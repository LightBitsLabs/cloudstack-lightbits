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
        org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig cfg = poolConfigOrNull(poolId);
        if (cfg == null || !cfg.isIpAcl()) {
            ensureHostDetails(hostId, poolId, cfg, false);
            return;
        }
        // The agent's data IP report and what is done with it (stored IPs and IP ACLs) run under
        // the host's report lock, held across management servers: of two overlapping connects of
        // one host, the one that asked last also commits last, so an older report cannot overwrite
        // a newer one. Without the lock in time this connect only records the host NQN, and the
        // next connect reports again.
        if (!hostGateLocker.run(reportLockName(hostId), REPORT_LOCK_SECONDS, () -> ensureHostDetails(hostId, poolId, cfg, true))) {
            LOG.warn("Lightbits: the report lock of host {} was busy for {} s; its data IPs for pool {} are recorded at its"
                    + " next connect", hostId, REPORT_LOCK_SECONDS, poolId);
            ensureHostDetails(hostId, poolId, cfg, false);
        }
    }

    static final int REPORT_LOCK_SECONDS = 120;

    static String reportLockName(long hostId) {
        return "lightbits.host.report." + hostId;
    }

    private void ensureHostDetails(long hostId, long poolId, org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig cfg,
                                   boolean wantIps) {
        String key = LightbitsPrimaryDataStoreDriver.HOST_NQN_DETAIL;
        DetailVO existing = hostDetailsDao.findDetail(hostId, key);
        boolean hasNqn = existing != null && existing.getValue() != null && !existing.getValue().isBlank();
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
                recordDataIps(hostId, poolId, hasNqn ? existing.getValue() : nqn, ((LightbitsGetHostNqnAnswer) answer).getDataIps(),
                        ((LightbitsGetHostNqnAnswer) answer).isDataIpsPartial());
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
     * Keep the host's data IPs for the pool (host detail lightbits.dataips.<pool id>) and bring
     * the IP ACLs of the pool's volumes this host is in into line. The refresh runs on every
     * connect, not only when the IPs change, so a volume an earlier refresh could not update is
     * retried. A partial report (the agent could not look up every endpoint) adds its addresses
     * to the stored ones and drops none. An agent older than this plugin version reports none:
     * the old detail is kept and the host cannot be granted access to the pool's volumes until
     * it is upgraded.
     *
     * <p>Storing the IPs and the refresh run under the host's ACL lock, then the pool's IP ACL
     * lock, which every IP ACL writer of the pool holds (grants, revokes, host removal; see
     * LightbitsHostGate). So no other writer can resolve this host's old IPs and write them
     * after the refresh, and two refreshes of the pool cannot interleave. Without the locks in
     * time nothing changes and the next connect tries again.
     */
    private void recordDataIps(long hostId, long poolId, String nqn, java.util.List<String> dataIps, boolean partial) {
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
        // Lock order as everywhere: the host's ACL lock, then the pool's IP ACL lock (LightbitsHostGate).
        boolean had = hostGateLocker.run(org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.lockName(hostId),
                IP_ACL_LOCK_SECONDS, () -> {
            boolean poolLock = hostGateLocker.run(ipAclLockName(poolId), IP_ACL_LOCK_SECONDS,
                    () -> storeAndRefresh(hostId, poolId, nqn, dataIps, partial));
            if (!poolLock) {
                LOG.warn("Lightbits: the IP ACL lock of pool {} was busy for {} s; the data IPs of host {} are recorded at its"
                        + " next connect", poolId, IP_ACL_LOCK_SECONDS, hostId);
            }
        });
        if (!had) {
            LOG.warn("Lightbits: the ACL lock of host {} was busy for {} s; its data IPs for pool {} are recorded at its"
                    + " next connect", hostId, IP_ACL_LOCK_SECONDS, poolId);
        }
    }

    static final int IP_ACL_LOCK_SECONDS = 120;

    static String ipAclLockName(long poolId) {
        return org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.poolIpAclLockName(poolId);
    }

    private void storeAndRefresh(long hostId, long poolId, String nqn, java.util.List<String> reported, boolean partial) {
        String key = org.apache.cloudstack.storage.datastore.util.LightbitsHostIps.detailKey(poolId);
        DetailVO current = hostDetailsDao.findDetail(hostId, key);
        java.util.List<String> ips = org.apache.cloudstack.storage.datastore.util.LightbitsHostIps.merge(
                current == null ? null : current.getValue(), reported, partial);
        if (partial) {
            LOG.warn("Lightbits: host {} could not look up every NVMe endpoint of pool {}; added {} to its data IPs and"
                    + " kept the ones it had", hostId, poolId, reported);
        }
        if (ips.size() > 1) {
            LOG.warn("Lightbits: host {} reaches the cluster of pool {} from several addresses {}; all of them are put in"
                    + " the IP ACLs of its volumes", hostId, poolId, ips);
        }
        String value = String.join(",", ips);
        if (current == null) {
            hostDetailsDao.persist(new DetailVO(hostId, key, value));
        } else if (!value.equals(current.getValue())) {
            current.setValue(value);
            hostDetailsDao.update(current.getId(), current);
        }
        if (current == null || !value.equals(current.getValue())) {
            LOG.info("Lightbits: host {} data IPs for pool {}: {} (were {})", hostId, poolId, value,
                    current == null ? "unknown" : current.getValue());
        }
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
            LOG.warn("Lightbits: could not update the IP ACLs of the volumes of pool {} for host {}: {}; retried at the"
                    + " host's next connect and set at the next attach", poolId, hostId, e.getMessage());
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
                java.util.List<String> pruned = new java.util.ArrayList<>();
                Runnable prune = () -> pruned.addAll(org.apache.cloudstack.storage.datastore.util.LightbitsAcl.pruneHost(
                        cfg.newClient(), cfg.getEnvironment(), pool.getId(), nqnValue, cfg.isIpAcl() ? ipResolver(pool.getId()) : null));
                // With ipAcl, under the pool's IP ACL lock (inside the host's lock, as everywhere). The
                // removal must not be held up: without the lock in time it removes the NQN only and
                // leaves the IP ACLs as they are, since IPs resolved outside the lock may be stale. The
                // removed host's address then stays until the next locked write of that volume's IP ACL.
                if (cfg.isIpAcl() && !hostGateLocker.run(ipAclLockName(pool.getId()), IP_ACL_LOCK_SECONDS, prune)) {
                    LOG.warn("Lightbits: the IP ACL lock of pool {} was busy for {} s while host {} is being removed; removing"
                            + " its NQN only, the volumes' IP ACLs keep its address until their next attach, migration or"
                            + " host connect", pool.getName(), IP_ACL_LOCK_SECONDS, hostId);
                    pruned.addAll(org.apache.cloudstack.storage.datastore.util.LightbitsAcl.pruneHost(
                            cfg.newClient(), cfg.getEnvironment(), pool.getId(), nqnValue, null));
                } else if (!cfg.isIpAcl()) {
                    prune.run();
                }
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
