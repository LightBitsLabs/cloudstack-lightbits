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
package org.apache.cloudstack.storage.datastore.provider;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.HypervisorHostListener;
import org.apache.cloudstack.storage.datastore.command.LightbitsGetHostNqnAnswer;
import org.apache.cloudstack.storage.datastore.command.LightbitsGetHostNqnCommand;
import org.apache.cloudstack.storage.datastore.driver.LightbitsPrimaryDataStoreDriver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ModifyStoragePoolCommand;
import com.cloud.agent.AgentManager;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Host listener for the Lightbits managed primary storage pool.
 *
 * On host connect it sends a ModifyStoragePoolCommand so the KVM agent registers the pool
 * with its LightbitsStorageAdaptor (needed so per-volume connectPhysicalDisk can later find
 * the pool). It also records the host<->pool association. There is no host-side mount:
 * individual volumes connect over NVMe/TCP at attach time (driver grantAccess + adaptor).
 *
 * A non-null listener is REQUIRED: StoragePoolMonitor calls
 * provider.getHostListener().hostConnect(...) for every host that connects while the pool
 * exists; returning null NPEs and wedges the host in Alert.
 */
public class LightbitsHostListener implements HypervisorHostListener {
    private static final Logger LOG = LogManager.getLogger(LightbitsHostListener.class);

    @Inject
    private AgentManager agentMgr;
    @Inject
    private DataStoreManager dataStoreMgr;
    @Inject
    private HostDao hostDao;
    @Inject
    private HostDetailsDao hostDetailsDao;
    @Inject
    private StoragePoolHostDao storagePoolHostDao;
    @Inject
    private org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao primaryDataStoreDao;
    @Inject
    private org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao storagePoolDetailsDao;

    @Override
    public boolean hostAdded(long hostId) {
        return true;
    }

    @Override
    public boolean hostConnect(long hostId, long poolId) {
        LOG.info("Lightbits hostConnect: host={} pool={}", hostId, poolId);
        StoragePool pool = (StoragePool) dataStoreMgr.getDataStore(poolId, DataStoreRole.Primary);

        StoragePoolHostVO association = storagePoolHostDao.findByPoolHost(poolId, hostId);
        if (association == null) {
            storagePoolHostDao.persist(new StoragePoolHostVO(poolId, hostId, ""));
        }

        // Register the pool on the KVM agent's LightbitsStorageAdaptor so per-volume
        // connectPhysicalDisk can resolve it. Managed, per-volume NVMe/TCP: no pool mount.
        // The agent gets the cluster's NVMe discovery endpoints with the pool, and nothing
        // else: no credentials ever travel to a hypervisor.
        ModifyStoragePoolCommand cmd = new ModifyStoragePoolCommand(true, pool, discoveryDetails(poolId));
        Answer answer = agentMgr.easySend(hostId, cmd);
        if (answer == null || !answer.getResult()) {
            String detail = answer == null ? "no answer" : answer.getDetails();
            // Tolerate per-pool registration failure instead of aborting the whole host
            // connect. Throwing here propagates through StoragePoolMonitor.processConnect
            // and sends the ENTIRE host to Alert, taking down other pools that registered
            // fine (e.g. one unreachable/misconfigured pool bricks the host for all pools).
            // Mirror the tolerance LifeCycle.attachCluster and ensureHostNqnDetail already
            // use: log and continue. Pool registration is retried on the next host connect,
            // and volumes still attach per-volume via the driver + adaptor at VM start.
            LOG.warn("Lightbits: failed to register pool {} on host {}: {} - continuing so the"
                    + " host stays usable; pool registration will be retried on next connect",
                    poolId, hostId, detail);
        } else {
            LOG.info("Lightbits pool {} registered on host {}", poolId, hostId);
        }

        ensureHostDetails(hostId, poolId);
        clearRemovingMark(hostId);
        return true;
    }

    private java.util.Map<String, String> discoveryDetails(long poolId) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        out.put(com.cloud.hypervisor.kvm.storage.LightbitsStorageAdaptor.DETAIL_PLUGIN_VERSION,
                org.apache.cloudstack.storage.datastore.util.LightbitsVersion.version());
        try {
            org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig cfg =
                    org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig.fromDetails(
                            storagePoolDetailsDao.listDetailsKeyPairs(poolId), null);
            out.put(com.cloud.hypervisor.kvm.storage.LightbitsStorageAdaptor.DETAIL_NVME_DISCOVERY,
                    String.join(",", cfg.discoveryEndpoints()));
        } catch (RuntimeException e) {
            // the agent falls back to the pool's host address on the discovery port
            LOG.warn("Lightbits: could not derive the NVMe discovery endpoints of pool {} ({}); the host will use"
                    + " the pool's API host only", poolId, e.getMessage());
        }
        return out;
    }

    /**
     * Auto-populate host_details['nvme.hostnqn'] (I10): ask the agent for /etc/nvme/hostnqn
     * and persist it, so the driver's grantAccess works with no manual step. A failure is
     * logged but does not fail hostConnect — the pool is registered and usable; only a later
     * volume attach on THIS host would fail, with a pointed error from the driver.
     *
     * With the pool option ipAcl the agent is asked on every connect, also for its data IPs
     * toward the pool's cluster (see {@link #recordDataIps}).
     */
    private void ensureHostDetails(long hostId, long poolId) {
        String key = LightbitsPrimaryDataStoreDriver.HOST_NQN_DETAIL;
        DetailVO existing = hostDetailsDao.findDetail(hostId, key);
        boolean hasNqn = existing != null && existing.getValue() != null && !existing.getValue().isBlank();
        org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig cfg = poolConfigOrNull(poolId);
        boolean wantIps = cfg != null && cfg.isIpAcl();
        if (hasNqn && !wantIps) {
            LOG.debug("Lightbits: host {} already has '{}' = {}", hostId, key, existing.getValue());
            return;
        }
        Answer answer = agentMgr.easySend(hostId, wantIps
                ? new LightbitsGetHostNqnCommand(cfg.discoveryEndpoints()) : new LightbitsGetHostNqnCommand());
        if (answer instanceof LightbitsGetHostNqnAnswer && answer.getResult()
                && ((LightbitsGetHostNqnAnswer) answer).getHostNqn() != null) {
            String nqn = ((LightbitsGetHostNqnAnswer) answer).getHostNqn();
            if (!hasNqn) {
                hostDetailsDao.persist(new DetailVO(hostId, key, nqn));
                LOG.info("Lightbits: host {} '{}' auto-populated: {}", hostId, key, nqn);
            }
            if (wantIps) {
                recordDataIps(hostId, poolId, hasNqn ? existing.getValue() : nqn, ((LightbitsGetHostNqnAnswer) answer).getDataIps());
            }
        } else {
            String detail = answer == null ? "no answer" : answer.getDetails();
            if (hasNqn) {
                LOG.warn("Lightbits: could not read the data IPs of host {} for pool {} ({}); its volumes keep the IP"
                        + " ACLs they have", hostId, poolId, detail);
            } else {
                LOG.warn("Lightbits: could not auto-populate '{}' for host {} ({}); volume attach on this host "
                        + "will fail until it is set - check nvme-cli on the host, then reconnect it", key, hostId, detail);
            }
        }
    }

    private org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig poolConfigOrNull(long poolId) {
        try {
            return org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig.fromDetails(
                    storagePoolDetailsDao.listDetailsKeyPairs(poolId), null);
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: could not read the options of pool {} ({})", poolId, e.getMessage());
            return null;
        }
    }

    /**
     * Keep the host's data IPs for the pool (host detail lightbits.dataips.<pool id>) and,
     * when they are new or changed, bring the IP ACLs of the pool's volumes this host may
     * use in line. An agent older than this plugin version reports none: the old detail is
     * kept and the host cannot be granted access to the pool's volumes until it is upgraded.
     */
    private void recordDataIps(long hostId, long poolId, String nqn, java.util.List<String> dataIps) {
        String key = org.apache.cloudstack.storage.datastore.util.LightbitsHostIps.detailKey(poolId);
        if (dataIps == null) {
            LOG.warn("Lightbits: the agent of host {} does not report its data IPs (older than this plugin version);"
                    + " pool {} has ipAcl on, so the host gets no access to its volumes until the agent is upgraded", hostId, poolId);
            return;
        }
        if (dataIps.isEmpty()) {
            LOG.warn("Lightbits: host {} found no route toward the NVMe endpoints of pool {}; keeping its data IPs as they"
                    + " were", hostId, poolId);
            return;
        }
        if (dataIps.size() > 1) {
            LOG.warn("Lightbits: host {} reaches the cluster of pool {} from several addresses {}; all of them are put in"
                    + " the IP ACLs of its volumes", hostId, poolId, dataIps);
        }
        String value = String.join(",", dataIps);
        DetailVO current = hostDetailsDao.findDetail(hostId, key);
        if (current != null && value.equals(current.getValue())) {
            return;
        }
        if (current == null) {
            hostDetailsDao.persist(new DetailVO(hostId, key, value));
        } else {
            current.setValue(value);
            hostDetailsDao.update(current.getId(), current);
        }
        LOG.info("Lightbits: host {} data IPs for pool {}: {} (were {})", hostId, poolId, value,
                current == null ? "unknown" : current.getValue());
        try {
            org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig cfg =
                    org.apache.cloudstack.storage.datastore.util.LightbitsPoolSecret.load(storagePoolDetailsDao, poolId);
            java.util.List<String> updated = org.apache.cloudstack.storage.datastore.util.LightbitsAcl.refreshIps(cfg.newClient(),
                    cfg.getEnvironment(), poolId, nqn, ipResolver(poolId));
            if (!updated.isEmpty()) {
                LOG.info("Lightbits: updated the IP ACL of {} volume(s) of pool {} for host {}: {}", updated.size(), poolId,
                        hostId, updated);
            }
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: could not update the IP ACLs of the volumes of pool {} for host {}: {}; they are set at"
                    + " the next attach", poolId, hostId, e.getMessage());
        }
    }

    private org.apache.cloudstack.storage.datastore.util.LightbitsAcl.IpResolver ipResolver(long poolId) {
        return org.apache.cloudstack.storage.datastore.util.LightbitsHostIps.resolver(hostDetailsDao,
                LightbitsPrimaryDataStoreDriver.HOST_NQN_DETAIL, poolId);
    }

    /** A host that connects is not being removed (a removal that did not go through). */
    private void clearRemovingMark(long hostId) {
        DetailVO mark = hostDetailsDao.findDetail(hostId, org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.REMOVING_DETAIL);
        if (mark != null) {
            hostDetailsDao.remove(mark.getId());
            LOG.info("Lightbits: host {} connected again; cleared its removal mark", hostId);
        }
    }

    @Override
    public boolean hostDisconnected(long hostId, long poolId) {
        StoragePoolHostVO association = storagePoolHostDao.findByPoolHost(poolId, hostId);
        if (association != null) {
            storagePoolHostDao.deleteStoragePoolHostDetails(hostId, poolId);
        }
        return true;
    }

    @Override
    public boolean hostAboutToBeRemoved(long hostId) {
        // Runs while the host's details still exist. Take the host out of the ACL of every
        // volume it was granted: once CloudStack forgets the host nothing else would, and the
        // machine could keep writing to a volume that is started elsewhere later.
        try {
            com.cloud.host.DetailVO nqn = hostDetailsDao.findDetail(hostId,
                    org.apache.cloudstack.storage.datastore.driver.LightbitsPrimaryDataStoreDriver.HOST_NQN_DETAIL);
            if (nqn == null || nqn.getValue() == null || nqn.getValue().isBlank()) {
                return true;
            }
            // Mark the host and sweep under its ACL lock, so a grant that overlaps the sweep
            // either finishes first (and is swept) or sees the mark and writes nothing.
            boolean locked = org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.removal(hostId,
                    () -> markRemoving(hostId), hostGateLocker, () -> sweepHostFromAcls(hostId, nqn.getValue()));
            if (!locked) {
                LOG.warn("Lightbits: host {} is being removed but its ACL lock was busy for {} s; swept its ACL entries"
                        + " without it (a grant still running for it takes its own entry out again)", hostId,
                        org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.REMOVAL_LOCK_SECONDS);
            }
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: ACL cleanup for host {} that is being removed failed: {}", hostId, e.getMessage());
        }
        return true;
    }

    /** The lock grants and host removal share; tests swap it for an in-process one. */
    org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.Locker hostGateLocker =
            org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.GLOBAL;

    private void markRemoving(long hostId) {
        if (hostDetailsDao.findDetail(hostId, org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.REMOVING_DETAIL) == null) {
            hostDetailsDao.persist(new DetailVO(hostId, org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.REMOVING_DETAIL, "true"));
        }
    }

    private void sweepHostFromAcls(long hostId, String nqnValue) {
        for (org.apache.cloudstack.storage.datastore.db.StoragePoolVO pool : primaryDataStoreDao.findPoolsByProvider(
                org.apache.cloudstack.storage.datastore.util.LightbitsUtil.PROVIDER_NAME)) {
            try {
                org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig cfg =
                        org.apache.cloudstack.storage.datastore.util.LightbitsPoolSecret.load(storagePoolDetailsDao, pool.getId());
                java.util.List<String> pruned = org.apache.cloudstack.storage.datastore.util.LightbitsAcl.pruneHost(cfg.newClient(),
                        cfg.getEnvironment(), pool.getId(), nqnValue, cfg.isIpAcl() ? ipResolver(pool.getId()) : null);
                if (!pruned.isEmpty()) {
                    LOG.warn("Lightbits: host {} is being removed and still had access to {} volume(s) of pool {}: removed it"
                            + " from their ACLs {}", hostId, pruned.size(), pool.getName(), pruned);
                }
            } catch (RuntimeException e) {
                LOG.warn("Lightbits: could not check the volumes of pool {} for ACL entries of host {} that is being"
                        + " removed: {}", pool.getName(), hostId, e.getMessage());
            }
        }
    }

    @Override
    public boolean hostRemoved(long hostId, long clusterId) {
        return true;
    }

    @Override
    public boolean hostEnabled(long hostId) {
        return true;
    }
}
