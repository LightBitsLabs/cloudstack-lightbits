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
package org.apache.cloudstack.storage.datastore.lifecycle;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.ClusterScope;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.HostScope;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreParameters;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.storage.datastore.client.LightbitsApiClient;
import org.apache.cloudstack.storage.datastore.client.model.ClusterInfo;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig;
import org.apache.cloudstack.storage.datastore.util.LightbitsUtil;
import org.apache.cloudstack.storage.volume.datastore.PrimaryDataStoreHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.StoragePoolInfo;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.StoragePool;
import com.cloud.utils.crypt.DBEncryptionUtil;

public class LightbitsPrimaryDataStoreLifeCycle implements PrimaryDataStoreLifeCycle {

    private static final Logger LOG = LogManager.getLogger(LightbitsPrimaryDataStoreLifeCycle.class);

    @Inject
    PrimaryDataStoreHelper dataStoreHelper;
    @Inject
    com.cloud.storage.StorageManager storageMgr;
    @Inject
    com.cloud.resource.ResourceManager resourceMgr;
    @Inject
    StoragePoolDetailsDao storagePoolDetailsDao;
    @Inject
    com.cloud.storage.dao.VMTemplatePoolDao templatePoolDao;

    /**
     * Parse + validate pool params and preflight the cluster (auth, project, health,
     * capacity) BEFORE any pool row is created — a bad endpoint/JWT fails fast here
     * rather than leaving a half-registered pool. Returns the details map (JWT
     * excluded; the caller encrypts it into storage_pool_details separately) and
     * cluster-derived capacity via {@link PreflightResult}.
     *
     * Note: the actual DataStore creation goes through PrimaryDataStoreHelper, which
     * needs the Spring-wired managed context, so it is exercised by the live/Marvin
     * tests rather than unit tests. This method isolates the unit-testable logic.
     */
    public PreflightResult preflight(Map<String, Object> dsInfos) {
        String url = (String) dsInfos.get("url");
        @SuppressWarnings("unchecked")
        Map<String, String> details = (Map<String, String>) dsInfos.get("details");
        String jwt = details == null ? null : details.get(LightbitsPoolConfig.KEY_JWT);

        LightbitsPoolConfig cfg = LightbitsPoolConfig.fromUrl(url, jwt);

        LightbitsApiClient client = clientFactory.create(cfg);
        ClusterInfo info;
        try {
            info = client.getClusterInfo();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cannot reach Lightbits cluster at " + cfg.apiEndpoint()
                    + " with the supplied credentials: " + e.getMessage());
        }
        if (!info.isHealthy()) {
            throw new IllegalArgumentException("Lightbits cluster is not healthy (state="
                    + (info.getHealth() == null ? "unknown" : info.getHealth().getState()) + ")");
        }
        // A volume cannot have more replicas than there are nodes. Fail fast at registration
        // (with actionable guidance) instead of at the first volume create. Any cluster size is
        // supported — e.g. a single-node cluster works with replicas=1. Tolerant: if the node
        // count can't be determined, skip the check rather than block registration.
        int nodes = 0;
        try {
            nodes = client.countNodes();
        } catch (RuntimeException ignore) {
            LOG.debug("Lightbits: could not list cluster nodes for replica validation; skipping");
        }
        if (nodes > 0 && cfg.getReplicaCount() > nodes) {
            throw new IllegalArgumentException("replicas=" + cfg.getReplicaCount() + " but the Lightbits cluster"
                    + " has only " + nodes + " node" + (nodes == 1 ? "" : "s")
                    + "; set replicas=1.." + nodes + " in the pool URL (single-node clusters use replicas=1)");
        }
        long capacity = info.getStatistics() == null ? 0L : info.getStatistics().effectiveBytes();

        Map<String, String> persisted = new HashMap<>(cfg.toDetailsWithoutSecret());
        return new PreflightResult(cfg, persisted, capacity);
    }

    /** Result of {@link #preflight}: validated config, details to persist, capacity from the cluster. */
    public static class PreflightResult {
        public final LightbitsPoolConfig config;
        public final Map<String, String> detailsWithoutSecret;
        public final long capacityBytes;

        public PreflightResult(LightbitsPoolConfig config, Map<String, String> detailsWithoutSecret, long capacityBytes) {
            this.config = config;
            this.detailsWithoutSecret = detailsWithoutSecret;
            this.capacityBytes = capacityBytes;
        }
    }

    /** Seam so preflight() is unit-testable with a mock client. */
    public interface ClientFactory {
        LightbitsApiClient create(LightbitsPoolConfig cfg);
    }

    private ClientFactory clientFactory = cfg ->
            LightbitsApiClient.insecureForDevelopment(cfg.apiEndpoint(), cfg.getJwt(), cfg.getProject());

    public void setClientFactory(ClientFactory factory) {
        this.clientFactory = factory;
    }

    @Override
    public DataStore initialize(Map<String, Object> dsInfos) {
        PreflightResult pf = preflight(dsInfos);

        String name = (String) dsInfos.get("name");
        Long zoneId = (Long) dsInfos.get("zoneId");
        Long podId = (Long) dsInfos.get("podId");
        Long clusterId = (Long) dsInfos.get("clusterId");

        // Persist config as pool details; the JWT is encrypted at rest (never stored cleartext)
        // and chunked because it exceeds the varchar(255) detail-value column.
        Map<String, String> details = new HashMap<>(pf.detailsWithoutSecret);
        LightbitsPoolConfig.putChunked(details, LightbitsPoolConfig.KEY_JWT, DBEncryptionUtil.encrypt(pf.config.getJwt()));

        PrimaryDataStoreParameters params = new PrimaryDataStoreParameters();
        params.setType(LightbitsUtil.POOL_TYPE);   // v1: IscsiLUN (see LightbitsUtil / decision log Q3)
        params.setUuid(UUID.randomUUID().toString());
        // host/port/path back the required storage_pool columns (host_address, path);
        // the API endpoint identifies the cluster, project is the path segment.
        params.setHost(pf.config.getApiHost());
        params.setPort(pf.config.getApiPort());
        params.setPath("/" + pf.config.getProject());
        params.setName(name);
        params.setProviderName((String) dsInfos.get("providerName"));
        params.setManaged(true);
        params.setCapacityBytes(pf.capacityBytes);
        params.setUsedBytes(0);
        params.setHypervisorType(HypervisorType.KVM);
        params.setZoneId(zoneId);
        params.setPodId(podId);
        params.setClusterId(clusterId);
        params.setDetails(details);
        params.setTags((String) dsInfos.get("tags"));

        return dataStoreHelper.createPrimaryDataStore(params);
    }

    // Connect each eligible KVM host to the pool: this sends ModifyStoragePoolCommand,
    // which registers the pool with the host's LightbitsStorageAdaptor so per-volume
    // connectPhysicalDisk can resolve it at attach time. (No pool-wide mount; managed,
    // per-volume NVMe/TCP.)

    @Override
    public boolean attachCluster(DataStore store, ClusterScope scope) {
        org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo primary =
                (org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo) store;
        for (com.cloud.host.HostVO host :
                resourceMgr.getEligibleUpAndEnabledHostsInClusterForStorageConnection(primary)) {
            try {
                storageMgr.connectHostToSharedPool(host, store.getId());
            } catch (Exception e) {
                // tolerate per-host failure; the host reconnects the pool when it comes up
            }
        }
        dataStoreHelper.attachCluster(store);
        return true;
    }

    @Override
    public boolean attachHost(DataStore store, HostScope scope, StoragePoolInfo existingInfo) {
        return true;
    }

    @Override
    public boolean attachZone(DataStore dataStore, ZoneScope scope, HypervisorType hypervisorType) {
        for (com.cloud.host.HostVO host :
                resourceMgr.getEligibleUpAndEnabledHostsInZoneForStorageConnection(dataStore, scope.getScopeId(), hypervisorType)) {
            try {
                storageMgr.connectHostToSharedPool(host, dataStore.getId());
            } catch (Exception e) {
                // tolerate per-host failure
            }
        }
        dataStoreHelper.attachZone(dataStore, hypervisorType);
        return true;
    }

    @Override
    public boolean maintain(DataStore store) {
        return dataStoreHelper.maintain(store);
    }

    @Override
    public boolean cancelMaintain(DataStore store) {
        return dataStoreHelper.cancelMaintain(store);
    }

    @Override
    public boolean deleteDataStore(DataStore store) {
        cleanupClusterObjects(store);
        return dataStoreHelper.deletePrimaryDataStore(store);
    }

    /**
     * Remove everything the plugin created on the Lightbits cluster for this pool before the
     * pool row goes away: cached template volumes (template_spool_ref) and any leftover
     * environment-prefixed volumes/snapshots (e.g. clone-source snapshots whose deletion was
     * deferred because the clone still existed). Guest volumes are already gone — CloudStack
     * refuses to delete a pool that still has volumes.
     *
     * The sweep is scoped to this pool's project AND its {@code cs-<environment>-} name
     * prefix; the environment is required to be unique per pool, so objects of other pools /
     * CloudStack deployments sharing the cluster are never touched.
     *
     * Best-effort by design: an unreachable cluster must not wedge pool removal. Failures are
     * logged with the objects left behind.
     */
    private void cleanupClusterObjects(DataStore store) {
        try {
            Map<String, String> details = storagePoolDetailsDao.listDetailsKeyPairs(store.getId());
            String jwt = DBEncryptionUtil.decrypt(LightbitsPoolConfig.readChunked(details, LightbitsPoolConfig.KEY_JWT));
            LightbitsPoolConfig cfg = LightbitsPoolConfig.fromDetails(details, jwt);
            LightbitsApiClient client = clientFactory.create(cfg);

            for (com.cloud.storage.VMTemplateStoragePoolVO ref : templatePoolDao.listByPoolId(store.getId())) {
                if (ref.getInstallPath() != null && !ref.getInstallPath().isBlank()) {
                    client.deleteVolume(ref.getInstallPath());
                    LOG.info("Lightbits pool {} delete: removed cached template volume {}", store.getId(), ref.getInstallPath());
                }
                templatePoolDao.remove(ref.getId());
            }

            String prefix = LightbitsUtil.volumeName(cfg.getEnvironment(), "");
            for (org.apache.cloudstack.storage.datastore.client.model.Snapshot s : client.listSnapshots()) {
                if (s.getName() != null && s.getName().startsWith(prefix)) {
                    client.deleteSnapshot(s.getUuid());
                    LOG.info("Lightbits pool {} delete: swept snapshot {}", store.getId(), s.getName());
                }
            }
            for (org.apache.cloudstack.storage.datastore.client.model.Volume v : client.listVolumes()) {
                if (v.getName() != null && v.getName().startsWith(prefix)) {
                    client.deleteVolume(v.getUuid());
                    LOG.info("Lightbits pool {} delete: swept volume {}", store.getId(), v.getName());
                }
            }
        } catch (Exception e) {
            LOG.warn("Lightbits pool {} delete: cluster-side cleanup incomplete ({}); "
                    + "check the cluster for leftover cs-<environment>-* objects", store.getId(), e.getMessage());
        }
    }

    @Override
    public boolean migrateToObjectStore(DataStore store) {
        return false;
    }

    @Override
    public void updateStoragePool(StoragePool storagePool, Map<String, String> details) {
        // capacity is refreshed from the cluster via the driver's stats; nothing to do here yet
    }

    @Override
    public void enableStoragePool(DataStore store) {
        dataStoreHelper.enable(store);
    }

    @Override
    public void disableStoragePool(DataStore store) {
        dataStoreHelper.disable(store);
    }

    @Override
    public void changeStoragePoolScopeToZone(DataStore store, ClusterScope clusterScope, Hypervisor.HypervisorType hypervisorType) {
        throw new UnsupportedOperationException("Lightbits plugin: scope change not supported yet");
    }

    @Override
    public void changeStoragePoolScopeToCluster(DataStore store, ClusterScope clusterScope, Hypervisor.HypervisorType hypervisorType) {
        throw new UnsupportedOperationException("Lightbits plugin: scope change not supported yet");
    }
}
