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
import org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig;
import org.apache.cloudstack.storage.datastore.util.LightbitsPoolSecret;
import org.apache.cloudstack.storage.datastore.util.LightbitsTrustedHosts;
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

        // An expired mark (a removal that did not go through) is cleared before registration, so
        // the post-registration removal check does not undo this connect's registration; a mark of
        // an active removal is kept and that check still applies.
        clearRemovingMark(hostId);
        ensureHostDetails(hostId, poolId);
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
            if (cfg.isInbandAuth()) {
                out.put(com.cloud.hypervisor.kvm.storage.LightbitsStorageAdaptor.DETAIL_INBAND_AUTH, "true");
            }
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
        boolean wantIps = cfg != null && cfg.isIpAcl();
        boolean wantAuth = cfg != null && cfg.isInbandAuth();
        if (!wantIps && !wantAuth) {
            ensureHostDetails(hostId, poolId, cfg, false, false);
            return;
        }
        // The agent's report (data IPs, key) and what is done with it (stored IPs and IP ACLs,
        // trusted-host registration) run under the host's report lock, held across management
        // servers: of two overlapping connects of one host, the one that asked last also commits
        // last, so an older report cannot overwrite a newer one. Without the lock in time this
        // connect only records the host NQN, and the next connect reports again.
        if (!hostGateLocker.run(reportLockName(hostId), REPORT_LOCK_SECONDS,
                () -> ensureHostDetails(hostId, poolId, cfg, wantIps, wantAuth))) {
            LOG.warn("Lightbits: the report lock of host {} was busy for {} s; its {} for pool {} are recorded at its next"
                    + " connect", hostId, REPORT_LOCK_SECONDS,
                    wantIps && wantAuth ? "data IPs and in-band key" : wantIps ? "data IPs" : "in-band key", poolId);
            ensureHostDetails(hostId, poolId, cfg, false, false);
        }
    }

    static final int REPORT_LOCK_SECONDS = 120;

    static String reportLockName(long hostId) {
        return "lightbits.host.report." + hostId;
    }

    private void ensureHostDetails(long hostId, long poolId, org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig cfg,
                                   boolean wantIps, boolean wantAuth) {
        String key = LightbitsPrimaryDataStoreDriver.HOST_NQN_DETAIL;
        DetailVO existing = hostDetailsDao.findDetail(hostId, key);
        boolean hasNqn = existing != null && existing.getValue() != null && !existing.getValue().isBlank();
        if (hasNqn && !wantIps && !wantAuth) {
            LOG.debug("Lightbits: host {} already has '{}' = {}", hostId, key, existing.getValue());
            return;
        }
        Answer answer = agentMgr.easySend(hostId, new LightbitsGetHostNqnCommand(wantIps ? cfg.discoveryEndpoints() : null, wantAuth));
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
            if (wantAuth) {
                if (hasNqn && !existing.getValue().trim().equals(nqn.trim())) {
                    refuseMismatchedNqn(hostId, poolId, existing.getValue(), nqn);
                } else {
                    registerTrustedHost(hostId, poolId, hasNqn ? existing.getValue() : nqn, (LightbitsGetHostNqnAnswer) answer);
                }
            }
        } else {
            String detail = answer == null ? "no answer" : answer.getDetails();
            if (hasNqn) {
                LOG.warn("Lightbits: could not ask host {} for its {} for pool {} ({}); {}", hostId,
                        wantIps && wantAuth ? "data IPs and in-band authentication key" : wantIps ? "data IPs" : "in-band authentication key",
                        poolId, detail, wantAuth ? "its trusted host registration is checked again at its next connect"
                                : "its volumes keep the IP ACLs they have");
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
                    cfg.getEnvironment(), poolId, nqn, ipResolver(poolId), ips);
            if (!updated.isEmpty()) {
                LOG.info("Lightbits: updated the IP ACL of {} volume(s) of pool {} for host {}: {}", updated.size(), poolId,
                        hostId, updated);
            }
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: could not update the IP ACLs of the volumes of pool {} for host {}: {}; retried at the"
                    + " host's next connect and set at the next attach", poolId, hostId, e.getMessage());
        }
    }

    /**
     * Register the host's in-band authentication key as a trusted host in the pool's project
     * and keep its fingerprint (host detail lightbits.inband.<pool id>) and the agent's count of
     * paths without its current key, then log whether every host of the pool is ready for the cluster admin to
     * turn in-band authentication on. Failures are logged and retried at the next connect.
     */
    private void registerTrustedHost(long hostId, long poolId, String nqn, LightbitsGetHostNqnAnswer answer) {
        String key = answer.getDhchapKey();
        if (key == null) {
            if (answer.getInbandAuthError() != null) {
                LOG.warn("Lightbits: host {} could not prepare its in-band authentication key for pool {}: {}", hostId, poolId,
                        answer.getInbandAuthError());
            } else {
                LOG.warn("Lightbits: the agent of host {} does not support in-band authentication (older than this plugin"
                        + " version); pool {} has inbandAuth on, so upgrade the agent before the cluster admin turns"
                        + " in-band authentication on", hostId, poolId);
            }
            // No key now: an earlier registration no longer says this host is ready, and its
            // count of keyless paths is unknown until the agent reports one again.
            setHostDetail(hostId, LightbitsTrustedHosts.detailKey(poolId), null);
            setHostDetail(hostId, LightbitsTrustedHosts.keyOptionalDetailKey(poolId), null);
            setHostDetail(hostId, LightbitsTrustedHosts.KEYLESS_DETAIL, null);
            setHostDetail(hostId, LightbitsTrustedHosts.CURRENT_KEY_DETAIL, null);
            reportInbandReadiness(poolId);
            return;
        }
        setHostDetail(hostId, LightbitsTrustedHosts.KEYLESS_DETAIL,
                answer.getKeylessPaths() == null ? null : String.valueOf(answer.getKeylessPaths()));
        String fp = LightbitsTrustedHosts.fingerprint(key);
        // host-wide: a pool whose registration holds another fingerprint no longer counts this host as ready
        setHostDetail(hostId, LightbitsTrustedHosts.CURRENT_KEY_DETAIL, fp);
        try {
            LightbitsPoolConfig cfg = LightbitsPoolSecret.load(storagePoolDetailsDao, poolId);
            com.cloud.host.HostVO host = hostDao.findById(hostId);
            String hostUuid = host == null ? String.valueOf(hostId) : host.getUuid();
            org.apache.cloudstack.storage.datastore.client.LightbitsApiClient client = cfg.newClient();
            LightbitsTrustedHosts.Registration r = LightbitsTrustedHosts.register(client,
                    LightbitsTrustedHosts.name(cfg.getEnvironment(), hostUuid), nqn, key,
                    java.util.Map.of("cloudstack-host", hostUuid));
            if (r.registered() && removing(hostId)) {
                // The host's removal began, or finished, while this connect registered it. The removal
                // marks the host before it deletes the trusted host, and CloudStack deletes the host's
                // details (mark and NQN) only after that: so either the sweep runs after this write and
                // deletes it, or this check sees the mark or the missing NQN. The host never stays trusted.
                LightbitsTrustedHosts.unregister(client, cfg.getEnvironment(), nqn, new java.util.ArrayList<>());
                setHostDetail(hostId, LightbitsTrustedHosts.detailKey(poolId), null);
                setHostDetail(hostId, LightbitsTrustedHosts.keyOptionalDetailKey(poolId), null);
                LOG.warn("Lightbits: host {} is being removed; deleted the trusted host this connect had registered for"
                        + " pool {}", hostId, poolId);
                return;
            }
            if (!r.registered()) {
                setHostDetail(hostId, LightbitsTrustedHosts.detailKey(poolId), null);
                setHostDetail(hostId, LightbitsTrustedHosts.keyOptionalDetailKey(poolId), null);
                LOG.warn("Lightbits: host {} ({}) is a trusted host of another project on the cluster of pool {}; this"
                        + " pool's project cannot set its key. It can connect once in-band authentication is on only if"
                        + " that trusted host holds this host's key ({} on the host)", hostId, nqn, poolId,
                        "/etc/nvme/lightbits/dhchap-key");
            } else {
                DetailVO before = hostDetailsDao.findDetail(hostId, LightbitsTrustedHosts.detailKey(poolId));
                setHostDetail(hostId, LightbitsTrustedHosts.detailKey(poolId), fp);
                if (before == null || !fp.equals(before.getValue())) {
                    LOG.info("Lightbits: host {} registered as trusted host '{}' in project {} of pool {} (key {})", hostId,
                            r.name, cfg.getProject(), poolId, fp);
                }
                setHostDetail(hostId, LightbitsTrustedHosts.keyOptionalDetailKey(poolId),
                        Boolean.TRUE.equals(r.authRequired) ? null : r.authRequired == null ? "unknown" : "true");
                if (r.authRequired == null) {
                    LOG.warn("Lightbits: the cluster did not report authRequired for trusted host '{}' of host {} in project"
                            + " {}; it may allow connections without a key. Check it with lbcli get trusted-host"
                            + " --project-name={} --name={}", r.name, hostId, cfg.getProject(), cfg.getProject(), r.name);
                } else if (!r.authRequired) {
                    LOG.warn("Lightbits: trusted host '{}' of host {} in project {} has authRequired=false: the host has its"
                            + " key, but may still connect without one when in-band authentication is on. The plugin leaves"
                            + " this to the cluster admin (lbcli update trusted-host --project-name={} --name={}"
                            + " --auth-required=true)", r.name, hostId, cfg.getProject(), cfg.getProject(), r.name);
                }
                if (r.foreignName) {
                    LOG.info("Lightbits: host {} was already a trusted host of project {} as '{}'; set its key there", hostId,
                            cfg.getProject(), r.name);
                }
            }
        } catch (RuntimeException e) {
            setHostDetail(hostId, LightbitsTrustedHosts.detailKey(poolId), null);
            setHostDetail(hostId, LightbitsTrustedHosts.keyOptionalDetailKey(poolId), null);
            LOG.warn("Lightbits: could not register host {} as a trusted host for pool {}: {}; retried at its next connect"
                    + " (the cluster's InBandAuthentication feature flag must be on)", hostId, poolId, e.getMessage());
            // A failed write may still have been applied (a timeout or 5xx after the request was sent).
            // If the host is being removed, delete what this connect may have created, as on success.
            if (removing(hostId)) {
                try {
                    LightbitsPoolConfig cfg = LightbitsPoolSecret.load(storagePoolDetailsDao, poolId);
                    LightbitsTrustedHosts.unregister(cfg.newClient(), cfg.getEnvironment(), nqn, new java.util.ArrayList<>());
                    LOG.warn("Lightbits: host {} is being removed; deleted any trusted host the failed registration for pool {}"
                            + " may have created", hostId, poolId);
                } catch (RuntimeException e2) {
                    LOG.warn("Lightbits: host {} is being removed and its registration for pool {} failed; could not check for"
                            + " a trusted host it may have created ({}): delete it by hand (lbcli delete trusted-host)", hostId,
                            poolId, e2.getMessage());
                }
            }
        }
        reportInbandReadiness(poolId);
    }

    /**
     * The agent reports a host NQN other than the one CloudStack keeps for the host (its
     * /etc/nvme/hostnqn changed). The volume ACLs name the kept NQN, so registering the key
     * under either one would be wrong: the host is left unregistered for the pool, and the log
     * says how to reconcile.
     */
    private void refuseMismatchedNqn(long hostId, long poolId, String kept, String reported) {
        setHostDetail(hostId, LightbitsTrustedHosts.detailKey(poolId), null);
        setHostDetail(hostId, LightbitsTrustedHosts.keyOptionalDetailKey(poolId), null);
        LOG.error("Lightbits: host {} now reports host NQN {}, but CloudStack keeps {} for it (host detail '{}') and the"
                + " pool's volume ACLs name that one; its in-band authentication key is not registered for pool {}. Restore"
                + " the host's /etc/nvme/hostnqn, or put the host in maintenance, update the host detail and the volumes'"
                + " ACLs to the new NQN, then reconnect it", hostId, reported, kept,
                LightbitsPrimaryDataStoreDriver.HOST_NQN_DETAIL, poolId);
        reportInbandReadiness(poolId);
    }

    /**
     * One log line per host connect on whether the pool's hosts are ready for in-band
     * authentication: every host of the pool registered with its current key, and none with paths that do not hold it.
     * This covers only this pool's hosts; enforcement is cluster-wide, so the log says so.
     */
    private void reportInbandReadiness(long poolId) {
        java.util.List<String> unregistered = new java.util.ArrayList<>();
        java.util.List<String> keyless = new java.util.ArrayList<>();
        java.util.List<String> optional = new java.util.ArrayList<>();
        java.util.List<StoragePoolHostVO> refs = storagePoolHostDao.listByPoolId(poolId);
        for (StoragePoolHostVO ref : refs) {
            long id = ref.getHostId();
            com.cloud.host.HostVO h = hostDao.findById(id);
            String label = h == null ? String.valueOf(id) : h.getName();
            DetailVO reg = hostDetailsDao.findDetail(id, LightbitsTrustedHosts.detailKey(poolId));
            DetailVO current = hostDetailsDao.findDetail(id, LightbitsTrustedHosts.CURRENT_KEY_DETAIL);
            if (reg == null) {
                unregistered.add(label);
            } else if (!LightbitsTrustedHosts.holdsCurrentKey(reg.getValue(), current == null ? null : current.getValue())) {
                unregistered.add(label + " (registered with an earlier key)");
            } else {
                DetailVO opt = hostDetailsDao.findDetail(id, LightbitsTrustedHosts.keyOptionalDetailKey(poolId));
                if (opt != null) {
                    optional.add("unknown".equals(opt.getValue()) ? label + " (authRequired not reported)" : label);
                }
            }
            DetailVO k = hostDetailsDao.findDetail(id, LightbitsTrustedHosts.KEYLESS_DETAIL);
            if (k == null || !"0".equals(k.getValue())) {
                keyless.add(label + (k == null ? " (unknown)" : " (" + k.getValue() + ")"));
            }
        }
        String optionalNote = optional.isEmpty() ? "" : " Trusted hosts with authRequired=false (they may still connect"
                + " without a key; the cluster admin sets this): " + optional + ".";
        if (unregistered.isEmpty() && keyless.isEmpty()) {
            LOG.info("Lightbits: the {} host(s) of pool {} are ready for in-band authentication: all are trusted hosts and"
                    + " connect with their keys.{} Enforcement is cluster-wide: the cluster admin turns it on (lbcli enable"
                    + " in-band-auth) only when every host and client of the cluster is ready, including those of other"
                    + " pools, projects and clients outside CloudStack", refs.size(), poolId, optionalNote);
        } else {
            LOG.warn("Lightbits: the hosts of pool {} are NOT ready for in-band authentication; do not turn it on yet. Not"
                    + " registered: {}. With paths that do not hold the host's current key, none or an old one (they fail"
                    + " at their next reconnect once it is on; put the host in maintenance, disconnect its Lightbits paths"
                    + " and cancel maintenance, see INSTALL.md): {}.{}", poolId, unregistered, keyless,
                    optionalNote);
        }
    }

    private void setHostDetail(long hostId, String name, String value) {
        DetailVO current = hostDetailsDao.findDetail(hostId, name);
        if (value == null) {
            if (current != null) {
                hostDetailsDao.remove(current.getId());
            }
        } else if (current == null) {
            hostDetailsDao.persist(new DetailVO(hostId, name, value));
        } else if (!value.equals(current.getValue())) {
            current.setValue(value);
            hostDetailsDao.update(current.getId(), current);
        }
    }

    private org.apache.cloudstack.storage.datastore.util.LightbitsAcl.IpResolver ipResolver(long poolId) {
        return org.apache.cloudstack.storage.datastore.util.LightbitsHostIps.resolver(hostDetailsDao,
                LightbitsPrimaryDataStoreDriver.HOST_NQN_DETAIL, poolId);
    }

    /** Being removed, or already removed (its details gone): see LightbitsHostGate.removingCheck. */
    private boolean removing(long hostId) {
        return org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.removingCheck(hostDetailsDao,
                LightbitsPrimaryDataStoreDriver.HOST_NQN_DETAIL).isRemoving(hostId);
    }

    /**
     * A host that connects after its removal mark has expired is not being removed (a removal that
     * did not go through): clear the mark. A younger mark is kept, so a connect during an active
     * removal cannot clear it under the removal's feet (see LightbitsHostGate.MARK_HOLD_MILLIS).
     */
    private void clearRemovingMark(long hostId) {
        DetailVO mark = hostDetailsDao.findDetail(hostId, org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.REMOVING_DETAIL);
        if (mark == null) {
            return;
        }
        if (org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.markClearable(mark.getValue(), System.currentTimeMillis())) {
            hostDetailsDao.remove(mark.getId());
            LOG.info("Lightbits: host {} connected again; cleared its removal mark", hostId);
        } else {
            LOG.warn("Lightbits: host {} connected while its removal began less than {} min ago; its removal mark is kept"
                    + " (grants and in-band registration stay refused until it expires)", hostId,
                    org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.MARK_HOLD_MILLIS / 60000);
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

    /** Mark the host as being removed, with the time, renewed by each removal attempt. */
    private void markRemoving(long hostId) {
        setHostDetail(hostId, org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.REMOVING_DETAIL,
                org.apache.cloudstack.storage.datastore.util.LightbitsHostGate.markValue(System.currentTimeMillis()));
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
            unregisterTrustedHost(hostId, pool, nqnValue);
        }
    }

    /** Delete the trusted host the plugin made for a host that is being removed, for a pool with inbandAuth. */
    private void unregisterTrustedHost(long hostId, org.apache.cloudstack.storage.datastore.db.StoragePoolVO pool, String nqn) {
        try {
            LightbitsPoolConfig cfg = LightbitsPoolSecret.load(storagePoolDetailsDao, pool.getId());
            if (!cfg.isInbandAuth()) {
                return;
            }
            java.util.List<String> others = new java.util.ArrayList<>();
            java.util.List<String> deleted = LightbitsTrustedHosts.unregister(cfg.newClient(), cfg.getEnvironment(), nqn, others);
            if (!deleted.isEmpty()) {
                LOG.info("Lightbits: host {} is being removed: deleted its trusted host(s) {} in project {} of pool {}", hostId,
                        deleted, cfg.getProject(), pool.getName());
            }
            if (!others.isEmpty()) {
                LOG.warn("Lightbits: host {} is being removed and is still trusted host(s) {} in project {} of pool {}, which"
                        + " the plugin did not make; delete them by hand if the machine should lose access", hostId, others,
                        cfg.getProject(), pool.getName());
            }
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: could not delete the trusted host of host {} that is being removed, in pool {}: {}; delete it"
                    + " by hand (lbcli delete trusted-host)", hostId, pool.getName(), e.getMessage());
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
