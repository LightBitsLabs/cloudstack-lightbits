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
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.storage.StorageManager;
import com.cloud.vm.VirtualMachineManager;
import org.apache.cloudstack.storage.datastore.client.LightbitsApiClient;
import org.apache.cloudstack.storage.datastore.client.VolumeExtras;
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
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.utils.Pair;
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
    @Inject
    private org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao diskOfferingDetailsDao;
    @Inject
    private com.cloud.service.dao.ServiceOfferingDao serviceOfferingDao;
    @Inject
    private com.cloud.service.dao.ServiceOfferingDetailsDao serviceOfferingDetailsDao;
    @Inject
    private com.cloud.user.dao.AccountDao accountDao;

    /** host_details key holding a KVM host's NVMe host NQN (populated for the host). */
    public static final String HOST_NQN_DETAIL = "nvme.hostnqn";

    /**
     * Offering-detail key overriding the pool's replica factor per volume (I8).
     * Set on a DISK offering ({@code details[0].lightbitsReplicas=N}) for data disks, or on a
     * COMPUTE offering ({@code serviceofferingdetails[0].lightbitsReplicas=N}) for root disks.
     */
    public static final String REPLICAS_DETAIL = "lightbitsReplicas";

    /**
     * The replica factor for a volume: the offering's {@link #REPLICAS_DETAIL} when present
     * (disk offering first; for root disks the volume's disk offering is the compute
     * offering's implicit one, so fall through to the service-offering details), else the
     * pool default.
     */
    private int replicasFor(VolumeInfo vol, LightbitsPoolConfig cfg) {
        String value = offeringDetail(vol, REPLICAS_DETAIL);
        if (value == null) {
            return cfg.getReplicaCount();
        }
        int replicas = LightbitsUtil.parseReplicasDetail(value);
        LOG.debug("Lightbits: volume {} uses offering replica override {}", vol.getUuid(), replicas);
        return replicas;
    }

    /**
     * Offering-detail key overriding the pool's QoS policy per volume, mirroring
     * {@link #REPLICAS_DETAIL}. Set on a DISK offering
     * ({@code details[0].lightbitsQosPolicy=<name>}) for data disks, or on a COMPUTE offering
     * ({@code serviceofferingdetails[0].lightbitsQosPolicy=<name>}) for root disks.
     */
    public static final String QOS_POLICY_DETAIL = "lightbitsQosPolicy";

    /**
     * The effective QoS policy NAME for a volume: the offering's {@link #QOS_POLICY_DETAIL}
     * when present (disk offering first, then the compute offering's details for root disks),
     * else the pool default ({@code ?qosPolicy=} → cfg.getQosPolicy()). Null/blank means no
     * QoS policy — the volume is created without one. The name is passed straight to volume
     * create (matching lbcli --qos-policy-name and the CSI driver); no UUID resolution.
     */
    /**
     * The QoS policy for a volume, in this order: the offering's {@link #QOS_POLICY_DETAIL};
     * with the pool option {@code qosFromIops=true} and a max IOPS on the volume, the IOPS
     * policy picked by {@link LightbitsUtil#policyForIops}; the pool default. Null: no policy.
     * A policy named by an offering is checked when {@code checkOfferingName} (the pool
     * default was checked at registration, an offering's name never was).
     */
    private String qosPolicyNameFor(Long diskOfferingId, Long maxIops, LightbitsPoolConfig cfg,
                                    LightbitsApiClient client, boolean checkOfferingName) {
        String fromOffering = offeringDetail(diskOfferingId, QOS_POLICY_DETAIL);
        if (fromOffering != null && !fromOffering.isBlank()) {
            String name = fromOffering.trim();
            if (checkOfferingName && !client.qosPolicyExists(name)) {
                throw new CloudRuntimeException("QoS policy '" + name + "' (offering detail " + QOS_POLICY_DETAIL
                        + ") is not visible to Lightbits project " + client.getProject()
                        + "; a cluster admin must create it for that project, or correct the offering");
            }
            return name;
        }
        if (cfg.isQosFromIops() && maxIops != null && maxIops > 0) {
            List<LightbitsApiClient.IopsPolicy> policies = client.listIopsPolicies();
            String name = LightbitsUtil.policyForIops(policies, maxIops);
            if (name == null) {
                throw new CloudRuntimeException("no Lightbits QoS policy visible to project " + client.getProject()
                        + " allows at least " + maxIops + " IOPS for reads and writes (pool option "
                        + LightbitsPoolConfig.KEY_QOS_FROM_IOPS + "); policies with an IOPS limit: "
                        + (policies.isEmpty() ? "none" : policies) + ". A cluster admin must create one, "
                        + "or set " + QOS_POLICY_DETAIL + " on the offering");
            }
            return name;
        }
        String poolDefault = cfg.getQosPolicy();
        return (poolDefault == null || poolDefault.isBlank()) ? null : poolDefault.trim();
    }

    private String checkedQosPolicyNameFor(VolumeInfo vol, LightbitsPoolConfig cfg, LightbitsApiClient client) {
        return qosPolicyNameFor(vol.getDiskOfferingId(), vol.getMaxIops(), cfg, client, true);
    }

    /**
     * Bring a volume's QoS policy in line with its offering after an offering change. Best
     * effort: a failure is logged and the next attach or resize tries again. A volume whose
     * offering and pool name no policy keeps the one it has (the cluster has no "none").
     */
    private void reconcileQosPolicy(LightbitsApiClient client, LightbitsPoolConfig cfg, Long diskOfferingId, Long maxIops,
                                    org.apache.cloudstack.storage.datastore.client.model.Volume v) {
        try {
            String wanted = qosPolicyNameFor(diskOfferingId, maxIops, cfg, client, false);
            if (wanted == null || wanted.equals(v.getQosPolicyName())) {
                return;
            }
            client.updateVolumeQosPolicy(v.getUuid(), wanted);
            LOG.info("Lightbits: volume {} QoS policy changed from {} to {}", v.getName(), v.getQosPolicyName(), wanted);
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: could not change the QoS policy of volume {} to match its offering ({}); "
                    + "it is tried again at the next attach or resize", v.getName(), e.getMessage());
        }
    }

    /**
     * An offering detail of a volume: the disk offering's detail first; for a root disk the
     * disk offering is the compute offering's implicit one, so then the compute offering's
     * details. Null when neither sets it.
     */
    private String offeringDetail(VolumeInfo vol, String key) {
        return offeringDetail(vol.getDiskOfferingId(), key);
    }

    private String offeringDetail(Long diskOfferingId, String key) {
        if (diskOfferingId == null) {
            return null;
        }
        String value = diskOfferingDetailsDao.getDetail(diskOfferingId, key);
        if (value == null) {
            com.cloud.service.ServiceOfferingVO svc =
                    serviceOfferingDao.findServiceOfferingByComputeOnlyDiskOffering(diskOfferingId, false);
            if (svc != null) {
                value = serviceOfferingDetailsDao.getDetail(svc.getId(), key);
            }
        }
        return value;
    }

    /** Offering detail turning compression on or off for a volume, overriding the pool's {@code compression}. */
    public static final String COMPRESSION_DETAIL = "lightbitsCompression";
    /** Offering detail with comma-separated failure domains the volume (or its primary replica) is placed in. */
    public static final String PLACEMENT_DETAIL = "lightbitsPlacement";
    /** Offering detail asking for an unencrypted volume on a cluster in hybrid encryption mode. */
    public static final String UNENCRYPTED_DETAIL = "lightbitsUnencrypted";

    /** Label keys the plugin sets on every volume it creates. */
    public static final String LABEL_VOLUME = "cloudstack-volume";
    public static final String LABEL_TEMPLATE = "cloudstack-template";
    public static final String LABEL_ACCOUNT = "cloudstack-account";
    public static final String LABEL_POOL = "cloudstack-pool";

    /** Compression for a volume: the offering's {@link #COMPRESSION_DETAIL}, else the pool's setting. */
    private boolean compressionFor(VolumeInfo vol, LightbitsPoolConfig cfg) {
        String value = offeringDetail(vol, COMPRESSION_DETAIL);
        return value == null ? cfg.isCompression() : LightbitsUtil.parseBooleanDetail(COMPRESSION_DETAIL, value);
    }

    /** Labels, placement and encryption choice for a new volume. */
    private VolumeExtras extrasFor(VolumeInfo vol, DataStore store) {
        java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
        labels.put(LABEL_VOLUME, vol.getUuid());
        String account = accountUuid(vol.getAccountId());
        if (account != null) {
            labels.put(LABEL_ACCOUNT, account);
        }
        labels.put(LABEL_POOL, store.getUuid());
        String placement = offeringDetail(vol, PLACEMENT_DETAIL);
        String unencrypted = offeringDetail(vol, UNENCRYPTED_DETAIL);
        return new VolumeExtras(labels,
                placement == null ? List.of() : LightbitsUtil.parsePlacementDetail(placement),
                unencrypted != null && LightbitsUtil.parseBooleanDetail(UNENCRYPTED_DETAIL, unencrypted));
    }

    private String accountUuid(long accountId) {
        try {
            com.cloud.user.Account a = accountDao.findByIdIncludingRemoved(accountId);
            return a == null ? null : a.getUuid();
        } catch (RuntimeException e) {
            LOG.debug("Lightbits: no account uuid for account {}: {}", accountId, e.getMessage());
            return null;
        }
    }

    /** Rebuild the validated pool config (incl. environment) from persisted details. */
    LightbitsPoolConfig configForPool(long poolId) {
        return org.apache.cloudstack.storage.datastore.util.LightbitsPoolSecret.load(storagePoolDetailsDao, poolId);
    }

    /** Build an API client for a pool (by id) from its persisted (JWT-encrypted) details. */
    LightbitsApiClient clientForPool(long poolId) {
        return clientFor(configForPool(poolId));
    }

    LightbitsApiClient clientFor(LightbitsPoolConfig cfg) {
        return cfg.newClient();
    }

    @Override
    public Map<String, String> getCapabilities() {
        Map<String, String> caps = new HashMap<>();
        caps.put("STORAGE_SYSTEM_SNAPSHOT", "true");
        caps.put("CAN_CREATE_VOLUME_FROM_SNAPSHOT", "true");
        caps.put("CAN_CREATE_VOLUME_FROM_VOLUME", "true");
        caps.put("CAN_CREATE_TEMPLATE_FROM_SNAPSHOT", "true");
        // Without this StorageSystemSnapshotStrategy cannot handle REVERT for a backend
        // snapshot and revertSnapshot fails in the core before the driver is ever called.
        caps.put("CAN_REVERT_VOLUME_TO_SNAPSHOT", "true");
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
                LightbitsApiClient client = clientFor(cfg);
                org.apache.cloudstack.storage.datastore.client.model.Volume created =
                        client.createVolume(
                                LightbitsUtil.volumeName(cfg.getEnvironment(), store.getId(), vol.getUuid()),
                                vol.getSize(), replicasFor(vol, cfg), null, compressionFor(vol, cfg),
                                checkedQosPolicyNameFor(vol, cfg, client), extrasFor(vol, store));
                // path identifies the backing volume; the KVM adaptor resolves the NVMe
                // device by this UUID (/dev/disk/by-id/nvme-uuid.<UUID>). The path must be
                // returned inside a VolumeObjectTO so CloudStack persists it on the volume.
                path = created.getUuid();
                VolumeObjectTO to = (VolumeObjectTO) vol.getTO();
                to.setPath(path);
                // Managed block volumes have no image-file format, but CloudStack's snapshot
                // path (StorageSystemSnapshotStrategy.verifyFormat) rejects a null format with
                // "Only VHD, OVA, QCOW2, RAW are supported". Stamp RAW so snapshots/clones work.
                VolumeVO vo = volumeDao.findById(vol.getId());
                if (vo != null && vo.getFormat() != ImageFormat.RAW) {
                    vo.setFormat(ImageFormat.RAW);
                    volumeDao.update(vo.getId(), vo);
                }
                answer = new CreateObjectAnswer(to);
            } else if (data.getType() == DataObjectType.TEMPLATE) {
                // Managed-template caching: create a Lightbits volume to hold the template.
                // copyAsync then streams the template bits onto it (agent qemu-img).
                TemplateInfo tmpl = (TemplateInfo) data;
                LightbitsPoolConfig cfg = configForPool(store.getId());
                long size = tmpl.getSize() != null && tmpl.getSize() > 0 ? tmpl.getSize() : (1L << 30);
                org.apache.cloudstack.storage.datastore.client.model.Volume created =
                        clientFor(cfg).createVolume(
                                LightbitsUtil.volumeName(cfg.getEnvironment(), store.getId(), tmpl.getUuid()),
                                size, cfg.getReplicaCount(), null, cfg.isCompression(), null,
                                new VolumeExtras(java.util.Map.of(LABEL_TEMPLATE, tmpl.getUuid(), LABEL_POOL, store.getUuid()),
                                        List.of(), false));
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
            // cleanup); VOLUME = a guest disk; SNAPSHOT = a user volume snapshot. Same
            // cs-<environment>-p<poolId>-<uuid> naming scheme for all three.
            if (data.getType() == DataObjectType.VOLUME || data.getType() == DataObjectType.TEMPLATE) {
                LightbitsPoolConfig cfg = configForPool(store.getId());
                LightbitsApiClient client = clientFor(cfg);
                String name = LightbitsUtil.volumeName(cfg.getEnvironment(), store.getId(), data.getUuid());
                // includes a volume an earlier, interrupted delete left in Deleting: that removal
                // is waited for (without a second DELETE) rather than taken for done
                java.util.Optional<org.apache.cloudstack.storage.datastore.client.model.Volume> lbVol =
                        client.getVolumeByNameIncludingDeleting(name);
                if (lbVol.isPresent()) {
                    if (!lbVol.get().isDeletedOrDeleting()) {
                        client.deleteVolume(lbVol.get().getUuid());
                    }
                    // the clone-source snapshot can only go once its clone is really gone
                    client.waitForVolumeGone(lbVol.get().getUuid());
                    LAST_STATS.remove(lbVol.get().getUuid());
                }
                // Not found is success (idempotent teardown: a retried delete converges). The
                // plugin's own clone-source snapshot goes with the volume. Snapshots the USER
                // took of this volume are CloudStack objects with their own lifecycle and stay.
                deleteCloneSourceSnapshot(client, name);
            } else if (data.getType() == DataObjectType.SNAPSHOT) {
                LightbitsPoolConfig cfg = configForPool(store.getId());
                LightbitsApiClient client = clientFor(cfg);
                String name = LightbitsUtil.volumeName(cfg.getEnvironment(), store.getId(), data.getUuid());
                client.getSnapshotByName(name).ifPresent(s -> client.deleteSnapshot(s.getUuid()));
            }
        } catch (Exception e) {
            // A failed cluster-side delete must fail the CloudStack operation: reporting
            // success here would orphan the volume on the cluster for good.
            errMsg = "Lightbits: delete of " + data.getType() + " " + data.getUuid() + " failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.toString());
            LOG.warn(errMsg, e);
        }
        if (callback == null) {
            if (errMsg != null) {
                throw new CloudRuntimeException(errMsg);
            }
            return;
        }
        CommandResult result = new CommandResult();
        result.setResult(errMsg);
        callback.complete(result);
    }

    /**
     * Drop the clone-source snapshot cloneOnStorage left behind (named {@code <cloneName>-src},
     * taken on the clone's source). Failures propagate: the caller fails the delete, CloudStack
     * retries it, and the retry finds the volume gone and comes straight back here.
     */
    private void deleteCloneSourceSnapshot(LightbitsApiClient client, String cloneName) {
        client.getSnapshotByName(cloneName + "-src").ifPresent(s -> client.deleteSnapshot(s.getUuid()));
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
                && (srcData.getType() == DataObjectType.TEMPLATE || srcData.getType() == DataObjectType.VOLUME
                    || srcData.getType() == DataObjectType.SNAPSHOT)) {
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
     *
     * Source and destination may live on DIFFERENT pools (e.g. a template cached on one pool
     * cloned into a volume on another, or a cross-pool volume clone), so each name is built
     * with ITS OWN pool id. Both objects share one Lightbits project/cluster, so a single
     * client (built from the destination pool's config) can see both.
     */
    private VolumeObjectTO cloneOnStorage(DataObject srcData, VolumeInfo destVol) {
        DataStore store = destVol.getDataStore();
        long destPoolId = store.getId();
        long srcPoolId = srcData.getDataStore() != null ? srcData.getDataStore().getId() : destPoolId;
        LightbitsPoolConfig cfg = configForPool(destPoolId);
        // environment can differ per pool; resolve the source's own environment for its name.
        LightbitsPoolConfig srcCfg = srcPoolId == destPoolId ? cfg : configForPool(srcPoolId);
        // The single (destination-pool) client can only see the source if both pools live on
        // the same cluster and project. Fail with a pointed message instead of the confusing
        // "clone source not found on cluster" a cross-project lookup would produce.
        if (!srcCfg.getProject().equals(cfg.getProject())
                || !srcCfg.apiEndpoint().equals(cfg.apiEndpoint())) {
            throw new CloudRuntimeException("Lightbits: cannot clone across pools in different projects/clusters ("
                    + srcCfg.apiEndpoint() + "/" + srcCfg.getProject() + " -> "
                    + cfg.apiEndpoint() + "/" + cfg.getProject() + ")");
        }
        LightbitsApiClient client = clientFor(cfg);

        String srcName = LightbitsUtil.volumeName(srcCfg.getEnvironment(), srcPoolId, srcData.getUuid());
        String destName = LightbitsUtil.volumeName(cfg.getEnvironment(), destPoolId, destVol.getUuid());
        // Drop any empty placeholder createAsync may have pre-created under this name; the clone
        // becomes the real backing volume.
        client.getVolumeByName(destName).ifPresent(v -> client.deleteVolume(v.getUuid()));

        org.apache.cloudstack.storage.datastore.client.model.Snapshot snap;
        long srcSize;
        int srcReplicas;
        if (srcData.getType() == DataObjectType.SNAPSHOT) {
            // volume-from-snapshot: the Lightbits snapshot already exists (takeSnapshot made it)
            snap = client.getSnapshotByName(srcName).orElseThrow(
                    () -> new CloudRuntimeException("clone source snapshot not found on cluster: " + srcName));
            // Size and replica count come from the snapshot itself: its base volume may have
            // been resized or deleted since, and a missing base volume must not read as "0 bytes".
            srcSize = snap.getSizeBytes();
            srcReplicas = snap.getReplicaCount() > 0 ? snap.getReplicaCount() : cfg.getReplicaCount();
        } else {
            // template/volume source: snapshot it, then clone from that snapshot
            org.apache.cloudstack.storage.datastore.client.model.Volume src = client.getVolumeByName(srcName)
                    .orElseThrow(() -> new CloudRuntimeException("clone source not found on cluster: " + srcName));
            snap = client.createSnapshot(destName + "-src", src.getUuid());
            srcSize = src.getSizeBytes();
            srcReplicas = src.getReplicaCount();
        }
        // From here on the clone-source snapshot taken above is ours to remove if anything fails,
        // including the lookups before the clone request is sent.
        org.apache.cloudstack.storage.datastore.client.model.Volume created;
        try {
            // Clone at the larger of the requested volume size and the source image size; LightOS
            // requires an explicit size and rejects a clone smaller than its source.
            long size = Math.max(destVol.getSize() != null ? destVol.getSize() : 0L, srcSize);
            // LightOS requires a clone's replicaCount to EQUAL its source's (and replicaCount is
            // immutable after create), so clones always inherit the source's replica factor. If
            // the offering asks for a different lightbitsReplicas, warn — don't fail the deploy.
            int replicas = srcReplicas > 0 ? srcReplicas : cfg.getReplicaCount();
            int requested = replicasFor(destVol, cfg);
            if (requested != replicas) {
                LOG.warn("Lightbits: volume {} requested {} replicas via its offering, but clones inherit their "
                        + "source's replica factor ({}): the cluster requires clone == source and the count is "
                        + "immutable; using {}", destVol.getUuid(), requested, replicas, replicas);
            }
            // A clone shares its source's data: it keeps the source's placement and encryption,
            // and only takes the new volume's labels (see createVolumeFromSnapshot).
            created = client.createVolumeFromSnapshot(destName, snap.getUuid(), size, replicas, null,
                    compressionFor(destVol, cfg), checkedQosPolicyNameFor(destVol, cfg, client), extrasFor(destVol, store));
        } catch (RuntimeException e) {
            // Do not leave the clone-source snapshot we just took behind a failed clone. A clone
            // this call created has been removed, and that removal waited for, by the client.
            // Only a snapshot THIS call created: a user's snapshot is never ours, and a
            // clone-source snapshot found through a 409 belongs to an earlier attempt that
            // may still need it.
            if (srcData.getType() != DataObjectType.SNAPSHOT && snap.isCreatedByThisCall()) {
                try {
                    client.deleteSnapshot(snap.getUuid());
                } catch (RuntimeException cleanup) {
                    LOG.warn("Lightbits: could not remove clone-source snapshot {}-src after a failed clone: {}",
                            destName, cleanup.getMessage());
                }
            }
            throw e;
        }
        String path = created.getUuid();

        VolumeVO vo = volumeDao.findById(destVol.getId());
        if (vo != null) {
            vo.setPath(path);
            vo.setPoolId(store.getId());
            // See createAsync: RAW so the snapshot path's verifyFormat accepts this volume.
            vo.setFormat(ImageFormat.RAW);
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
        // We can materialize a volume from a template (root-disk-from-template), clone
        // volume->volume, and clone volume-from-snapshot within the cluster.
        return destData.getType() == DataObjectType.VOLUME
                && (srcData.getType() == DataObjectType.TEMPLATE || srcData.getType() == DataObjectType.VOLUME
                    || srcData.getType() == DataObjectType.SNAPSHOT);
    }

    @Override
    public void resize(DataObject data, AsyncCompletionCallback<CreateCmdResult> callback) {
        String errMsg = null;
        try {
            VolumeInfo vol = (VolumeInfo) data;
            // the NEW size travels in the ResizeVolumePayload; vol.getSize() is still the old
            // size at this point (ScaleIO/SolidFire read the payload the same way)
            com.cloud.storage.ResizeVolumePayload payload =
                    (com.cloud.storage.ResizeVolumePayload) vol.getpayload();
            long newSize = payload != null && payload.newSize != null ? payload.newSize : vol.getSize();

            long poolId = data.getDataStore().getId();
            LightbitsPoolConfig cfg = configForPool(poolId);
            LightbitsApiClient client = clientFor(cfg);
            String name = LightbitsUtil.volumeName(cfg.getEnvironment(), poolId, vol.getUuid());
            org.apache.cloudstack.storage.datastore.client.model.Volume v = client.getVolumeByName(name)
                    .orElseThrow(() -> new CloudRuntimeException("volume not found on cluster: " + name));
            if (newSize < v.getSizeBytes()) {
                throw new CloudRuntimeException("Lightbits volumes cannot shrink ("
                        + v.getSizeBytes() + " -> " + newSize + " bytes)");
            }
            if (newSize > v.getSizeBytes()) {
                client.resizeVolume(v.getUuid(), newSize);
                LOG.info("Lightbits: resized volume {} from {} to {} bytes", name, v.getSizeBytes(), newSize);
            }
            // An offering change reaches the driver only through resize (CloudStack calls it
            // when the size or the IOPS change); the new offering is in the payload.
            Long offeringId = payload != null && payload.getNewDiskOfferingId() != null
                    ? payload.getNewDiskOfferingId() : vol.getDiskOfferingId();
            Long maxIops = payload != null && payload.newMaxIops != null ? payload.newMaxIops : vol.getMaxIops();
            reconcileQosPolicy(client, cfg, offeringId, maxIops, v);
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
        long poolId = dataStore.getId();
        LightbitsPoolConfig cfg = configForPool(poolId);
        LightbitsApiClient client = clientFor(cfg);
        String name = LightbitsUtil.volumeName(cfg.getEnvironment(), poolId, dataObject.getUuid());
        org.apache.cloudstack.storage.datastore.util.LightbitsAcl.update(client, name, true, acl -> {
            if (acl.contains(nqn)) {
                return false;
            }
            acl.add(nqn);
            return true;
        });
        if (dataObject instanceof VolumeInfo) {
            // An offering change with the same size and IOPS never reaches resize: pick it up here.
            client.getVolumeByName(name).ifPresent(v ->
                    reconcileQosPolicy(client, cfg, ((VolumeInfo) dataObject).getDiskOfferingId(),
                            ((VolumeInfo) dataObject).getMaxIops(), v));
        }
        return true;
    }

    @Override
    public void revokeAccess(DataObject dataObject, Host host, DataStore dataStore) {
        String nqn = hostNqn(host);
        long poolId = dataStore.getId();
        LightbitsPoolConfig cfg = configForPool(poolId);
        LightbitsApiClient client = clientFor(cfg);
        String name = LightbitsUtil.volumeName(cfg.getEnvironment(), poolId, dataObject.getUuid());
        org.apache.cloudstack.storage.datastore.util.LightbitsAcl.update(client, name, false, acl -> acl.remove(nqn));
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
        // Cluster-wide used bytes (GET /api/v2/cluster) are not readable by the pool's
        // project-scoped JWT. Report provisioned bytes = sum of THIS pool's live volume
        // sizes — a project can host several pools, so filter by the per-pool name prefix —
        // read via the project-scoped volume list the token is allowed.
        //
        // Failures propagate: this value feeds allocation decisions, and a fabricated 0
        // ("pool is empty") would green-light allocations against unknown state.
        long poolId = storagePool.getId();
        LightbitsPoolConfig cfg = configForPool(poolId);
        try {
            long total = 0L;
            for (org.apache.cloudstack.storage.datastore.client.model.Volume v
                    : clientFor(cfg).listVolumes()) {
                if (LightbitsUtil.isPoolObject(cfg.getEnvironment(), poolId, v.getName())) {
                    total += v.getSizeBytes();
                }
            }
            return total;
        } catch (Exception e) {
            throw new CloudRuntimeException("Lightbits: cannot compute provisioned bytes for pool "
                    + poolId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public long getUsedIops(StoragePool storagePool) {
        return 0;
    }

    @Override
    public void takeSnapshot(SnapshotInfo snapshot, AsyncCompletionCallback<CreateCmdResult> callback) {
        CreateCmdResult result;
        try {
            VolumeInfo vol = snapshot.getBaseVolume();
            VolumeVO volVO = volumeDao.findById(vol.getId());
            if (volVO == null || volVO.getRemoved() != null || volVO.getPoolId() == null) {
                throw new CloudRuntimeException("the volume this snapshot belongs to is no longer on a"
                        + " Lightbits pool (volume " + vol.getUuid() + ")");
            }
            long poolId = volVO.getPoolId();
            LightbitsPoolConfig cfg = configForPool(poolId);
            LightbitsApiClient client = clientFor(cfg);

            String volName = LightbitsUtil.volumeName(cfg.getEnvironment(), poolId, vol.getUuid());
            org.apache.cloudstack.storage.datastore.client.model.Volume lbVol =
                    client.getVolumeByName(volName).orElseThrow(() -> new CloudRuntimeException(
                            "base volume not found on cluster: " + volName));

            // Native Lightbits snapshot, named like volumes: cs-<environment>-p<poolId>-<snapshotUuid>
            String snapName = LightbitsUtil.volumeName(cfg.getEnvironment(), poolId, snapshot.getUuid());
            org.apache.cloudstack.storage.datastore.client.model.Snapshot s =
                    client.createSnapshot(snapName, lbVol.getUuid());

            SnapshotObjectTO to = (SnapshotObjectTO) snapshot.getTO();
            to.setPath(s.getUuid());
            result = new CreateCmdResult(null, new CreateObjectAnswer(to));
            result.setResult(null);
            LOG.info("Lightbits: snapshot {} of volume {} -> {}", snapName, volName, s.getUuid());
        } catch (Exception e) {
            LOG.warn("Lightbits: takeSnapshot failed for snapshot {}", snapshot.getUuid(), e);
            result = new CreateCmdResult(null, new CreateObjectAnswer(e.toString()));
            result.setResult(e.toString());
        }
        callback.complete(result);
    }

    @Override
    public void revertSnapshot(SnapshotInfo snapshotOnImageStore, SnapshotInfo snapshotOnPrimaryStore, AsyncCompletionCallback<CommandResult> callback) {
        SnapshotInfo snapshot = snapshotOnPrimaryStore != null ? snapshotOnPrimaryStore : snapshotOnImageStore;
        CommandResult result = new CommandResult();
        try {
            VolumeInfo vol = snapshot.getBaseVolume();
            VolumeVO volVO = volumeDao.findById(vol.getId());
            if (volVO == null || volVO.getRemoved() != null || volVO.getPoolId() == null) {
                throw new CloudRuntimeException("the volume this snapshot belongs to no longer exists"
                        + " or is not on a Lightbits pool");
            }
            long poolId = volVO.getPoolId();
            LightbitsPoolConfig cfg = configForPool(poolId);
            LightbitsApiClient client = clientFor(cfg);

            String volName = LightbitsUtil.volumeName(cfg.getEnvironment(), poolId, vol.getUuid());
            org.apache.cloudstack.storage.datastore.client.model.Volume lbVol =
                    client.getVolumeByName(volName).orElseThrow(() -> new CloudRuntimeException(
                            "base volume not found on cluster: " + volName));
            String snapName = LightbitsUtil.volumeName(cfg.getEnvironment(), poolId, snapshot.getUuid());
            org.apache.cloudstack.storage.datastore.client.model.Snapshot s =
                    client.getSnapshotByName(snapName).orElseThrow(() -> new CloudRuntimeException(
                            "snapshot not found on cluster: " + snapName));

            // A rollback restores the snapshot's SIZE as well as its content: reverting a
            // volume that was grown since would shrink it under CloudStack, which keeps the
            // larger size on record. Refuse instead of silently truncating.
            if (s.getSizeBytes() <= 0) {
                throw new CloudRuntimeException("cannot revert volume " + vol.getUuid() + " to snapshot "
                        + snapshot.getUuid() + ": the cluster does not report the snapshot's size, so a"
                        + " shrink of the volume cannot be ruled out.");
            }
            if (s.getSizeBytes() < lbVol.getSizeBytes()) {
                throw new CloudRuntimeException("cannot revert volume " + vol.getUuid() + " to snapshot "
                        + snapshot.getUuid() + ": the volume was grown after the snapshot was taken ("
                        + s.getSizeBytes() + " -> " + lbVol.getSizeBytes() + " bytes) and a revert would"
                        + " shrink it. Create a new volume from the snapshot instead.");
            }
            // The rollback rewrites the volume in place. CloudStack only allows a revert with
            // the VM stopped or the volume detached, and both revoke host access; a host still
            // in the ACL means something is (or may be) using the device.
            java.util.List<String> acl = org.apache.cloudstack.storage.datastore.util.LightbitsAcl.hosts(lbVol);
            if (!acl.isEmpty()) {
                throw new CloudRuntimeException("cannot revert volume " + vol.getUuid() + " to snapshot "
                        + snapshot.getUuid() + ": the volume is still exposed to host(s) " + acl
                        + ". Stop the VM or detach the volume first.");
            }
            // native in-place rollback (PUT .../volumes/{uuid}/rollback)
            if (lbVol.getEtag() == null || lbVol.getEtag().isEmpty()) {
                throw new CloudRuntimeException("cannot revert volume " + vol.getUuid() + " to snapshot "
                        + snapshot.getUuid() + ": the cluster does not report the volume's revision (ETag),"
                        + " so the revert cannot be bound to the state that was checked.");
            }
            // Conditional on the revision the two guards above looked at: a resize or a host
            // attach landing in between gets a 412 from the cluster and nothing is rolled back.
            client.rollbackVolume(lbVol.getUuid(), s.getUuid(), lbVol.getEtag());
            LOG.info("Lightbits: rolled back volume {} to snapshot {}", volName, snapName);
        } catch (Exception e) {
            LOG.warn("Lightbits: revertSnapshot failed for snapshot {}", snapshot.getUuid(), e);
            result.setResult(e.getMessage());
        }
        callback.complete(result);
    }

    @Override
    public void handleQualityOfServiceForVolumeMigration(VolumeInfo volumeInfo, QualityOfServiceState qualityOfServiceState) {
        // no-op until QoS mapping is implemented
    }

    @Override
    public boolean canProvideStorageStats() {
        // Capacity/usage come from GET /api/v2/nodes, which the pool's project-scoped JWT IS
        // allowed to read (the cluster-wide GET /api/v2/cluster is not). This keeps capacity
        // refreshed at runtime — cluster scale-out is picked up on the next stats cycle
        // instead of being frozen at registration time.
        return true;
    }

    @Override
    public Pair<Long, Long> getStorageStats(StoragePool storagePool) {
        // Pair<capacity, used>, both scaled by the pool's replica factor: raw physical bytes
        // across nodes / replicaCount = logical bytes this pool can provision. Values are
        // CLUSTER-wide (LightOS has no per-project space quota), so pools sharing a cluster
        // all report the same underlying capacity — a known, documented double-count.
        try {
            long poolId = storagePool.getId();
            LightbitsPoolConfig cfg = configForPool(poolId);
            LightbitsApiClient.NodeCapacity nc = clientFor(cfg).nodeCapacity();
            if (nc.effectivePhysicalBytes <= 0) {
                LOG.warn("Lightbits: nodes report no effective capacity for pool {}; skipping stats cycle", poolId);
                return null;
            }
            long replicas = Math.max(1, cfg.getReplicaCount());
            long capacity = nc.effectivePhysicalBytes / replicas;
            long used = Math.max(0L, nc.effectivePhysicalBytes - nc.freePhysicalBytes) / replicas;
            return new Pair<>(capacity, used);
        } catch (Exception e) {
            // The stats path must degrade gracefully and never wedge the collector; null is
            // the documented contract for "no stats this cycle".
            LOG.warn("Lightbits: could not read node capacity for pool {} ({}); skipping stats cycle",
                    storagePool.getId(), e.getMessage());
            return null;
        }
    }

    @Override
    public boolean canProvideVolumeStats() {
        // The cluster knows how much of each volume holds data; a KVM host would only see a
        // raw block device of the full size.
        return true;
    }

    @Override
    public Pair<Long, Long> getVolumeStats(StoragePool storagePool, String volumeId) {
        StatsBackoff backoff = STATS_BACKOFF.computeIfAbsent(storagePool.getId(), id -> new StatsBackoff(System::currentTimeMillis));
        return volumeStats(clientForPool(storagePool.getId()), volumeId, LAST_STATS, backoff);
    }

    /** How long after a failed statistics read of a pool its volumes are not read again. */
    static final long STATS_RETRY_MILLIS = 60_000L;

    /**
     * When a statistics read of a pool last failed. CloudStack asks for each volume in turn,
     * without a timeout, and a stalled endpoint makes a read take about 90 s (three attempts):
     * without this, every volume of the pool would repeat that read in the same pass. For
     * {@link #STATS_RETRY_MILLIS} after a failure the volumes of the pool get their last sample
     * without a read; then reads start again, so collection recovers on its own.
     */
    static final class StatsBackoff {
        private final java.util.function.LongSupplier clock;
        private volatile long failedAt;
        private volatile boolean failed;

        StatsBackoff(java.util.function.LongSupplier clock) {
            this.clock = clock;
        }

        boolean active() {
            return failed && clock.getAsLong() - failedAt < STATS_RETRY_MILLIS;
        }

        void failed() {
            failedAt = clock.getAsLong();
            failed = true;
        }

        void succeeded() {
            failed = false;
        }
    }

    private static final Map<Long, StatsBackoff> STATS_BACKOFF = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Last sample of each volume, by Lightbits UUID. CloudStack asks for the volumes of one host
     * in one request and, at the first volume with no sample, drops the whole request
     * (StorageManagerImpl.getVolumeStats in 4.22 and 4.23 returns an answer with no statistics
     * at all). It keeps the previous sample of every volume it gets no new one for, so
     * answering with the last sample of a volume that has none this round is what CloudStack
     * would show anyway, and the other volumes of the request are updated. Entries go when the
     * volume is deleted or found gone.
     */
    private static final Map<String, Pair<Long, Long>> LAST_STATS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Pair of (bytes holding data, provisioned size) for a volume addressed by its Lightbits
     * UUID, which is what CloudStack stores as the volume's path. "Holding data" is the
     * logical figure, before compression and replication, capped at the size: it is what the
     * owner of the volume consumed, independent of how the cluster stores it.
     *
     * Returns null when the volume is not on the cluster or the cluster does not report its
     * usage, which CloudStack treats as "no statistics for this round"; a failure to reach the
     * cluster propagates. A made-up zero would be shown and metered as a real value.
     */
    static Pair<Long, Long> volumeStats(LightbitsApiClient client, String volumeId) {
        return volumeStats(client, volumeId, new HashMap<>(), new StatsBackoff(System::currentTimeMillis));
    }

    static Pair<Long, Long> volumeStats(LightbitsApiClient client, String volumeId, Map<String, Pair<Long, Long>> last,
                                        StatsBackoff backoff) {
        if (volumeId == null || !volumeId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            return null;   // not a volume this plugin created; never put a foreign value into a request path
        }
        if (backoff.active()) {
            Pair<Long, Long> previous = last.get(volumeId);
            if (previous == null) {
                throw new com.cloud.utils.exception.CloudRuntimeException("Lightbits: no statistics for volume " + volumeId
                        + ": a statistics read of its pool failed less than " + STATS_RETRY_MILLIS / 1000
                        + " s ago, so the cluster is not asked again yet");
            }
            LOG.debug("Lightbits: a statistics read of the pool failed less than {} s ago; reporting the last sample of"
                    + " volume {} without a read", STATS_RETRY_MILLIS / 1000, volumeId);
            return previous;
        }
        java.util.Optional<org.apache.cloudstack.storage.datastore.client.model.Volume> found;
        try {
            found = client.getVolume(volumeId);
            backoff.succeeded();
        } catch (RuntimeException e) {
            backoff.failed();
            Pair<Long, Long> previous = last.get(volumeId);
            if (previous == null) {
                throw e;
            }
            LOG.debug("Lightbits: no statistics for volume {} this round ({}); reporting its last sample", volumeId, e.getMessage());
            return previous;
        }
        if (found.isEmpty() || found.get().isDeletedOrDeleting()) {
            return missing(volumeId, last.remove(volumeId), "is not on the cluster or is being deleted");
        }
        long size = found.get().getSizeBytes();
        long used = found.get().getLogicalUsedBytes();
        if (used < 0) {
            return missing(volumeId, last.get(volumeId), "has no usage statistics on the cluster");
        }
        Pair<Long, Long> sample = new Pair<>(Math.min(used, size), size);
        last.put(volumeId, sample);
        return sample;
    }

    /** The answer for a volume with no sample this round: its last one, else nothing. */
    private static Pair<Long, Long> missing(String volumeId, Pair<Long, Long> previous, String why) {
        if (previous != null) {
            LOG.debug("Lightbits: volume {} {}; reporting its last sample", volumeId, why);
            return previous;
        }
        LOG.warn("Lightbits: volume {} {} and has no earlier sample. CloudStack drops the statistics of every volume in the"
                + " same request this round when one has none (StorageManagerImpl.getVolumeStats); they are updated in a"
                + " later round", volumeId, why);
        return null;
    }

    @Override
    public boolean requiresAccessForMigration(DataObject dataObject) {
        // Managed NVMe/TCP: the destination host's NQN must be in the Lightbits volume
        // ACL before the VM can open the device there. Returning true makes CloudStack
        // call grantAccess(volume, destHost, dataStore) during migration prepare, which
        // adds the destination NQN. revokeAccess later prunes the source NQN.
        return true;
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
