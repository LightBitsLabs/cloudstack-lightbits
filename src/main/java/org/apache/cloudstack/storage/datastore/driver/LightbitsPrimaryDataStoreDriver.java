/*
 * Copyright 2026 Lightbits Labs Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cloudstack.storage.datastore.driver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.ChapInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.CreateCmdResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.RemoteHostEndPoint;
import org.apache.cloudstack.storage.command.CommandResult;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.CreateObjectAnswer;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.storage.StorageManager;
import com.cloud.vm.VirtualMachineManager;
import org.apache.cloudstack.storage.datastore.client.LightbitsApiClient;
import org.apache.cloudstack.storage.datastore.client.model.ClusterInfo;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig;
import org.apache.cloudstack.storage.datastore.util.LightbitsUtil;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.DataObjectType;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.host.Host;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.utils.Pair;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.exception.CloudRuntimeException;

public class LightbitsPrimaryDataStoreDriver implements PrimaryDataStoreDriver {

    private static final Logger LOG = LogManager.getLogger(LightbitsPrimaryDataStoreDriver.class);

    @Inject
    private StoragePoolDetailsDao storagePoolDetailsDao;
    @Inject
    private com.cloud.host.dao.HostDetailsDao hostDetailsDao;
    @Inject
    private EndPointSelector selector;
    @Inject
    private com.cloud.storage.dao.VMTemplatePoolDao vmTemplatePoolDao;
    @Inject
    private com.cloud.storage.dao.VolumeDao volumeDao;

    /** host_details key holding a KVM host's NVMe host NQN (populated for the host). */
    public static final String HOST_NQN_DETAIL = "nvme.hostnqn";

    /** Rebuild the validated pool config (incl. environment) from persisted details. */
    LightbitsPoolConfig configForPool(long poolId) {
        Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(poolId);
        String jwt = DBEncryptionUtil.decrypt(LightbitsPoolConfig.readChunked(details, LightbitsPoolConfig.KEY_JWT));
        return LightbitsPoolConfig.fromDetails(details, jwt);
    }

    /** Build an API client for a pool (by id) from its persisted (JWT-encrypted) details. */
    LightbitsApiClient clientForPool(long poolId) {
        return clientFor(configForPool(poolId));
    }

    private LightbitsApiClient clientFor(LightbitsPoolConfig cfg) {
        // v1 dev/lab TLS: trust-all (see open item L3). Production uses the validated default.
        return LightbitsApiClient.insecureForDevelopment(cfg.apiEndpoint(), cfg.getJwt(), cfg.getProject());
    }

    @Override
    public Map<String, String> getCapabilities() {
        Map<String, String> caps = new HashMap<>();
        caps.put("STORAGE_SYSTEM_SNAPSHOT", "true");
        caps.put("CAN_CREATE_VOLUME_FROM_SNAPSHOT", "true");
        caps.put("CAN_CREATE_VOLUME_FROM_VOLUME", "true");
        // Materialize root volumes from a cached template with a native Lightbits
        // snapshot+clone (instant, no agent qemu-img). Drives root-disk-from-template boot
        // via StorageSystemDataMotionStrategy.handleCreateVolumeFromTemplateBothOnStorageSystem.
        caps.put("CAN_CLONE_VOLUME_FROM_TEMPLATE", "true");
        return caps;
    }

    @Override
    public DataTO getTO(DataObject data) {
        return null;
    }

    @Override
    public DataStoreTO getStoreTO(DataStore store) {
        return null;
    }

    @Override
    public void createAsync(DataStore store, DataObject data, AsyncCompletionCallback<CreateCmdResult> callback) {
        String path = null;
        String errMsg = null;
        Answer answer = new Answer(null, false, "not started");
        try {
            if (data.getType() == DataObjectType.VOLUME) {
                VolumeInfo vol = (VolumeInfo) data;
                LightbitsPoolConfig cfg = configForPool(store.getId());
                org.apache.cloudstack.storage.datastore.client.model.Volume created =
                        clientFor(cfg).createVolume(
                                LightbitsUtil.volumeName(cfg.getEnvironment(), vol.getUuid()),
                                vol.getSize(), cfg.getReplicaCount(), null);
                // path identifies the backing volume; the KVM adaptor resolves the NVMe
                // device by this UUID (/dev/disk/by-id/nvme-uuid.<UUID>). The path must be
                // returned inside a VolumeObjectTO so CloudStack persists it on the volume.
                path = created.getUuid();
                VolumeObjectTO to = (VolumeObjectTO) vol.getTO();
                to.setPath(path);
                answer = new CreateObjectAnswer(to);
            } else if (data.getType() == DataObjectType.TEMPLATE) {
                // Managed-template caching: create a Lightbits volume to hold the template.
                // copyAsync then streams the template bits onto it (agent qemu-img).
                TemplateInfo tmpl = (TemplateInfo) data;
                LightbitsPoolConfig cfg = configForPool(store.getId());
                long size = tmpl.getSize() != null && tmpl.getSize() > 0 ? tmpl.getSize() : (1L << 30);
                org.apache.cloudstack.storage.datastore.client.model.Volume created =
                        clientFor(cfg).createVolume(
                                LightbitsUtil.volumeName(cfg.getEnvironment(), tmpl.getUuid()),
                                size, cfg.getReplicaCount(), null);
                path = created.getUuid();
                // The managed-template flow calls processEvent(OperationSucceeded) WITHOUT the
                // answer, so the framework does not persist our path. Set install_path on the
                // template_spool_ref ourselves (SolidFire/ScaleIO pattern) so the copy + attach
                // reach the device by the Lightbits volume UUID.
                com.cloud.storage.VMTemplateStoragePoolVO ref =
                        vmTemplatePoolDao.findByPoolTemplate(store.getId(), tmpl.getId(), null);
                if (ref != null) {
                    ref.setInstallPath(path);
                    ref.setLocalDownloadPath(path);
                    vmTemplatePoolDao.update(ref.getId(), ref);
                }
                TemplateObjectTO to = (TemplateObjectTO) tmpl.getTO();
                to.setPath(path);
                answer = new CreateObjectAnswer(to);
            } else {
                errMsg = "Lightbits plugin: createAsync supports VOLUME/TEMPLATE (got " + data.getType() + ")";
                answer = new Answer(null, false, errMsg);
            }
        } catch (Exception e) {
            errMsg = e.getMessage();
            answer = new Answer(null, false, errMsg);
        }
        if (callback == null) {
            if (errMsg != null) {
                throw new CloudRuntimeException(errMsg);
            }
            return;
        }
        CreateCmdResult result = new CreateCmdResult(path, answer);
        result.setResult(errMsg);
        callback.complete(result);
    }

    @Override
    public void deleteAsync(DataStore store, DataObject data, AsyncCompletionCallback<CommandResult> callback) {
        String errMsg = null;
        try {
            // TEMPLATE = the cached template's cluster volume (template eviction / storage
            // cleanup); VOLUME = a guest disk. Same naming scheme for both.
            if (data.getType() == DataObjectType.VOLUME || data.getType() == DataObjectType.TEMPLATE) {
                LightbitsPoolConfig cfg = configForPool(store.getId());
                LightbitsApiClient client = clientFor(cfg);
                String name = LightbitsUtil.volumeName(cfg.getEnvironment(), data.getUuid());
                client.getVolumeByName(name).ifPresent(v -> client.deleteVolume(v.getUuid()));
                deleteCloneSourceSnapshot(client, name);
            }
        } catch (Exception e) {
            errMsg = e.getMessage();
        }
        if (callback != null) {
            callback.complete(new CommandResult());
        }
        if (errMsg != null && callback == null) {
            throw new CloudRuntimeException(errMsg);
        }
    }

    /**
     * Drop the clone-source snapshot cloneOnStorage left behind (named {@code <cloneName>-src},
     * taken on the clone's source). Best-effort: LightOS refuses to delete a snapshot whose
     * clone still exists (e.g. clone deletion still in flight); the pool-delete sweep in the
     * lifecycle catches any such straggler.
     */
    private void deleteCloneSourceSnapshot(LightbitsApiClient client, String cloneName) {
        try {
            client.getSnapshotByName(cloneName + "-src").ifPresent(s -> client.deleteSnapshot(s.getUuid()));
        } catch (Exception e) {
            LOG.warn("Lightbits: could not delete clone-source snapshot {}-src ({}); "
                    + "it will be swept when the pool is deleted", cloneName, e.getMessage());
        }
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, AsyncCompletionCallback<CopyCommandResult> callback) {
        copyAsync(srcData, destData, null, callback);
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        Answer answer;
        String errMsg = null;

        // Storage-side clone: when the source (a cached template, or another volume) already
        // lives on THIS primary storage, materialize the destination volume with a native
        // Lightbits snapshot+clone -- instant, no agent qemu-img. This is the
        // CAN_CLONE_VOLUME_FROM_TEMPLATE path (StorageSystemDataMotionStrategy) and it drives
        // root-disk-from-template boot. NB: the framework invokes this with a null callback,
        // so the driver must persist the backing path itself.
        boolean srcOnPrimary = srcData.getDataStore() != null
                && srcData.getDataStore().getRole() == DataStoreRole.Primary;
        if (destData.getType() == DataObjectType.VOLUME && srcOnPrimary
                && (srcData.getType() == DataObjectType.TEMPLATE || srcData.getType() == DataObjectType.VOLUME)) {
            try {
                answer = new CopyCmdAnswer(cloneOnStorage(srcData, (VolumeInfo) destData));
            } catch (Exception e) {
                errMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                answer = new CopyCmdAnswer(errMsg);
            }
            completeCopy(callback, answer, errMsg);
            return;
        }

        // Otherwise (e.g. secondary image-store template -> primary template cache) delegate the
        // byte copy to a KVM host via a CopyCommand; the agent connects the dest volume (our
        // adaptor) and runs qemu-img convert.
        try {
            int wait = StorageManager.PRIMARY_STORAGE_DOWNLOAD_WAIT.value();
            CopyCommand cmd = new CopyCommand(srcData.getTO(), destData.getTO(), wait,
                    VirtualMachineManager.ExecuteInSequence.value());
            EndPoint ep = destHost != null ? RemoteHostEndPoint.getHypervisorHostEndPoint(destHost)
                    : selector.select(srcData, destData);
            if (ep == null) {
                answer = new Answer(cmd, false, "Lightbits copyAsync: no endpoint to run the copy");
            } else {
                answer = ep.sendMessage(cmd);
            }
        } catch (Exception e) {
            errMsg = e.getMessage();
            answer = new Answer(null, false, errMsg);
        }
        if (answer != null && !answer.getResult() && errMsg == null) {
            errMsg = answer.getDetails();
        }
        completeCopy(callback, answer, errMsg);
    }

    /**
     * Create the destination volume as a native Lightbits clone of a source object already on
     * this cluster (a cached template or another volume): snapshot the source, then create a
     * volume from that snapshot. Persists the backing path on the volume (the clone path runs
     * with a null callback, so the CopyCmdAnswer alone would not be applied) and returns the TO.
     */
    private VolumeObjectTO cloneOnStorage(DataObject srcData, VolumeInfo destVol) {
        DataStore store = destVol.getDataStore();
        LightbitsPoolConfig cfg = configForPool(store.getId());
        LightbitsApiClient client = clientFor(cfg);

        String srcName = LightbitsUtil.volumeName(cfg.getEnvironment(), srcData.getUuid());
        org.apache.cloudstack.storage.datastore.client.model.Volume src = client.getVolumeByName(srcName)
                .orElseThrow(() -> new CloudRuntimeException("clone source not found on cluster: " + srcName));

        String destName = LightbitsUtil.volumeName(cfg.getEnvironment(), destVol.getUuid());
        // Drop any empty placeholder createAsync may have pre-created under this name; the clone
        // becomes the real backing volume.
        client.getVolumeByName(destName).ifPresent(v -> client.deleteVolume(v.getUuid()));

        org.apache.cloudstack.storage.datastore.client.model.Snapshot snap =
                client.createSnapshot(destName + "-src", src.getUuid());
        // Clone at the larger of the requested volume size and the source image size; LightOS
        // requires an explicit size and rejects a clone smaller than its source.
        long size = Math.max(destVol.getSize() != null ? destVol.getSize() : 0L, src.getSizeBytes());
        org.apache.cloudstack.storage.datastore.client.model.Volume created =
                client.createVolumeFromSnapshot(destName, snap.getUuid(), size, cfg.getReplicaCount(), null);
        String path = created.getUuid();

        VolumeVO vo = volumeDao.findById(destVol.getId());
        if (vo != null) {
            vo.setPath(path);
            vo.setPoolId(store.getId());
            volumeDao.update(vo.getId(), vo);
        }
        VolumeObjectTO to = (VolumeObjectTO) destVol.getTO();
        to.setPath(path);
        return to;
    }

    private void completeCopy(AsyncCompletionCallback<CopyCommandResult> callback, Answer answer, String errMsg) {
        if (callback == null) {
            if (errMsg != null) {
                throw new CloudRuntimeException(errMsg);
            }
            return;
        }
        CopyCommandResult result = new CopyCommandResult(null, answer);
        if (errMsg != null) {
            result.setResult(errMsg);
        }
        callback.complete(result);
    }

    @Override
    public boolean canCopy(DataObject srcData, DataObject destData) {
        // We can materialize a volume from a template (root-disk-from-template) and clone
        // volume->volume within the cluster; the agent CopyCommand does the work.
        return destData.getType() == DataObjectType.VOLUME
                && (srcData.getType() == DataObjectType.TEMPLATE || srcData.getType() == DataObjectType.VOLUME);
    }

    @Override
    public void resize(DataObject data, AsyncCompletionCallback<CreateCmdResult> callback) {
        String errMsg = null;
        try {
            VolumeInfo vol = (VolumeInfo) data;
            LightbitsPoolConfig cfg = configForPool(data.getDataStore().getId());
            LightbitsApiClient client = clientFor(cfg);
            String name = LightbitsUtil.volumeName(cfg.getEnvironment(), vol.getUuid());
            org.apache.cloudstack.storage.datastore.client.model.Volume v = client.getVolumeByName(name)
                    .orElseThrow(() -> new CloudRuntimeException("volume not found on cluster: " + name));
            client.resizeVolume(v.getUuid(), vol.getSize());
        } catch (Exception e) {
            errMsg = e.getMessage();
        }
        completeCreate(callback, null, errMsg);
    }

    // --- helpers ---

    private void completeCreate(AsyncCompletionCallback<CreateCmdResult> callback, String path, String errMsg) {
        if (callback == null) {
            if (errMsg != null) {
                throw new CloudRuntimeException(errMsg);
            }
            return;
        }
        CreateCmdResult result = new CreateCmdResult(path, null);
        result.setResult(errMsg);
        callback.complete(result);
    }

    @Override
    public ChapInfo getChapInfo(DataObject dataObject) {
        // NVMe/TCP, not iSCSI: no CHAP
        return null;
    }

    @Override
    public boolean grantAccess(DataObject dataObject, Host host, DataStore dataStore) {
        String nqn = hostNqn(host);
        LightbitsPoolConfig cfg = configForPool(dataStore.getId());
        LightbitsApiClient client = clientFor(cfg);
        String name = LightbitsUtil.volumeName(cfg.getEnvironment(), dataObject.getUuid());
        org.apache.cloudstack.storage.datastore.client.model.Volume v = client.getVolumeByName(name)
                .orElseThrow(() -> new CloudRuntimeException("grantAccess: volume not found on cluster: " + name));
        java.util.List<String> acl = currentAcl(v);
        if (!acl.contains(nqn)) {
            acl.add(nqn);
            client.updateVolumeAcl(v.getUuid(), acl);
        }
        return true;
    }

    @Override
    public void revokeAccess(DataObject dataObject, Host host, DataStore dataStore) {
        String nqn = hostNqn(host);
        LightbitsPoolConfig cfg = configForPool(dataStore.getId());
        LightbitsApiClient client = clientFor(cfg);
        String name = LightbitsUtil.volumeName(cfg.getEnvironment(), dataObject.getUuid());
        client.getVolumeByName(name).ifPresent(v -> {
            java.util.List<String> acl = currentAcl(v);
            if (acl.remove(nqn)) {
                client.updateVolumeAcl(v.getUuid(), acl);
            }
        });
    }

    private String hostNqn(Host host) {
        com.cloud.host.DetailVO detail = hostDetailsDao.findDetail(host.getId(), HOST_NQN_DETAIL);
        if (detail == null || detail.getValue() == null || detail.getValue().isBlank()) {
            throw new CloudRuntimeException("host " + host.getId() + " has no '" + HOST_NQN_DETAIL
                    + "' detail; cannot grant Lightbits volume access. The HostListener auto-populates it on host"
                    + " connect - check nvme-cli on the host, then reconnect the host (see the listener warn log).");
        }
        return detail.getValue();
    }

    private java.util.List<String> currentAcl(org.apache.cloudstack.storage.datastore.client.model.Volume v) {
        java.util.List<String> acl = new java.util.ArrayList<>();
        if (v.getAcl() != null && v.getAcl().getValues() != null) {
            for (String val : v.getAcl().getValues()) {
                if (!"ALLOW_NONE".equals(val)) {
                    acl.add(val);
                }
            }
        }
        return acl;
    }

    @Override
    public long getDataObjectSizeIncludingHypervisorSnapshotReserve(DataObject dataObject, StoragePool storagePool) {
        // thin provisioning: the pool consumes the volume's virtual size (no reserve)
        return dataObject instanceof VolumeInfo ? ((VolumeInfo) dataObject).getSize() : 0L;
    }

    @Override
    public long getBytesRequiredForTemplate(TemplateInfo templateInfo, StoragePool storagePool) {
        return 0;
    }

    @Override
    public long getUsedBytes(StoragePool storagePool) {
        ClusterInfo info = clientForPool(storagePool.getId()).getClusterInfo();
        return info.getStatistics() == null ? 0L : info.getStatistics().usedBytes();
    }

    @Override
    public long getUsedIops(StoragePool storagePool) {
        return 0;
    }

    @Override
    public void takeSnapshot(SnapshotInfo snapshot, AsyncCompletionCallback<CreateCmdResult> callback) {
        // TODO: native Lightbits snapshot
        throw new UnsupportedOperationException("Lightbits plugin: not implemented yet");
    }

    @Override
    public void revertSnapshot(SnapshotInfo snapshotOnImageStore, SnapshotInfo snapshotOnPrimaryStore, AsyncCompletionCallback<CommandResult> callback) {
        throw new UnsupportedOperationException("Lightbits plugin: not implemented yet");
    }

    @Override
    public void handleQualityOfServiceForVolumeMigration(VolumeInfo volumeInfo, QualityOfServiceState qualityOfServiceState) {
        // no-op until QoS mapping is implemented
    }

    @Override
    public boolean canProvideStorageStats() {
        return true;   // capacity/usage come from the cluster API
    }

    @Override
    public Pair<Long, Long> getStorageStats(StoragePool storagePool) {
        ClusterInfo info = clientForPool(storagePool.getId()).getClusterInfo();
        ClusterInfo.Statistics s = info.getStatistics();
        if (s == null) {
            return null;
        }
        // Pair<capacity, used>
        return new Pair<>(s.effectiveBytes(), s.usedBytes());
    }

    @Override
    public boolean canProvideVolumeStats() {
        return false;
    }

    @Override
    public Pair<Long, Long> getVolumeStats(StoragePool storagePool, String volumeId) {
        throw new UnsupportedOperationException("Lightbits plugin: not implemented yet");
    }

    @Override
    public boolean volumesRequireGrantAccessWhenUsed() {
        // NVMe/TCP volumes must be ACL'd to the host NQN before the agent can connect them.
        return true;
    }

    @Override
    public boolean canHostAccessStoragePool(Host host, StoragePool pool) {
        // Any KVM host with NVMe/TCP reachability can access the pool; real per-host
        // reachability checks arrive with the M2 agent adaptor.
        return true;
    }

    @Override
    public boolean isVmInfoNeeded() {
        return false;
    }

    @Override
    public void provideVmInfo(long vmId, long volumeId) {
        // not needed
    }

    @Override
    public boolean isVmTagsNeeded(String tagKey) {
        return false;
    }

    @Override
    public void provideVmTags(long vmId, long volumeId, String tagValue) {
        // not needed
    }

    @Override
    public boolean isStorageSupportHA(StoragePoolType type) {
        return false;
    }

    @Override
    public void detachVolumeFromAllStorageNodes(Volume volume) {
        // No-op: Lightbits volumes are not pinned to storage nodes (ANA multipath across
        // the cluster). Nothing to detach at the storage layer.
    }
}
