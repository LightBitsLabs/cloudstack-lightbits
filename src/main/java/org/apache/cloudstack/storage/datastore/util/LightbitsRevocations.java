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

    /**
     * Records are append-only: each write is a pool detail of its own (the NQN's digest, then a
     * version), and a retry or a confirmed revocation deletes exactly the records it looked at
     * before it acted. A record written later, by any management server and with or without the
     * NQN lock, is never deleted by an action that did not see it.
     */
    private static String newRecordKey(String nqn) {
        return detailKey(nqn) + "." + Long.toHexString(System.currentTimeMillis())
                + Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt() & 0x7fffffff);
    }

    /** Record a revocation of the NQN on the pool's cluster as pending. */
    public static void record(StoragePoolDetailsDao dao, long poolId, String nqn) {
        String key = newRecordKey(nqn);
        try {
            dao.addDetail(poolId, key, nqn, false);
        } catch (RuntimeException e) {
            // CloudStack removes the host whatever its listeners return, so the revocation cannot
            // hold the removal up: keep it in memory and in a local file, saved again on each retry pass.
            loadUnsaved();
            unsaved(poolId).put(key, nqn);
            boolean kept = writeUnsaved();
            LOG.error("Lightbits: could not save the pending revocation of removed host {} on pool {} ({}); it is kept"
                    + (kept ? " in memory and in " + fallbackFile + " on this management server" : " in memory only (the local"
                    + " file could not be written either)") + " and retried until the database takes it. If this management"
                    + " server is lost before then, keep the removed machine fenced and remove the NQN from the pool's volume"
                    + " ACLs and trusted hosts by hand (INSTALL.md, \"Removing a host\")", nqn, poolId, e.getMessage());
        }
    }

    /**
     * Local copy of the records the database refused, on the management server (one line per
     * record: pool id, record key, NQN; no secret). Read back after a restart of this management
     * server, so a restart before the database takes them does not lose them.
     */
    static java.nio.file.Path fallbackFile = java.nio.file.Paths.get("/var/lib/cloudstack/lightbits/pending-revocations");

    private static final java.util.concurrent.atomic.AtomicBoolean LOADED = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Read the local copy once per process, into the records kept in memory. It holds the same
     * monitor as {@link #writeUnsaved()}, so no caller rewrites the file while it is being read.
     */
    static synchronized void loadUnsaved() {
        if (!LOADED.compareAndSet(false, true)) {
            return;
        }
        java.nio.file.Path f = fallbackFile;
        if (!java.nio.file.Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            int n = 0;
            for (String line : utf8Lines(java.nio.file.Files.readAllBytes(f))) {
                String[] p = line.trim().split(" ");
                if (p.length != 3 || !p[1].startsWith(DETAIL_PREFIX) || p[2].isEmpty()) {
                    continue;
                }
                try {
                    unsaved(Long.parseLong(p[0])).putIfAbsent(p[1], p[2]);
                    n++;
                } catch (NumberFormatException e) {
                    // not a record line
                }
            }
            if (n > 0) {
                LOG.warn("Lightbits: read {} pending revocation(s) the database had refused from {}; retrying them", n, f);
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOG.error("Lightbits: could not read the pending revocations kept in {}: {}", f, e.getMessage());
        }
    }

    /** The lines of {@code bytes} decoded as UTF-8; a line that is not valid UTF-8 is skipped, the others are kept. */
    static List<String> utf8Lines(byte[] bytes) {
        List<String> lines = new java.util.ArrayList<>();
        int start = 0;
        for (int i = 0; i <= bytes.length; i++) {
            if (i == bytes.length || bytes[i] == '\n') {
                try {
                    lines.add(StandardCharsets.UTF_8.newDecoder()
                            .decode(java.nio.ByteBuffer.wrap(bytes, start, i - start)).toString());
                } catch (java.nio.charset.CharacterCodingException e) {
                    // a damaged line: skipped
                }
                start = i + 1;
            }
        }
        return lines;
    }

    /** Write the records kept in memory to the local copy (or remove it when none is left). */
    private static synchronized boolean writeUnsaved() {
        java.nio.file.Path f = fallbackFile;
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Long, Map<String, String>> pool : UNSAVED.entrySet()) {
            for (Map.Entry<String, String> r : pool.getValue().entrySet()) {
                b.append(pool.getKey()).append(' ').append(r.getKey()).append(' ').append(r.getValue()).append('\n');
            }
        }
        try {
            if (b.length() == 0) {
                java.nio.file.Files.deleteIfExists(f);
                return true;
            }
            java.nio.file.Path dir = f.getParent();
            java.nio.file.Files.createDirectories(dir,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile(dir, "pending-revocations", ".tmp",
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            java.nio.file.Files.write(tmp, b.toString().getBytes(StandardCharsets.UTF_8));
            java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (java.io.IOException | RuntimeException e) {
            LOG.error("Lightbits: could not write the pending revocations to {}: {}", f, e.getMessage());
            return false;
        }
    }

    /** Test hook: forget what this process holds, as a restart would (the local copy stays). */
    static void forgetUnsavedForTest() {
        UNSAVED.clear();
        LOADED.set(false);
    }

    /** Records whose save failed (record key to NQN), per pool; lost at a management server restart. */
    private static final Map<Long, Map<String, String>> UNSAVED = new java.util.concurrent.ConcurrentHashMap<>();

    private static Map<String, String> unsaved(long poolId) {
        return UNSAVED.computeIfAbsent(poolId, k -> new java.util.concurrent.ConcurrentHashMap<>());
    }

    /** Every record of the pool, saved or kept in memory (record key to NQN); in memory only if the read fails. */
    static Map<String, String> records(StoragePoolDetailsDao dao, long poolId) {
        loadUnsaved();
        // a record kept in memory is saved now, under its own key, if the database takes it
        boolean saved = false;
        for (Map.Entry<String, String> e : new java.util.HashMap<>(unsaved(poolId)).entrySet()) {
            try {
                dao.addDetail(poolId, e.getKey(), e.getValue(), false);
                saved |= unsaved(poolId).remove(e.getKey(), e.getValue());
            } catch (RuntimeException ex) {
                // stays in memory (and in the local copy) for the next pass
            }
        }
        if (saved) {
            writeUnsaved();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>(unsaved(poolId));
        Map<String, String> details;
        try {
            details = dao.listDetailsKeyPairs(poolId);
        } catch (RuntimeException e) {
            LOG.warn("Lightbits: could not read the pending revocations of pool {} ({}); retrying the {} kept in memory", poolId,
                    e.getMessage(), out.size());
            return out;
        }
        if (details != null) {
            for (Map.Entry<String, String> e : details.entrySet()) {
                if (e.getKey() != null && e.getKey().startsWith(DETAIL_PREFIX) && e.getValue() != null && !e.getValue().isBlank()) {
                    out.put(e.getKey(), nqnOf(e.getValue()));
                }
            }
        }
        return out;
    }

    /** The NQNs whose revocation on the pool's cluster is pending. */
    public static List<String> pending(StoragePoolDetailsDao dao, long poolId) {
        List<String> out = new ArrayList<>();
        for (String nqn : records(dao, poolId).values()) {
            if (!out.contains(nqn)) {
                out.add(nqn);
            }
        }
        return out;
    }

    /** The keys of the NQN's records as they are now. */
    static java.util.Set<String> recordKeys(StoragePoolDetailsDao dao, long poolId, String nqn) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Map.Entry<String, String> e : records(dao, poolId).entrySet()) {
            if (nqn.equals(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Delete exactly these records (the ones an action looked at); later ones stay. */
    static void delete(StoragePoolDetailsDao dao, long poolId, java.util.Set<String> keys) {
        boolean local = false;
        for (String key : keys) {
            local |= unsaved(poolId).remove(key) != null;
            dao.removeDetail(poolId, key);
        }
        if (local) {
            writeUnsaved();
        }
    }

    /** The NQN of a record's value (records of an earlier build carry a version after it). */
    static String nqnOf(String value) {
        String v = value.trim();
        int sp = v.indexOf(' ');
        return sp < 0 ? v : v.substring(0, sp);
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
     * Revoke the NQN now and delete its pending records when the cluster confirms (the records
     * there before the revocation started; later ones stay); otherwise they stay for a later
     * retry. Returns true when done.
     */
    public static boolean revokeOrKeep(StoragePoolDetailsDao dao, LightbitsApiClient client, LightbitsPoolConfig cfg, long poolId,
                                       String nqn) {
        return revokeOrKeep(dao, client, cfg, poolId, nqn, recordKeys(dao, poolId, nqn));
    }

    private static boolean revokeOrKeep(StoragePoolDetailsDao dao, LightbitsApiClient client, LightbitsPoolConfig cfg, long poolId,
                                        String nqn, java.util.Set<String> observed) {
        try {
            if (revoke(client, cfg, poolId, nqn)) {
                delete(dao, poolId, observed);
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
        // One pass waits at most RETRY_BUDGET_SECONDS for busy locks in all (the statistics poll
        // and host connects run it); the revocation work itself does not count. The pass starts
        // one record further each time, so a record that is slow or busy does not keep the ones
        // after it waiting pass after pass.
        int start = Math.floorMod(PASS.merge(poolId, 1, Integer::sum), pending.size());
        long waitedMillis = 0;
        for (int i = 0; i < pending.size(); i++) {
            String nqn = pending.get((start + i) % pending.size());
            long left = RETRY_BUDGET_SECONDS - waitedMillis / 1000L;
            if (left <= 0) {
                LOG.info("Lightbits: pending revocations on pool {} left for the next retry (this pass waited {} s for locks)",
                        poolId, RETRY_BUDGET_SECONDS);
                return;
            }
            long asked = System.currentTimeMillis();
            long[] started = {0};
            // Under the NQN's lock, which a host takes to record this NQN as its own: a host added
            // back with it either is seen here (and the record dropped) or records it after the
            // revocation is done, so its own registration and grants come after and stay.
            boolean ran = locker.run(nqnLockName(nqn), (int) Math.min(NQN_LOCK_SECONDS, left), () -> {
                started[0] = System.currentTimeMillis();
                retryOne(poolDetails, hostDetails, nqnDetail, poolId, cfg, client, nqn);
            });
            waitedMillis += (ran ? started[0] : System.currentTimeMillis()) - asked;
            if (!ran) {
                LOG.info("Lightbits: the lock of host NQN {} was busy; its pending revocation on pool {} is retried later", nqn, poolId);
            }
        }
    }

    /** Pass counter per pool, for the rotating start. */
    private static final Map<Long, Integer> PASS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Seconds one retry pass of a pool may spend waiting for NQN locks, all records together. */
    static final int RETRY_BUDGET_SECONDS = 60;

    /** Seconds a revocation retry or a host recording its NQN waits for the NQN's lock. */
    public static final int NQN_LOCK_SECONDS = 60;

    /** The lock a revocation retry and a host recording this NQN as its own share. */
    public static String nqnLockName(String nqn) {
        return "lightbits.nqn." + detailKey(nqn).substring(DETAIL_PREFIX.length());
    }

    private static void retryOne(StoragePoolDetailsDao poolDetails, HostDetailsDao hostDetails, String nqnDetail, long poolId,
                                 LightbitsPoolConfig cfg, LightbitsApiClient client, String nqn) {
        java.util.Set<String> observed = recordKeys(poolDetails, poolId, nqn);   // the records this retry acts on
        LightbitsHostGate.RemovingCheck removing = LightbitsHostGate.removingCheck(hostDetails, nqnDetail);
        for (DetailVO d : hostDetails.findByName(nqnDetail)) {
            if (d.getValue() != null && nqn.equals(d.getValue().trim()) && !removing.isRemoving(d.getHostId())) {
                delete(poolDetails, poolId, observed);
                LOG.info("Lightbits: host NQN {} belongs to a CloudStack host again; dropped its pending revocation on pool {}",
                        nqn, poolId);
                return;
            }
        }
        if (revokeOrKeep(poolDetails, client, cfg, poolId, nqn, observed)) {
            LOG.info("Lightbits: completed the pending revocation of removed host {} on pool {}", nqn, poolId);
        }
    }
}
