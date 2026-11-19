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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LightbitsPoolSecretTest {
    private static final long POOL = 2L;
    private static final String OLD = "eyJhbGciOiJSUzI1NiJ9." + "a".repeat(300) + ".sig-old";
    private static final String NEW = "eyJhbGciOiJSUzI1NiJ9." + "b".repeat(500) + ".sig-new";
    private static final UnaryOperator<String> ENC = v -> "enc(" + v + ")";
    private static final UnaryOperator<String> DEC = v -> v.substring(4, v.length() - 1);

    /** storage_pool_details of one pool; only the calls the plugin makes are implemented. */
    private final Map<String, String> rows = new HashMap<>();
    private StoragePoolDetailsDao dao;

    @BeforeEach
    void setUp() {
        dao = (StoragePoolDetailsDao) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {StoragePoolDetailsDao.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "listDetailsKeyPairs": return new HashMap<>(rows);
                        case "removeDetail": rows.remove((String) args[1]); return null;
                        case "addDetail": rows.put((String) args[1], (String) args[2]); return null;
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        rows.put("apiHost", "10.0.0.1");
        rows.put("project", "proj");
        rows.put("environment", "cs1");
        LightbitsPoolConfig.putChunked(rows, "jwt", ENC.apply(OLD));
    }

    private void assertNoClearTextToken() {
        for (Map.Entry<String, String> e : rows.entrySet()) {
            assertFalse(e.getValue().contains("sig-new") && !e.getValue().startsWith("enc(") && !e.getKey().startsWith("jwt."),
                    "clear-text token left in detail " + e.getKey());
            assertFalse(e.getValue().startsWith("eyJ"), "clear-text token left in detail " + e.getKey());
        }
    }

    @Test
    void aStoredTokenIsReadBack() {
        assertEquals(OLD, LightbitsPoolSecret.load(dao, POOL, ENC, DEC).getJwt());
    }

    @Test
    void aTokenSetWithUpdateStoragePoolIsEncryptedAndTheClearTextRowRemoved() {
        rows.put("secretkey", NEW);   // what CloudStack's updateStoragePool leaves behind
        assertEquals(NEW, LightbitsPoolSecret.load(dao, POOL, ENC, DEC).getJwt());
        assertFalse(rows.containsKey("secretkey"));
        assertNoClearTextToken();
        // and it stays readable on the next call
        assertEquals(NEW, LightbitsPoolSecret.load(dao, POOL, ENC, DEC).getJwt());
    }

    @Test
    void aTokenSetUnderTheOldJwtNameIsAbsorbedToo() {
        // details[0].jwt=<token> overwrites the chunk counter with the clear-text token
        rows.put("jwt", NEW);
        assertEquals(NEW, LightbitsPoolSecret.load(dao, POOL, ENC, DEC).getJwt());
        assertTrue(rows.get("jwt").matches("[0-9]+"));
        assertNoClearTextToken();
    }

    @Test
    void aShorterTokenLeavesNoChunkOfTheLongerOneBehind() {
        rows.put("secretkey", NEW);
        LightbitsPoolSecret.load(dao, POOL, ENC, DEC);
        int chunksOfNew = Integer.parseInt(rows.get("jwt"));
        rows.put("secretkey", OLD);
        assertEquals(OLD, LightbitsPoolSecret.load(dao, POOL, ENC, DEC).getJwt());
        int chunksOfOld = Integer.parseInt(rows.get("jwt"));
        assertTrue(chunksOfOld < chunksOfNew);
        assertFalse(rows.containsKey("jwt." + chunksOfOld), "stale chunk of the previous token");
    }

    @Test
    void aValueThatIsNotATokenIsRejectedRemovedAndThePreviousTokenKept() {
        rows.put("secretkey", "not a token");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LightbitsPoolSecret.load(dao, POOL, ENC, DEC));
        assertTrue(e.getMessage().contains("secretkey"), e.getMessage());
        assertFalse(e.getMessage().contains("not a token"), "the rejected value must not be echoed");
        assertFalse(rows.containsKey("secretkey"));
        assertEquals(OLD, LightbitsPoolSecret.load(dao, POOL, ENC, DEC).getJwt());
    }

    @Test
    void aPoolWithoutATokenSaysHowToSetOne() {
        rows.keySet().removeIf(k -> k.startsWith("jwt"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LightbitsPoolSecret.load(dao, POOL, ENC, DEC));
        assertTrue(e.getMessage().contains("details[0].secretkey"), e.getMessage());
    }

    @Test
    void registrationTakesTheTokenFromSecretkeyAndStillAcceptsJwt() {
        assertEquals(NEW, LightbitsPoolSecret.tokenFromInput(Map.of("secretkey", " " + NEW + " ")));
        assertEquals(OLD, LightbitsPoolSecret.tokenFromInput(Map.of("jwt", OLD)));
        assertEquals(NEW, LightbitsPoolSecret.tokenFromInput(Map.of("secretkey", NEW, "jwt", OLD)));
        assertNull(LightbitsPoolSecret.tokenFromInput(Map.of("other", "x")));
        assertNull(LightbitsPoolSecret.tokenFromInput(null));
    }
}
