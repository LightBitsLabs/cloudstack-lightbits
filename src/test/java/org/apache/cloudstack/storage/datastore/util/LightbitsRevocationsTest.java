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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.junit.jupiter.api.Test;

class LightbitsRevocationsTest {
    private final Map<String, String> details = new HashMap<>();
    private boolean failWrites;

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
        assertEquals(nqn, LightbitsRevocations.nqnOf(details.get(LightbitsRevocations.detailKey(nqn))), "saved by the next retry pass");

        LightbitsRevocations.clear(dao, pool, nqn);
        assertTrue(LightbitsRevocations.pending(dao, pool).isEmpty());
    }

    @Test
    void aRetryDropsOnlyTheRecordVersionItLookedAt() {
        String nqn = "nqn.2014-08.org.nvmexpress:uuid:versioned-revocation";
        long pool = 9102L;
        StoragePoolDetailsDao dao = dao();
        LightbitsRevocations.record(dao, pool, nqn);
        String observed = LightbitsRevocations.recordValue(dao, pool, nqn);
        // a removal writes the record again after the retry looked
        LightbitsRevocations.record(dao, pool, nqn);
        LightbitsRevocations.clearIf(dao, pool, nqn, observed);
        assertEquals(List.of(nqn), LightbitsRevocations.pending(dao, pool), "the newer record stays");
        LightbitsRevocations.clearIf(dao, pool, nqn, LightbitsRevocations.recordValue(dao, pool, nqn));
        assertTrue(LightbitsRevocations.pending(dao, pool).isEmpty(), "the version looked at is dropped");
        assertEquals(nqn, LightbitsRevocations.nqnOf(nqn), "a record from before versions reads as its NQN");
    }

    @Test
    void aNewerRevocationKeptInMemoryIsNotDroppedByARetryThatLookedEarlier() {
        String nqn = "nqn.2014-08.org.nvmexpress:uuid:unsaved-versioned";
        long pool = 9103L;
        StoragePoolDetailsDao dao = dao();
        LightbitsRevocations.record(dao, pool, nqn);
        String observed = LightbitsRevocations.recordValue(dao, pool, nqn);
        failWrites = true;
        LightbitsRevocations.record(dao, pool, nqn);   // a removal writes again; the database refuses it
        LightbitsRevocations.clearIf(dao, pool, nqn, observed);
        assertEquals(List.of(nqn), LightbitsRevocations.pending(dao, pool), "the newer one, kept in memory, stays");
        failWrites = false;
        LightbitsRevocations.clearIf(dao, pool, nqn, LightbitsRevocations.recordValue(dao, pool, nqn));
        assertTrue(LightbitsRevocations.pending(dao, pool).isEmpty());
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
        LightbitsRevocations.clear(dao(), pool, nqn);
    }
}
