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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    /** Files on the management server, by path. */
    private final Map<String, String> files = new HashMap<>();
    private final UnaryOperator<String> readFile = path -> {
        if (!files.containsKey(path)) {
            throw new IllegalArgumentException("the token file " + path + " cannot be read");
        }
        return files.get(path);
    };

    private LightbitsPoolConfig load() {
        return LightbitsPoolSecret.load(dao, POOL, ENC, DEC, readFile);
    }

    private void assertNoClearTextOrPointer() {
        assertFalse(rows.containsKey("tokenFile"), "pointer left behind");
        assertFalse(rows.containsKey("secretkey"), "clear-text token left behind");
        for (Map.Entry<String, String> e : rows.entrySet()) {
            assertFalse(e.getValue().startsWith("eyJ"), "clear-text token left in detail " + e.getKey());
        }
        assertTrue(rows.get("jwt").matches("[0-9]+"), "chunk counter: " + rows.get("jwt"));
    }

    @Test
    void aStoredTokenIsReadBack() {
        assertEquals(OLD, load().getJwt());
    }

    @Test
    void aTokenIsReplacedFromAFileAndThePointerRemoved() {
        files.put("/etc/cloudstack/management/new.jwt", NEW);
        rows.put("tokenFile", "/etc/cloudstack/management/new.jwt");   // what updateStoragePool leaves behind
        assertEquals(NEW, load().getJwt());
        assertNoClearTextOrPointer();
        files.clear();   // the operator deleted the file: the stored token is all that is needed
        assertEquals(NEW, load().getJwt());
    }

    @Test
    void aShorterTokenLeavesNoChunkOfTheLongerOneBehind() {
        files.put("/t", NEW);
        rows.put("tokenFile", "/t");
        load();
        int chunksOfNew = Integer.parseInt(rows.get("jwt"));
        files.put("/t", OLD);
        rows.put("tokenFile", "/t");
        assertEquals(OLD, load().getJwt());
        int chunksOfOld = Integer.parseInt(rows.get("jwt"));
        assertTrue(chunksOfOld < chunksOfNew);
        assertFalse(rows.containsKey("jwt." + chunksOfOld), "stale chunk of the previous token");
    }

    @Test
    void aMissingTokenFileFailsOnceAndThePreviousTokenStays() {
        rows.put("tokenFile", "/etc/cloudstack/management/missing.jwt");
        assertThrows(IllegalArgumentException.class, this::load);
        assertNoClearTextOrPointer();
        assertEquals(OLD, load().getJwt());
    }

    @Test
    void aFileThatDoesNotHoldATokenIsRejectedWithoutEchoingItsContent() {
        files.put("/t", "root:x:0:0:root:/root:/bin/bash");
        rows.put("tokenFile", "/t");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, this::load);
        assertFalse(e.getMessage().contains("root:x"), "file content must never be echoed: " + e.getMessage());
        assertTrue(e.getMessage().contains("tokenFile"), e.getMessage());
        assertEquals(OLD, load().getJwt());
    }

    @Test
    void aTokenPassedThroughTheApiAndCutAtTheColumnWidthIsRejectedAndRemoved() {
        rows.put("secretkey", NEW.substring(0, 255));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, this::load);
        assertTrue(e.getMessage().contains("255"), e.getMessage());
        assertNoClearTextOrPointer();
        assertEquals(OLD, load().getJwt());
    }

    @Test
    void theOldRotationCommandNoLongerBreaksThePool() {
        // details[0].jwt=<token> overwrites the chunk counter with the first 255 characters
        rows.put("jwt", NEW.substring(0, 255));
        assertThrows(IllegalArgumentException.class, this::load);
        assertNoClearTextOrPointer();
        assertEquals(OLD, load().getJwt());
    }

    @Test
    void aTokenPassedThroughTheApiIsRejectedEvenWhenItLooksLikeAJwt() {
        // a token cut after its second dot still matches the JWT pattern: it must not replace a working one
        rows.put("secretkey", "aaa.bbb.ccc");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, this::load);
        assertTrue(e.getMessage().contains("tokenFile"), e.getMessage());
        assertNoClearTextOrPointer();
        assertEquals(OLD, load().getJwt());
    }

    @Test
    void aJwtLookingValuePassedAsTheJwtDetailIsRejectedAndTheCounterRestored() {
        int chunks = Integer.parseInt(rows.get("jwt"));
        rows.put("jwt", "aaa.bbb.ccc");
        assertThrows(IllegalArgumentException.class, this::load);
        assertNoClearTextOrPointer();
        assertEquals(String.valueOf(chunks), rows.get("jwt"));
        assertEquals(OLD, load().getJwt());
    }

    @Test
    void anOverwrittenCounterIsRestoredEvenWhenTheTokenFileCannotBeRead() {
        int chunks = Integer.parseInt(rows.get("jwt"));
        rows.put("jwt", NEW.substring(0, 255));
        rows.put("tokenFile", "/etc/cloudstack/management/missing.jwt");
        assertThrows(IllegalArgumentException.class, this::load);
        assertEquals(String.valueOf(chunks), rows.get("jwt"));
        assertEquals(OLD, load().getJwt());
    }

    @Test
    void aFileReplacementStillWorksAfterTheCounterWasOverwritten() {
        rows.put("jwt", NEW.substring(0, 255));
        files.put("/t", NEW);
        rows.put("tokenFile", "/t");
        assertEquals(NEW, load().getJwt());
        assertNoClearTextOrPointer();
    }

    @Test
    void aPoolWithoutATokenSaysHowToSetOne() {
        rows.keySet().removeIf(k -> k.startsWith("jwt"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, this::load);
        assertTrue(e.getMessage().contains("tokenFile"), e.getMessage());
    }

    @Test
    void registrationTakesTheTokenFromSecretkeyAndStillAcceptsJwt() {
        assertEquals(NEW, LightbitsPoolSecret.tokenFromInput(Map.of("secretkey", " " + NEW + " ")));
        assertEquals(OLD, LightbitsPoolSecret.tokenFromInput(Map.of("jwt", OLD)));
        assertEquals(NEW, LightbitsPoolSecret.tokenFromInput(Map.of("secretkey", NEW, "jwt", OLD)));
        assertNull(LightbitsPoolSecret.tokenFromInput(Map.of("other", "x")));
        assertNull(LightbitsPoolSecret.tokenFromInput(null));
    }

    // ---- the real file reader ----

    @Test
    void tokenFilesAreReadOnlyBelowTheAllowedDirectory(@TempDir Path root, @TempDir Path elsewhere) throws Exception {
        Path ok = Files.writeString(root.resolve("pool.jwt"), NEW + "\n");
        assertEquals(NEW, LightbitsPoolSecret.readTokenFile(ok.toString(), root.toString()));

        Path outside = Files.writeString(elsewhere.resolve("secret"), "something else");
        assertThrows(IllegalArgumentException.class, () -> LightbitsPoolSecret.readTokenFile(outside.toString(), root.toString()));
        // a link inside the allowed directory that points outside is resolved first
        Path link = Files.createSymbolicLink(root.resolve("link.jwt"), outside);
        assertThrows(IllegalArgumentException.class, () -> LightbitsPoolSecret.readTokenFile(link.toString(), root.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> LightbitsPoolSecret.readTokenFile(root.resolve("..").resolve(elsewhere.getFileName()).resolve("secret").toString(), root.toString()));
        assertThrows(IllegalArgumentException.class, () -> LightbitsPoolSecret.readTokenFile(root.resolve("missing").toString(), root.toString()));
        assertThrows(IllegalArgumentException.class, () -> LightbitsPoolSecret.readTokenFile(root.toString(), root.toString()));
        Path big = Files.writeString(root.resolve("big"), "x".repeat(20000));
        assertThrows(IllegalArgumentException.class, () -> LightbitsPoolSecret.readTokenFile(big.toString(), root.toString()));
    }
}
