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
        // The key scope is read once for the whole connect: the pool registration on the agent,
        // the key request and the propagation to other pools all use the same one.
        String scope = inbandKeyScope(poolId);
        ModifyStoragePoolCommand cmd = new ModifyStoragePoolCommand(true, pool, discoveryDetails(poolId, scope));
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

        // A removal mark is never cleared here: it counts while a removal can be active and then
        // expires on its own (LightbitsHostGate.MARK_HOLD_MILLIS), so no connect can drop the mark
        // of an active removal, and an abandoned one does not undo this connect's registration.
        ensureHostDetails(hostId, poolId, scope);
        retryRevocations(poolId);
        return true;
    }

    /** The pool's key scope when it has inbandAuth on, else null (no cluster read). */
    private String inbandKeyScope(long poolId) {
        org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig cfg = poolConfigOrNull(poolId);
        return cfg != null && cfg.isInbandAuth() ? keyScope(poolId) : null;
    }

    private java.util.Map<String, String> discoveryDetails(long poolId, String scope) {
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
                if (scope != null) {
                    out.put(com.cloud.hypervisor.kvm.storage.LightbitsStorageAdaptor.DETAIL_INBAND_KEY_SCOPE, scope);
                }
                if (cfg.isInbandAuthTwoWay()) {
                    out.put(com.cloud.hypervisor.kvm.storage.LightbitsStorageAdaptor.DETAIL_INBAND_TWO_WAY, "true");
                }
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
    private void ensureHostDetails(long hostId, long poolId, String scope) {
        org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig cfg = poolConfigOrNull(poolId);
        boolean wantIps = cfg != null && cfg.isIpAcl();
        boolean wantAuth = cfg != null && cfg.isInbandAuth();
        if (!wantIps && !wantAuth) {
            ensureHostDetails(hostId, poolId, cfg, false, false, scope);
            return;
        }
        // The agent's report (data IPs, key) and what is done with it (stored IPs and IP ACLs,
        // trusted-host registration) run under the host's report lock, held across management
        // servers: of two overlapping connects of one host, the one that asked last also commits
        // last, so an older report cannot overwrite a newer one. Without the lock in time this
        // connect only records the host NQN, and the next connect reports again.
        if (!hostGateLocker.run(reportLockName(hostId), REPORT_LOCK_SECONDS,
                () -> ensureHostDetails(hostId, poolId, cfg, wantIps, wantAuth, scope))) {
            LOG.warn("Lightbits: the report lock of host {} was busy for {} s; its {} for pool {} are recorded at its next"
                    + " connect", hostId, REPORT_LOCK_SECONDS,
                    wantIps && wantAuth ? "data IPs and in-band key" : wantIps ? "data IPs" : "in-band key", poolId);
            ensureHostDetails(hostId, poolId, cfg, false, false, scope);
        }
    }

    static final int REPORT_LOCK_SECONDS = 120;

    static String reportLockName(long hostId) {
        return "lightbits.host.report." + hostId;
    }

    private void ensureHostDetails(long hostId, long poolId, org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig cfg,
                                   boolean wantIps, boolean wantAuth, String scope) {
        String key = LightbitsPrimaryDataStoreDriver.HOST_NQN_DETAIL;
        DetailVO existing = hostDetailsDao.findDetail(hostId, key);
        boolean hasNqn = existing != null && existing.getValue() != null && !existing.getValue().isBlank();
        if (hasNqn && !wantIps && !wantAuth) {
            LOG.debug("Lightbits: host {} already has '{}' = {}", hostId, key, existing.getValue());
            return;
        }
        // Rotation: the pool's key generation is above the one this host's keys are known to
        // belong to. A host with no record has keys of unknown age: once the pool's generation
        // was raised it rotates too; at generation 0 (never raised) it is recorded as current.
        boolean rotate = false;
        if (wantAuth) {
            DetailVO gen = hostDetailsDao.findDetail(hostId, LightbitsTrustedHosts.generationDetailKey(poolId));
            if (gen == null && cfg.getInbandKeyGeneration() == 0) {
                setHostDetail(hostId, LightbitsTrustedHosts.generationDetailKey(poolId), "0");
            } else {
                rotate = gen == null || generation(gen.getValue()) < cfg.getInbandKeyGeneration();
            }
        }
        // the other pools' generations as they are before the keys are made: a raise after this
        // read then costs at most one more rotation, never a skipped one
        // Pools of the same cluster this host still keys host-wide (connected before the scope was
        // known) share the cluster's trusted host with this pool: they move to the scope with it.
        java.util.Map<Long, Integer> adoptable = wantAuth && scope != null ? hostWidePoolsOfScope(hostId, poolId, scope)
                : java.util.Map.of();
        java.util.Map<Long, Integer> otherGenerations = new java.util.LinkedHashMap<>(
                rotate ? otherPoolGenerations(hostId, poolId, scope, false) : java.util.Map.of());
        if (rotate) {
            otherGenerations.putAll(adoptable);
        }
        // the same for every in-band pool of the host, for an agent that turns out to know no scopes
        java.util.Map<Long, Integer> allGenerations = rotate && scope != null
                ? otherPoolGenerations(hostId, poolId, null, true) : otherGenerations;
        // a rotation also asks for the controller key: the new keys go to every pool of the host,
        // two-way ones included
        Answer answer = agentMgr.easySend(hostId, new LightbitsGetHostNqnCommand(wantIps ? cfg.discoveryEndpoints() : null, wantAuth,
                wantAuth && (cfg.isInbandAuthTwoWay() || rotate)).withRotateKeys(rotate)
                .withKeyScope(wantAuth ? scope : null));
        boolean rotated = false;
        if (rotate && answer instanceof LightbitsGetHostNqnAnswer && answer.getResult()) {
            rotated = recordRotation(hostId, poolId, cfg.getInbandKeyGeneration(), (LightbitsGetHostNqnAnswer) answer);
        }
        if (answer instanceof LightbitsGetHostNqnAnswer && answer.getResult()
                && ((LightbitsGetHostNqnAnswer) answer).getHostNqn() != null) {
            String nqn = ((LightbitsGetHostNqnAnswer) answer).getHostNqn();
            if (!hasNqn) {
                // under the NQN's lock: a pending revocation of a removed host with this NQN is
                // then either finished before, or sees this host and is dropped
                Runnable record = () -> hostDetailsDao.persist(new DetailVO(hostId, key, nqn));
                if (!hostGateLocker.run(org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.nqnLockName(nqn),
                        org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.NQN_LOCK_SECONDS, record)) {
                    // Nothing that uses the NQN goes ahead outside the lock's order: a revocation still
                    // running for it could undo this host's registration and grants.
                    LOG.warn("Lightbits: the lock of host NQN {} was busy for {} s (a pending revocation of a removed host with"
                            + " this NQN is running); host {} records it, and registers for pool {}, at its next connect", nqn,
                            org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.NQN_LOCK_SECONDS, hostId, poolId);
                    return;
                }
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
                    LightbitsGetHostNqnAnswer report = (LightbitsGetHostNqnAnswer) answer;
                    String hostNqn = hasNqn ? existing.getValue() : nqn;
                    // the scope the host's keys for this pool now have (none from an agent without scopes)
                    setHostDetail(hostId, LightbitsTrustedHosts.perPool(HOST_SCOPE_DETAIL, poolId),
                            report.getKeyScope() == null ? HOST_WIDE : report.getKeyScope());
                    registerTrustedHost(hostId, poolId, hostNqn, report);
                    // An agent that does not know scopes answers without one: its keys are host-wide and
                    // shared by every in-band pool of the host, whatever cluster each is on.
                    boolean hostWide = scope != null && report.getKeyScope() == null;
                    if (!hostWide && !adoptable.isEmpty()) {
                        // a pool the agent could not be given with the scope still uses its host-wide
                        // keys: it gets no rotated keys and no generation, so it rotates at its next connect
                        for (Long failed : adoptScope(hostId, adoptable.keySet(), scope, hostNqn, report, rotated)) {
                            otherGenerations.remove(failed);
                        }
                    }
                    if (rotated) {
                        registerWithOtherPools(hostId, hostWide ? allGenerations : otherGenerations, hostNqn, report);
                    } else if (report.getDhchapKey() != null) {
                        // the keys changed without a rotation (files replaced on the host): the other
                        // pools of the same keys still hold the old ones, so register them again
                        registerWhereKeysChanged(hostId, poolId, hostWide ? null : scope, hostWide, hostNqn, report);
                    }
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
                    poolSecrets.apply(poolId);
            java.util.List<String> updated = org.apache.cloudstack.storage.datastore.util.LightbitsAcl.refreshIps(clients.apply(cfg),
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
            clearRegistration(hostId, poolId);
            setHostDetail(hostId, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.KEYLESS_DETAIL, poolId), null);
            setHostDetail(hostId, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, poolId), null);
            reportInbandReadiness(poolId);
            return;
        }
        setHostDetail(hostId, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.KEYLESS_DETAIL, poolId),
                answer.getKeylessPaths() == null ? null : String.valueOf(answer.getKeylessPaths()));
        // only a report changes the stored state: a host whose check failed keeps its warning
        if (answer.getKeysPossiblyExposed() != null) {
            setHostDetail(hostId, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.KEYS_EXPOSED_DETAIL, poolId),
                    answer.isKeysPossiblyExposed() ? "true" : null);
        }
        if (answer.isKeysPossiblyExposed()) {
            LOG.warn("Lightbits: host {} reports that its in-band authentication key files were readable by other users"
                    + " than root before they were made root-only again; the keys may have been copied. Rotate them: raise"
                    + " the pool detail {} and cycle the host (INSTALL.md, \"Rotating the keys\")", hostId,
                    LightbitsPoolConfig.KEY_INBAND_KEY_GENERATION);
        }
        String fp = LightbitsTrustedHosts.fingerprint(key);
        // host-wide: a pool whose registration holds another fingerprint no longer counts this host as ready
        setHostDetail(hostId, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, poolId), fp);
        String ctrl = null;
        try {
            LightbitsPoolConfig cfg = poolSecrets.apply(poolId);
            if (cfg.isInbandAuthTwoWay()) {
                ctrl = answer.getDhchapCtrlKey();
                if (ctrl == null) {
                    LOG.warn("Lightbits: pool {} has inbandAuthTwoWay on, but host {} sent no controller key (its agent is"
                            + " older than this plugin version, or the key could not be made; see the agent log); it is"
                            + " registered one-way", poolId, hostId);
                    setHostDetail(hostId, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_CTRL_KEY_DETAIL, poolId), null);
                    setHostDetail(hostId, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CTRL_KEYLESS_DETAIL, poolId), null);
                } else {
                    setHostDetail(hostId, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_CTRL_KEY_DETAIL, poolId), LightbitsTrustedHosts.fingerprint(ctrl));
                    setHostDetail(hostId, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CTRL_KEYLESS_DETAIL, poolId),
                            answer.getPathsWithoutCtrlKey() == null ? null : String.valueOf(answer.getPathsWithoutCtrlKey()));
                }
            }
            com.cloud.host.HostVO host = hostDao.findById(hostId);
            String hostUuid = host == null ? String.valueOf(hostId) : host.getUuid();
            org.apache.cloudstack.storage.datastore.client.LightbitsApiClient client = clients.apply(cfg);
            LightbitsTrustedHosts.Registration r = LightbitsTrustedHosts.register(client,
                    LightbitsTrustedHosts.name(cfg.getEnvironment(), hostUuid), nqn, key, ctrl,
                    java.util.Map.of("cloudstack-host", hostUuid));
            if (r.registered() && removing(hostId)) {
                // The host's removal began, or finished, while this connect registered it. The removal
                // marks the host before it deletes the trusted host, and CloudStack deletes the host's
                // details (mark and NQN) only after that: so either the sweep runs after this write and
                // deletes it, or this check sees the mark or the missing NQN. The host never stays trusted.
                LightbitsTrustedHosts.unregister(client, cfg.getEnvironment(), nqn, new java.util.ArrayList<>());
                clearRegistration(hostId, poolId);
                LOG.warn("Lightbits: host {} is being removed; deleted the trusted host this connect had registered for"
                        + " pool {}", hostId, poolId);
                return;
            }
            if (!r.registered()) {
                clearRegistration(hostId, poolId);
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
                setHostDetail(hostId, LightbitsTrustedHosts.ctrlDetailKey(poolId),
                        r.twoWay ? LightbitsTrustedHosts.fingerprint(ctrl) : null);
                if (r.targetKept) {
                    LOG.info("Lightbits: trusted host '{}' of host {} keeps the target secret it has (another pool of project"
                            + " {} uses two-way authentication); pool {} itself is one-way", r.name, hostId, cfg.getProject(),
                            poolId);
                }
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
            clearRegistration(hostId, poolId);
            LOG.warn("Lightbits: could not register host {} as a trusted host for pool {}: {}; retried at its next connect"
                    + " (the cluster's InBandAuthentication feature flag must be on)", hostId, poolId, e.getMessage());
            // A failed write may still have been applied (a timeout or 5xx after the request was sent).
            // If the host is being removed, delete what this connect may have created, as on success.
            if (removing(hostId)) {
                try {
                    LightbitsPoolConfig cfg = poolSecrets.apply(poolId);
                    LightbitsTrustedHosts.unregister(clients.apply(cfg), cfg.getEnvironment(), nqn, new java.util.ArrayList<>());
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
        clearRegistration(hostId, poolId);
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
        java.util.List<String> pendingRotation = new java.util.ArrayList<>();
        java.util.List<String> exposed = new java.util.ArrayList<>();
        java.util.List<StoragePoolHostVO> refs = storagePoolHostDao.listByPoolId(poolId);
        org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig cfg = poolConfigOrNull(poolId);
        if (cfg == null) {
            // without the options it is not known whether the pool is two-way: never say ready
            LOG.warn("Lightbits: readiness of pool {} for in-band authentication is unknown: its options could not be read",
                    poolId);
            return;
        }
        boolean twoWay = cfg.isInbandAuthTwoWay();
        for (StoragePoolHostVO ref : refs) {
            long id = ref.getHostId();
            com.cloud.host.HostVO h = hostDao.findById(id);
            String label = h == null ? String.valueOf(id) : h.getName();
            DetailVO reg = hostDetailsDao.findDetail(id, LightbitsTrustedHosts.detailKey(poolId));
            DetailVO current = hostDetailsDao.findDetail(id, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, poolId));
            if (reg == null) {
                unregistered.add(label);
            } else if (!LightbitsTrustedHosts.holdsCurrentKey(reg.getValue(), current == null ? null : current.getValue())) {
                unregistered.add(label + " (registered with an earlier key)");
            } else if (twoWay && !LightbitsTrustedHosts.holdsCurrentKey(value(id, LightbitsTrustedHosts.ctrlDetailKey(poolId)),
                    value(id, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_CTRL_KEY_DETAIL, poolId)))) {
                unregistered.add(label + " (no current controller key registered: two-way not in place)");
            } else {
                DetailVO opt = hostDetailsDao.findDetail(id, LightbitsTrustedHosts.keyOptionalDetailKey(poolId));
                if (opt != null) {
                    optional.add("unknown".equals(opt.getValue()) ? label + " (authRequired not reported)" : label);
                }
            }
            if ("true".equals(value(id, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.KEYS_EXPOSED_DETAIL, poolId)))) {
                exposed.add(label);
            }
            String gen = value(id, LightbitsTrustedHosts.generationDetailKey(poolId));
            if (gen != null && generation(gen) < cfg.getInbandKeyGeneration()) {
                pendingRotation.add(label);
            }
            DetailVO k = hostDetailsDao.findDetail(id, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.KEYLESS_DETAIL, poolId));
            if (k == null || !"0".equals(k.getValue())) {
                keyless.add(label + (k == null ? " (unknown)" : " (" + k.getValue() + ")"));
            }
            if (twoWay) {
                String c = value(id, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CTRL_KEYLESS_DETAIL, poolId));
                if (!"0".equals(c)) {
                    keyless.add(label + (c == null ? " (controller key: unknown)" : " (" + c + " without the current controller key)"));
                }
            }
        }
        String optionalNote = (optional.isEmpty() ? "" : " Trusted hosts with authRequired=false (they may still connect"
                + " without a key; the cluster admin sets this): " + optional + ".")
                + (pendingRotation.isEmpty() ? "" : " Hosts still on keys older than key generation "
                + cfg.getInbandKeyGeneration() + " (each rotates at a reconnect right after a maintenance cycle: cmk reconnect host): " + pendingRotation + ".")
                + (exposed.isEmpty() ? "" : " Hosts whose keys may have been copied (their key files were readable by other"
                + " users) and should be rotated: " + exposed + ".");
        if (unregistered.isEmpty() && keyless.isEmpty()) {
            LOG.info("Lightbits: the {} host(s) of pool {} are ready for in-band authentication: all are trusted hosts and"
                    + " connect with their keys{}.{} Enforcement is cluster-wide: the cluster admin turns it on (lbcli enable"
                    + " in-band-auth) only when every host and client of the cluster is ready, including those of other"
                    + " pools, projects and clients outside CloudStack", refs.size(), poolId,
                    twoWay ? ", and the cluster proves itself to each host with its controller key (two-way)" : "", optionalNote);
        } else {
            LOG.warn("Lightbits: the hosts of pool {} are NOT ready for in-band authentication; do not turn it on yet. Not"
                    + " registered: {}. With paths that do not hold the host's current key, none or an old one (they fail"
                    + " at their next reconnect once it is on; put the host in maintenance, disconnect its Lightbits paths"
                    + " and cancel maintenance, see INSTALL.md): {}.{}", poolId, unregistered, keyless,
                    optionalNote);
        }
    }

    private static int generation(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Record the outcome of a requested rotation. Done: the host's generation for the pool is
     * raised (its new keys are registered right after, in the same connect). Refused (volumes in
     * use) or not understood (an older agent): logged, and asked again at the next connect.
     */
    private boolean recordRotation(long hostId, long poolId, int generation, LightbitsGetHostNqnAnswer answer) {
        if (Boolean.TRUE.equals(answer.getKeysRotated())) {
            setHostDetail(hostId, LightbitsTrustedHosts.generationDetailKey(poolId), String.valueOf(generation));
            LOG.info("Lightbits: host {} rotated its in-band authentication keys (pool {} key generation {})", hostId, poolId,
                    generation);
            return true;
        } else if (answer.getKeysRotated() == null) {
            LOG.warn("Lightbits: host {} did not rotate its in-band authentication keys for pool {} key generation {}: its"
                    + " agent is older than this plugin version; upgrade it", hostId, poolId, generation);
        } else {
            LOG.warn("Lightbits: host {} did not rotate its in-band authentication keys for pool {} key generation {}: {}."
                    + " Asked again at its next connect", hostId, poolId, generation, answer.getRotateError());
        }
        return false;
    }

    /**
     * After a rotation, register the host's new keys with every other inbandAuth pool it is in:
     * the keys are per host, and each of those clusters would refuse the new key until that
     * pool's own host connect. Runs under the host's report lock, like every registration.
     */
    /**
     * The scope of a pool's host keys: its cluster's subsystem NQN, read once from the cluster and
     * kept as a pool detail ({@value #KEY_SCOPE_DETAIL}). Null when it cannot be read: the host
     * then uses its host-wide keys, as with a management server of an earlier version.
     */
    String keyScope(long poolId) {
        String kept = storagePoolDetailsDao.listDetailsKeyPairs(poolId).get(KEY_SCOPE_DETAIL);
        if (kept != null && !kept.isBlank()) {
            return kept.trim();
        }
        try {
            LightbitsPoolConfig cfg = poolSecrets.apply(poolId);
            String scope = clients.apply(cfg).clusterSubsystemNqn();
            if (scope != null) {
                storagePoolDetailsDao.addDetail(poolId, KEY_SCOPE_DETAIL, scope, false);
            }
            return scope;
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: could not read the subsystem NQN of the cluster of pool {} ({}); its hosts use their"
                    + " host-wide in-band keys until it can be read", poolId, e.getMessage());
            return null;
        }
    }

    /** Pool detail: the cluster's subsystem NQN, the scope of the hosts' in-band keys for the pool. */
    static final String KEY_SCOPE_DETAIL = "lightbits.subsystemnqn";

    /** The key generation of every other inbandAuth Lightbits pool of the host on the same cluster, by pool id. */
    private java.util.Map<Long, Integer> otherPoolGenerations(long hostId, long rotatingPoolId, String scope, boolean anyScope) {
        java.util.Map<Long, Integer> out = new java.util.LinkedHashMap<>();
        for (StoragePoolHostVO ref : storagePoolHostDao.listByHostId(hostId)) {
            long other = ref.getPoolId();
            if (other == rotatingPoolId) {
                continue;
            }
            org.apache.cloudstack.storage.datastore.db.StoragePoolVO p = primaryDataStoreDao.findById(other);
            if (p == null || !org.apache.cloudstack.storage.datastore.util.LightbitsUtil.PROVIDER_NAME.equals(p.getStorageProviderName())) {
                continue;
            }
            org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig c = poolConfigOrNull(other);
            // only pools of the same cluster share the rotated keys; other clusters keep theirs
            // the other pool's kept scope, not a fresh cluster read: the scope its agent was given
            if (c != null && c.isInbandAuth() && (anyScope || java.util.Objects.equals(scope, hostScope(hostId, other)))) {
                out.put(other, c.getInbandKeyGeneration());
            }
        }
        return out;
    }

    /** Host detail (per pool): the key scope this host was last given for the pool; {@value #HOST_WIDE} for none. */
    static final String HOST_SCOPE_DETAIL = "lightbits.keyscope";
    static final String HOST_WIDE = "host-wide";

    /**
     * The key scope the host uses for a pool: the one recorded at the host's last connect of that
     * pool (null for its host-wide keys), else the pool's kept scope. Scopes are per host, since
     * one host may have connected a pool before the pool's scope was known.
     */
    private String hostScope(long hostId, long poolId) {
        String recorded = value(hostId, LightbitsTrustedHosts.perPool(HOST_SCOPE_DETAIL, poolId));
        if (recorded != null) {
            return HOST_WIDE.equals(recorded) ? null : recorded;
        }
        return keptScope(poolId);
    }

    /**
     * The other in-band pools of the host whose scope is this one but which the host still keys
     * host-wide (recorded so at their last connect), with their key generations. A pool whose scope
     * is not kept yet is read from its cluster now ({@link #keyScope}).
     */
    private java.util.Map<Long, Integer> hostWidePoolsOfScope(long hostId, long poolId, String scope) {
        java.util.Map<Long, Integer> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<Long, Integer> e : otherPoolGenerations(hostId, poolId, null, true).entrySet()) {
            if (HOST_WIDE.equals(value(hostId, LightbitsTrustedHosts.perPool(HOST_SCOPE_DETAIL, e.getKey())))
                    && scope.equals(keyScope(e.getKey()))) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * Give the agent each pool again with the scope, so its connects use the cluster's keys, and
     * record the scope for the host; without a rotation, register the keys with each pool (with
     * a rotation, the caller registers them with the generations). Their paths still hold the
     * host-wide key, which the scoped one starts as a copy of (or, after a rotation, none: the
     * rotation needed them idle). Returns the pools the agent could not be given with the scope.
     */
    private java.util.Set<Long> adoptScope(long hostId, java.util.Set<Long> pools, String scope, String nqn,
                                           LightbitsGetHostNqnAnswer report, boolean rotated) {
        java.util.Set<Long> failed = new java.util.HashSet<>();
        for (Long other : pools) {
            StoragePool p = (StoragePool) dataStoreMgr.getDataStore(other, DataStoreRole.Primary);
            Answer a = agentMgr.easySend(hostId, new ModifyStoragePoolCommand(true, p, discoveryDetails(other, scope)));
            if (a == null || !a.getResult()) {
                LOG.warn("Lightbits: could not give pool {} to host {} with its cluster's key scope ({}); it moves at its next"
                        + " connect", other, hostId, a == null ? "no answer" : a.getDetails());
                failed.add(other);
                continue;
            }
            setHostDetail(hostId, LightbitsTrustedHosts.perPool(HOST_SCOPE_DETAIL, other), scope);
            LOG.info("Lightbits: host {} now uses its keys for the cluster of pool {} (shared with pool's trusted host)", hostId, other);
            if (!rotated) {
                org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig c = poolConfigOrNull(other);
                if (c != null && (!c.isInbandAuthTwoWay() || report.getDhchapCtrlKey() != null)) {
                    registerTrustedHost(hostId, other, nqn, report);
                }
            }
        }
        return failed;
    }

    /** The pool's key scope as kept, without reading the cluster; null when not known yet. */
    private String keptScope(long poolId) {
        String kept = storagePoolDetailsDao.listDetailsKeyPairs(poolId).get(KEY_SCOPE_DETAIL);
        return kept == null || kept.isBlank() ? null : kept.trim();
    }

    /**
     * Register the host again with each other in-band pool of the same keys whose recorded
     * current key (or controller key) differs from the one just reported, so their readiness
     * follows the keys the host now has.
     */
    private void registerWhereKeysChanged(long hostId, long poolId, String scope, boolean anyScope, String nqn,
                                          LightbitsGetHostNqnAnswer answer) {
        String fp = LightbitsTrustedHosts.fingerprint(answer.getDhchapKey());
        String ctrlFp = answer.getDhchapCtrlKey() == null ? null : LightbitsTrustedHosts.fingerprint(answer.getDhchapCtrlKey());
        for (Long other : otherPoolGenerations(hostId, poolId, scope, anyScope).keySet()) {
            org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig c = poolConfigOrNull(other);
            String known = value(hostId, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, other));
            if (c != null && c.isInbandAuthTwoWay() && ctrlFp == null) {
                // this report has no controller key, so the pool cannot be registered from it; if the
                // host key changed, its records are stale: the pool reports the host not ready until
                // its own connect registers both keys
                if (known != null && !known.equals(fp)) {
                    LOG.info("Lightbits: host {} has a new in-band key; pool {} (two-way) counts it not ready until its next"
                            + " connect", hostId, other);
                    clearRegistration(hostId, other);
                    setHostDetail(hostId, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, other), fp);
                    setHostDetail(hostId, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.KEYLESS_DETAIL, other), null);
                    reportInbandReadiness(other);
                }
                continue;
            }
            String knownCtrl = value(hostId, LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_CTRL_KEY_DETAIL, other));
            if (known != null && !known.equals(fp) || ctrlFp != null && knownCtrl != null && !knownCtrl.equals(ctrlFp)) {
                LOG.info("Lightbits: host {} has new in-band keys; registering them with pool {} too", hostId, other);
                registerTrustedHost(hostId, other, nqn, answer);
            }
        }
    }

    private void registerWithOtherPools(long hostId, java.util.Map<Long, Integer> generations, String nqn,
                                        LightbitsGetHostNqnAnswer answer) {
        for (java.util.Map.Entry<Long, Integer> e : generations.entrySet()) {
            registerTrustedHost(hostId, e.getKey(), nqn, answer);
            // the keys were made after this generation was read: they are at least that new
            setHostDetail(hostId, LightbitsTrustedHosts.generationDetailKey(e.getKey()), String.valueOf(e.getValue()));
        }
    }

    private String value(long hostId, String name) {
        DetailVO d = hostDetailsDao.findDetail(hostId, name);
        return d == null ? null : d.getValue();
    }

    /** Forget the host's registration for the pool: host key, authRequired and controller key details. */
    private void clearRegistration(long hostId, long poolId) {
        setHostDetail(hostId, LightbitsTrustedHosts.detailKey(poolId), null);
        setHostDetail(hostId, LightbitsTrustedHosts.keyOptionalDetailKey(poolId), null);
        setHostDetail(hostId, LightbitsTrustedHosts.ctrlDetailKey(poolId), null);
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

    /** A pool's configuration with its token (CloudStack's database encryption); tests swap it. */
    java.util.function.LongFunction<LightbitsPoolConfig> poolSecrets = id -> LightbitsPoolSecret.load(storagePoolDetailsDao, id);

    /** The API client for a pool's configuration; tests swap it for one aimed at a fake API. */
    java.util.function.Function<LightbitsPoolConfig, org.apache.cloudstack.storage.datastore.client.LightbitsApiClient> clients =
            LightbitsPoolConfig::newClient;

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
            // Renew the removal mark for each pool, and pruneHost renews it before each volume: the sweep
            // has no overall deadline, so the mark is kept fresh for as long as the removal runs (one
            // volume update, with its retries, takes far less than the mark's 30 min).
            markRemoving(hostId);
            // Recorded before anything is sent, and cleared only when the cluster confirms the
            // revocation below: an outage now is retried later instead of being forgotten.
            try {
                // under the NQN's lock, which a retry holds while it checks and cancels a record: a
                // record written now is never cancelled by a retry that looked before it (lock order:
                // host locks, then the NQN lock, as at a host connect)
                long poolId = pool.getId();
                Runnable record = () -> org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.record(
                        storagePoolDetailsDao, poolId, nqnValue);
                if (!hostGateLocker.run(org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.nqnLockName(nqnValue),
                        org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.NQN_LOCK_SECONDS, record)) {
                    // the removal must not wait longer; a retry running now sees this host as being removed
                    // (its mark is set) and revokes rather than cancels
                    LOG.warn("Lightbits: the lock of host NQN {} was busy for {} s; recording the pending revocation on pool {}"
                            + " without it", nqnValue, org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.NQN_LOCK_SECONDS,
                            pool.getName());
                    record.run();
                }
            } catch (RuntimeException e) {
                LOG.warn("Lightbits: could not record the pending revocation of host {} on pool {}: {}", hostId, pool.getName(),
                        e.getMessage());
            }
            try {
                org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig cfg =
                        poolSecrets.apply(pool.getId());
                java.util.List<String> pruned = new java.util.ArrayList<>();
                Runnable prune = () -> pruned.addAll(org.apache.cloudstack.storage.datastore.util.LightbitsAcl.pruneHost(
                        clients.apply(cfg), cfg.getEnvironment(), pool.getId(), nqnValue, cfg.isIpAcl() ? ipResolver(pool.getId()) : null,
                        () -> markRemoving(hostId)));
                // With ipAcl, under the pool's IP ACL lock (inside the host's lock, as everywhere). The
                // removal must not be held up: without the lock in time it removes the NQN only and
                // leaves the IP ACLs as they are, since IPs resolved outside the lock may be stale. The
                // removed host's address then stays until the next locked write of that volume's IP ACL.
                if (cfg.isIpAcl() && !hostGateLocker.run(ipAclLockName(pool.getId()), IP_ACL_LOCK_SECONDS, prune)) {
                    LOG.warn("Lightbits: the IP ACL lock of pool {} was busy for {} s while host {} is being removed; removing"
                            + " its NQN only, the volumes' IP ACLs keep its address until their next attach, migration or"
                            + " host connect", pool.getName(), IP_ACL_LOCK_SECONDS, hostId);
                    pruned.addAll(org.apache.cloudstack.storage.datastore.util.LightbitsAcl.pruneHost(
                            clients.apply(cfg), cfg.getEnvironment(), pool.getId(), nqnValue, null, () -> markRemoving(hostId)));
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
            try {
                LightbitsPoolConfig cfg = poolSecrets.apply(pool.getId());
                if (org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.revokeOrKeep(storagePoolDetailsDao,
                        clients.apply(cfg), cfg, pool.getId(), nqnValue)) {
                    LOG.info("Lightbits: host {} is revoked on the cluster of pool {}", hostId, pool.getName());
                }
            } catch (RuntimeException e) {
                LOG.warn("Lightbits: the revocation of host {} on pool {} stays pending and is retried: {}", hostId,
                        pool.getName(), e.getMessage());
            }
        }
    }

    /** Retry the pool's pending revocations of removed hosts (cheap when there are none). */
    private void retryRevocations(long poolId) {
        try {
            if (org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.pending(storagePoolDetailsDao, poolId).isEmpty()) {
                return;
            }
            LightbitsPoolConfig cfg = poolSecrets.apply(poolId);
            org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.retry(storagePoolDetailsDao, hostDetailsDao,
                    LightbitsPrimaryDataStoreDriver.HOST_NQN_DETAIL, poolId, cfg, clients.apply(cfg), hostGateLocker);
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: could not retry the pending revocations of pool {}: {}", poolId, e.getMessage());
        }
    }

    /** Delete the trusted host the plugin made for a host that is being removed, for a pool with inbandAuth. */
    private void unregisterTrustedHost(long hostId, org.apache.cloudstack.storage.datastore.db.StoragePoolVO pool, String nqn) {
        try {
            LightbitsPoolConfig cfg = poolSecrets.apply(pool.getId());
            if (!cfg.isInbandAuth()) {
                return;
            }
            java.util.List<String> others = new java.util.ArrayList<>();
            java.util.List<String> deleted = LightbitsTrustedHosts.unregister(clients.apply(cfg), cfg.getEnvironment(), nqn, others);
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
