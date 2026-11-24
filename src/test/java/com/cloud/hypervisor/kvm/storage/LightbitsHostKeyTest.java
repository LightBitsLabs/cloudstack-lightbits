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
package com.cloud.hypervisor.kvm.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.cloud.utils.exception.CloudRuntimeException;

class LightbitsHostKeyTest {
    private static final String NQN = "nqn.2014-08.org.nvmexpress:uuid:60c10b61-c740-478c-a800-402344d475c9";
    private static final String HOST_ID = "9023091c-0b2d-44e6-830c-2534623be8a0";
    /** Key bytes 0..31 in the format nvme gen-dhchap-key writes (CRC-32 appended little-endian). */
    private static final String KNOWN = "DHHC-1:00:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh+KfiaR:";

    @TempDir
    Path tmp;
    private Path nvme;
    private LightbitsHostKey key;

    @BeforeEach
    void setUp() throws IOException {
        nvme = Files.createDirectories(tmp.resolve("nvme"));
        Files.writeString(nvme.resolve("hostnqn"), NQN + "\n");
        Files.writeString(nvme.resolve("hostid"), HOST_ID + "\n");
        key = new LightbitsHostKey(nvme.resolve("lightbits"), nvme);
    }

    @Test
    void keysHaveTheFormatOfNvmeCliAndTheirCrcIsChecked() {
        assertTrue(LightbitsHostKey.valid(KNOWN));
        String made = LightbitsHostKey.newKey(new SecureRandom());
        assertTrue(LightbitsHostKey.valid(made), made.substring(0, 10));
        assertTrue(made.matches("DHHC-1:00:[A-Za-z0-9+/]{48}:"));
        assertNotEquals(made, LightbitsHostKey.newKey(new SecureRandom()));
        assertFalse(LightbitsHostKey.valid(KNOWN.replace("AAEC", "AAED")), "a changed key byte fails the CRC");
        assertFalse(LightbitsHostKey.valid("DHHC-1:00:AAECAwQF:"));
        assertFalse(LightbitsHostKey.valid("secret"));
        assertFalse(LightbitsHostKey.valid(null));
    }

    @Test
    void theKeyAndTheConfigFileAreMadeRootOnlyAndKept() throws IOException {
        String k = key.ensure();
        assertTrue(LightbitsHostKey.valid(k));
        Path dir = nvme.resolve("lightbits");
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(key.keyFile())));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(key.configFile())));
        assertEquals(k, Files.readString(key.keyFile()).trim());
        assertEquals("[{\"hostnqn\":\"" + NQN + "\",\"hostid\":\"" + HOST_ID + "\",\"dhchap_key\":\"" + k + "\"}]\n",
                Files.readString(key.configFile()));
        try (var s = Files.list(dir)) {
            assertEquals(2, s.count(), "no temporary file is left behind");
        }

        assertEquals(k, key.ensure(), "the key is kept");
        assertEquals(key.configFile(), key.ensureConfig());
    }

    @Test
    void aKeyPutThereByHandIsUsedAndAnInvalidOneIsReplaced() throws IOException {
        Path dir = Files.createDirectories(nvme.resolve("lightbits"));
        Files.writeString(dir.resolve("dhchap-key"), KNOWN + "\n");
        assertEquals(KNOWN, key.ensure());

        Files.writeString(dir.resolve("dhchap-key"), "not a key\n");
        String k = key.ensure();
        assertNotEquals("not a key", k);
        assertTrue(LightbitsHostKey.valid(k));
    }

    @Test
    void theConfigFollowsAChangedHostNqn() throws IOException {
        key.ensure();
        String other = "nqn.2014-08.org.nvmexpress:uuid:11111111-2222-3333-4444-555555555555";
        Files.writeString(nvme.resolve("hostnqn"), other + "\n");
        key.ensure();
        assertTrue(Files.readString(key.configFile()).contains(other));
    }

    @Test
    void theHostIdComesFromTheFileOrTheNqnUuid() throws IOException {
        assertEquals(HOST_ID, LightbitsHostKey.hostId(NQN, HOST_ID));
        assertEquals("60c10b61-c740-478c-a800-402344d475c9", LightbitsHostKey.hostId(NQN, null));
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class,
                () -> LightbitsHostKey.hostId("nqn.2014-08.com.example:host-a", null));
        assertTrue(e.getMessage().contains("/etc/nvme/hostid"));

        Files.delete(nvme.resolve("hostid"));
        key.ensure();
        assertTrue(Files.readString(key.configFile()).contains("\"hostid\":\"60c10b61-c740-478c-a800-402344d475c9\""));
    }

    @Test
    void aMissingHostNqnFailsWithTheReason() throws IOException {
        Files.delete(nvme.resolve("hostnqn"));
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, key::ensure);
        assertTrue(e.getMessage().contains("hostnqn"));
    }
}
