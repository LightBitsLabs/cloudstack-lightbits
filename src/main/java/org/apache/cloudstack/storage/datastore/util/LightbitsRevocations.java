/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.datastore.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.storage.datastore.client.LightbitsApiClient;
import org.apache.cloudstack.storage.datastore.client.model.Volume;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.host.DetailVO;
import com.cloud.host.dao.HostDetailsDao;

/**
 * Revocations of removed hosts that the cluster has not confirmed yet. When a host leaves
 * CloudStack, each Lightbits pool records its NQN as a pending revocation (a pool detail, so it
 * outlives the host's own records) before it takes the host out of the volume ACLs and deletes
 * its trusted host. The record is cleared only when the cluster confirms both: no volume of the
 * pool lists the NQN, and no trusted host the plugin made for it is left. Until then the pool
 * retries it (on the management server's storage statistics poll and at host connects) and
 * logs it, so an API outage during a removal does not leave the machine with access.
 */
public final class LightbitsRevocations {
    private static final Logger LOG = LogManager.getLogger(LightbitsRevocations.class);
    /** Pool detail prefix of a pending revocation; the value is the host NQN. */
    public static final String DETAIL_PREFIX = "lightbits.revoke.";

    private LightbitsRevocations() {
    }

    /** The pool detail name for an NQN (a digest: the detail name has a length limit, the NQN is the value). */
    static String detailKey(String nqn) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(nqn.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(DETAIL_PREFIX);
            for (int i = 0; i < 8; i++) {
                b.append(String.format("%02x", d[i]));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Record a revocation of the NQN on the pool's cluster as pending. */
    public static void record(StoragePoolDetailsDao dao, long poolId, String nqn) {
        try {
            dao.addDetail(poolId, detailKey(nqn), nqn, false);
            unsaved(poolId).remove(nqn);
        } catch (RuntimeException e) {
            // CloudStack removes the host whatever its listeners return, so the revocation cannot
            // hold the removal up: keep it in memory, retried and saved again on each retry pass.
            unsaved(poolId).add(nqn);
            LOG.error("Lightbits: could not save the pending revocation of removed host {} on pool {} ({}); it is kept in"
                    + " memory and retried, but a management server restart before it is saved or confirmed loses it. Check"
                    + " that no volume ACL of the pool lists the NQN and that its trusted host is gone", nqn, poolId, e.getMessage());
        }
    }

    /** Revocations whose record could not be saved, per pool; lost at a management server restart. */
    private static final Map<Long, java.util.Set<String>> UNSAVED = new java.util.concurrent.ConcurrentHashMap<>();

    private static java.util.Set<String> unsaved(long poolId) {
        return UNSAVED.computeIfAbsent(poolId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
    }

    /** The NQNs whose revocation on the pool's cluster is pending. */
    public static List<String> pending(StoragePoolDetailsDao dao, long poolId) {
        // a revocation kept in memory is saved now if the database takes it
        for (String nqn : new ArrayList<>(unsaved(poolId))) {
            record(dao, poolId, nqn);
        }
        List<String> out = new ArrayList<>(unsaved(poolId));
        Map<String, String> details = dao.listDetailsKeyPairs(poolId);
        if (details != null) {
            for (Map.Entry<String, String> e : details.entrySet()) {
                if (e.getKey() != null && e.getKey().startsWith(DETAIL_PREFIX) && e.getValue() != null && !e.getValue().isBlank()) {
                    if (!out.contains(e.getValue().trim())) {
                        out.add(e.getValue().trim());
                    }
                }
            }
        }
        return out;
    }

    static void clear(StoragePoolDetailsDao dao, long poolId, String nqn) {
        unsaved(poolId).remove(nqn);
        dao.removeDetail(poolId, detailKey(nqn));
    }

    /**
     * Revoke the NQN on the pool's cluster and say whether the cluster confirms it: the NQN is
     * taken out of the pool's volume ACLs (the IP ACLs are left for their next update, which
     * resolves the remaining hosts), its plugin-made trusted hosts are deleted, and then both are
     * read back. Throws when the cluster cannot be reached.
     */
    public static boolean revoke(LightbitsApiClient client, LightbitsPoolConfig cfg, long poolId, String nqn) {
        LightbitsAcl.pruneHost(client, cfg.getEnvironment(), poolId, nqn);
        for (Volume v : client.listVolumes()) {
            if (v.getName() != null && LightbitsUtil.isPoolObject(cfg.getEnvironment(), poolId, v.getName())
                    && LightbitsAcl.hosts(v).contains(nqn)) {
                return false;
            }
        }
        if (cfg.isInbandAuth()) {
            LightbitsTrustedHosts.unregister(client, cfg.getEnvironment(), nqn, new ArrayList<>());
            for (LightbitsApiClient.TrustedHost h : client.listTrustedHosts()) {
                if (nqn.equals(h.hostNqn) && LightbitsTrustedHosts.ownedBy(h.name, cfg.getEnvironment())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Revoke the NQN now and clear the pending record when the cluster confirms; otherwise the
     * record stays for a later retry. Returns true when done.
     */
    public static boolean revokeOrKeep(StoragePoolDetailsDao dao, LightbitsApiClient client, LightbitsPoolConfig cfg, long poolId,
                                       String nqn) {
        try {
            if (revoke(client, cfg, poolId, nqn)) {
                clear(dao, poolId, nqn);
                return true;
            }
            LOG.warn("Lightbits: the cluster of pool {} still lists removed host {} in a volume ACL or as a trusted host;"
                    + " the revocation stays pending and is retried", poolId, nqn);
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: could not revoke removed host {} on the cluster of pool {} ({}); the revocation stays"
                    + " pending and is retried", nqn, poolId, e.getMessage());
        }
        return false;
    }

    /**
     * Retry the pool's pending revocations. An NQN that a CloudStack host (not one being
     * removed) has again was added back on purpose: its record is dropped, not revoked.
     */
    public static void retry(StoragePoolDetailsDao poolDetails, HostDetailsDao hostDetails, String nqnDetail, long poolId,
                             LightbitsPoolConfig cfg, LightbitsApiClient client, LightbitsHostGate.Locker locker) {
        List<String> pending = pending(poolDetails, poolId);
        if (pending.isEmpty()) {
            return;
        }
        for (String nqn : pending) {
            // Under the NQN's lock, which a host takes to record this NQN as its own: a host added
            // back with it either is seen here (and the record dropped) or records it after the
            // revocation is done, so its own registration and grants come after and stay.
            if (!locker.run(nqnLockName(nqn), NQN_LOCK_SECONDS,
                    () -> retryOne(poolDetails, hostDetails, nqnDetail, poolId, cfg, client, nqn))) {
                LOG.info("Lightbits: the lock of host NQN {} was busy; its pending revocation on pool {} is retried later", nqn, poolId);
            }
        }
    }

    /** Seconds a revocation retry or a host recording its NQN waits for the NQN's lock. */
    public static final int NQN_LOCK_SECONDS = 60;

    /** The lock a revocation retry and a host recording this NQN as its own share. */
    public static String nqnLockName(String nqn) {
        return "lightbits.nqn." + detailKey(nqn).substring(DETAIL_PREFIX.length());
    }

    private static void retryOne(StoragePoolDetailsDao poolDetails, HostDetailsDao hostDetails, String nqnDetail, long poolId,
                                 LightbitsPoolConfig cfg, LightbitsApiClient client, String nqn) {
        LightbitsHostGate.RemovingCheck removing = LightbitsHostGate.removingCheck(hostDetails, nqnDetail);
        for (DetailVO d : hostDetails.findByName(nqnDetail)) {
            if (d.getValue() != null && nqn.equals(d.getValue().trim()) && !removing.isRemoving(d.getHostId())) {
                clear(poolDetails, poolId, nqn);
                LOG.info("Lightbits: host NQN {} belongs to a CloudStack host again; dropped its pending revocation on pool {}",
                        nqn, poolId);
                return;
            }
        }
        if (revokeOrKeep(poolDetails, client, cfg, poolId, nqn)) {
            LOG.info("Lightbits: completed the pending revocation of removed host {} on pool {}", nqn, poolId);
        }
    }
}
