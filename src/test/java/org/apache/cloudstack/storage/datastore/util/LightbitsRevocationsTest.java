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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LightbitsRevocationsTest {
    private final Map<String, String> details = new HashMap<>();
    private boolean failWrites;

    @TempDir
    java.nio.file.Path tmp;

    @BeforeEach
    void localCopyInATempDir() {
        LightbitsRevocations.fallbackFile = tmp.resolve("lightbits").resolve("pending-revocations");
        LightbitsRevocations.forgetUnsavedForTest();
    }

    private StoragePoolDetailsDao dao() {
        return (StoragePoolDetailsDao) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {StoragePoolDetailsDao.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "addDetail":
                            if (failWrites) {
                                throw new IllegalStateException("database down");
                            }
                            details.put((String) a[1], (String) a[2]);
                            return null;
                        case "removeDetail":
                            details.remove((String) a[1]);
                            return null;
                        case "listDetailsKeyPairs":
                            return new HashMap<>(details);
                        default:
                            return null;
                    }
                });
    }

    private void deleteAll(StoragePoolDetailsDao dao, long pool, String nqn) {
        LightbitsRevocations.delete(dao, pool, LightbitsRevocations.recordKeys(dao, pool, nqn));
    }

    @Test
    void aRevocationThatCannotBeSavedIsKeptAndSavedLater() {
        String nqn = "nqn.2014-08.org.nvmexpress:uuid:unsaved-revocation";
        long pool = 9101L;
        StoragePoolDetailsDao dao = dao();
        failWrites = true;
        LightbitsRevocations.record(dao, pool, nqn);
        assertTrue(details.isEmpty());
        assertEquals(List.of(nqn), LightbitsRevocations.pending(dao, pool), "kept in memory while the database refuses it");

        failWrites = false;
        assertEquals(List.of(nqn), LightbitsRevocations.pending(dao, pool), "listed once, not twice");
        assertEquals(1, details.size(), "saved by the next retry pass");
        assertEquals(nqn, details.values().iterator().next());

        deleteAll(dao, pool, nqn);
        assertTrue(LightbitsRevocations.pending(dao, pool).isEmpty());
    }

    @Test
    void anActionDeletesOnlyTheRecordsItLookedAt() {
        String nqn = "nqn.2014-08.org.nvmexpress:uuid:append-only";
        long pool = 9102L;
        StoragePoolDetailsDao dao = dao();
        LightbitsRevocations.record(dao, pool, nqn);
        java.util.Set<String> observed = LightbitsRevocations.recordKeys(dao, pool, nqn);
        LightbitsRevocations.record(dao, pool, nqn);   // a removal writes again, after the retry looked
        LightbitsRevocations.delete(dao, pool, observed);
        assertEquals(List.of(nqn), LightbitsRevocations.pending(dao, pool), "the later record stays");
        deleteAll(dao, pool, nqn);
        assertTrue(LightbitsRevocations.pending(dao, pool).isEmpty());
    }

    @Test
    void aLaterRecordKeptInMemoryStaysToo() {
        String nqn = "nqn.2014-08.org.nvmexpress:uuid:unsaved-later";
        long pool = 9103L;
        StoragePoolDetailsDao dao = dao();
        LightbitsRevocations.record(dao, pool, nqn);
        java.util.Set<String> observed = LightbitsRevocations.recordKeys(dao, pool, nqn);
        failWrites = true;
        LightbitsRevocations.record(dao, pool, nqn);   // a later removal; the database refuses it
        LightbitsRevocations.delete(dao, pool, observed);
        assertEquals(List.of(nqn), LightbitsRevocations.pending(dao, pool), "the later one, kept in memory, stays");
        failWrites = false;
        deleteAll(dao, pool, nqn);
    }

    @Test
    void revocationsKeptInMemoryAreListedWhenTheDetailsCannotBeRead() {
        String nqn = "nqn.2014-08.org.nvmexpress:uuid:unreadable-details";
        long pool = 9104L;
        failWrites = true;
        LightbitsRevocations.record(dao(), pool, nqn);
        StoragePoolDetailsDao broken = (StoragePoolDetailsDao) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {StoragePoolDetailsDao.class}, (p, m, a) -> {
                    throw new IllegalStateException("database down");
                });
        assertEquals(List.of(nqn), LightbitsRevocations.pending(broken, pool));
        failWrites = false;
        deleteAll(dao(), pool, nqn);
    }

    @Test
    void recordsOfAnEarlierBuildReadAsTheirNqn() {
        assertEquals("nqn.x", LightbitsRevocations.nqnOf("nqn.x 18c-1f"));
        assertEquals("nqn.x", LightbitsRevocations.nqnOf("nqn.x"));
    }

    @Test
    void eachPassStartsOneRecordFurther() {
        long pool = 9107L;
        StoragePoolDetailsDao dao = dao();
        LightbitsRevocations.record(dao, pool, "nqn.2014-08.org.nvmexpress:uuid:rotate-a");
        LightbitsRevocations.record(dao, pool, "nqn.2014-08.org.nvmexpress:uuid:rotate-b");
        java.util.List<String> first = new java.util.ArrayList<>();
        LightbitsHostGate.Locker busy = (name, seconds, work) -> {
            first.add(name);
            return false;
        };
        LightbitsRevocations.retry(dao, null, "nvme.hostnqn", pool, null, null, busy);
        String a = first.get(0);
        first.clear();
        LightbitsRevocations.retry(dao, null, "nvme.hostnqn", pool, null, null, busy);
        assertTrue(!first.get(0).equals(a), "the next pass starts with the other record");
        assertEquals(2, first.size(), "every record gets its turn while the budget lasts");
    }

    @Test
    void aRevocationTheDatabaseRefusedSurvivesARestartThroughTheLocalCopy() throws Exception {
        String nqn = "nqn.2014-08.org.nvmexpress:uuid:local-copy";
        long pool = 9201L;
        StoragePoolDetailsDao dao = dao();
        failWrites = true;
        LightbitsRevocations.record(dao, pool, nqn);
        java.nio.file.Path f = LightbitsRevocations.fallbackFile;
        assertTrue(java.nio.file.Files.readString(f).contains(pool + " lightbits.revoke.") && java.nio.file.Files.readString(f).contains(nqn));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(f)));
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(f.getParent())));

        LightbitsRevocations.forgetUnsavedForTest();   // the management server restarts, the database still refuses
        assertEquals(List.of(nqn), LightbitsRevocations.pending(dao, pool), "read back from the local copy");
        assertTrue(details.isEmpty());

        failWrites = false;   // the database takes it at the next pass; the local copy goes
        assertEquals(List.of(nqn), LightbitsRevocations.pending(dao, pool));
        assertEquals(1, details.size());
        assertFalse(java.nio.file.Files.exists(f), "nothing left only locally");
        deleteAll(dao, pool, nqn);
        assertTrue(LightbitsRevocations.pending(dao, pool).isEmpty());
    }

    @Test
    void aConfirmedRevocationKeptOnlyLocallyLeavesTheLocalCopyAndOddLinesAreIgnored() throws Exception {
        String nqn = "nqn.2014-08.org.nvmexpress:uuid:local-confirmed";
        long pool = 9202L;
        java.nio.file.Path f = LightbitsRevocations.fallbackFile;
        java.nio.file.Files.createDirectories(f.getParent());
        java.nio.file.Files.writeString(f, "not a record\n" + pool + " other.key " + nqn + "\nx lightbits.revoke.ab " + nqn + "\n"
                + pool + " lightbits.revoke.0011223344556677.1 " + nqn + "\n");
        StoragePoolDetailsDao dao = dao();
        failWrites = true;
        assertEquals(List.of(nqn), LightbitsRevocations.pending(dao, pool), "only the well-formed record");
        deleteAll(dao, pool, nqn);   // the cluster confirmed it while the database still refuses writes
        assertFalse(java.nio.file.Files.exists(f));
        failWrites = false;
    }
}
