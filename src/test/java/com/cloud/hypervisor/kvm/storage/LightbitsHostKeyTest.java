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
import java.util.List;

import org.junit.jupiter.api.Assumptions;
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
    private int uid;

    @BeforeEach
    void setUp() throws IOException {
        nvme = Files.createDirectories(tmp.resolve("nvme"));
        Files.writeString(nvme.resolve("hostnqn"), NQN + "\n");
        Files.writeString(nvme.resolve("hostid"), HOST_ID + "\n");
        uid = (Integer) Files.getAttribute(tmp, "unix:uid");
        key = new LightbitsHostKey(nvme.resolve("lightbits"), nvme, uid);
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
    void reusedFilesWithAWiderModeAreMadeRootOnlyAndKept() throws IOException {
        Path dir = Files.createDirectories(nvme.resolve("lightbits"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(key.keyFile(), KNOWN + "\n");
        Files.setPosixFilePermissions(key.keyFile(), PosixFilePermissions.fromString("rw-r--r--"));
        Files.writeString(key.configFile(), LightbitsHostKey.config(NQN, HOST_ID, KNOWN));
        Files.setPosixFilePermissions(key.configFile(), PosixFilePermissions.fromString("rw-rw-rw-"));

        assertEquals(KNOWN, key.ensure(), "a valid key is kept");
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(key.keyFile())));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(key.configFile())));
        assertEquals(LightbitsHostKey.config(NQN, HOST_ID, KNOWN), Files.readString(key.configFile()));
    }

    @Test
    void aDirectoryOwnedByAnotherUserIsRefused() throws IOException {
        Files.createDirectories(nvme.resolve("lightbits"));
        Files.writeString(key.keyFile(), KNOWN + "\n");
        LightbitsHostKey other = new LightbitsHostKey(nvme.resolve("lightbits"), nvme, uid + 4242);
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, other::ensure);
        assertTrue(e.getMessage().contains("not root"), e.getMessage());
        assertEquals(KNOWN, Files.readString(key.keyFile()).trim(), "nothing is read or changed");
    }

    @Test
    void aKeyFileOwnedByAnotherUserOrALinkIsNotReused() throws IOException {
        Path dir = Files.createDirectories(nvme.resolve("lightbits"));
        Path elsewhere = Files.writeString(tmp.resolve("planted"), KNOWN + "\n");
        Files.createSymbolicLink(key.keyFile(), elsewhere);
        String k = key.ensure();
        assertNotEquals(KNOWN, k, "a linked key is not reused");
        assertFalse(Files.isSymbolicLink(key.keyFile()));
        assertEquals(KNOWN, Files.readString(elsewhere).trim(), "the link target is not written");

        Assumptions.assumeTrue(uid == 0, "changing a file's owner needs root");
        Files.writeString(key.keyFile(), KNOWN + "\n");
        Files.setAttribute(key.keyFile(), "unix:uid", 4242);
        String fresh = key.ensure();
        assertNotEquals(KNOWN, fresh, "a key owned by another user is not reused");
        assertEquals(0, (int) (Integer) Files.getAttribute(key.keyFile(), "unix:uid"));
        assertTrue(Files.readString(key.configFile()).contains(fresh));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    }

    @Test
    void aSymlinkedKeyDirectoryIsRefused() throws IOException {
        Path real = Files.createDirectories(tmp.resolve("elsewhere"));
        Files.writeString(real.resolve("dhchap-key"), KNOWN + "\n");
        Files.createSymbolicLink(nvme.resolve("lightbits"), real);
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, key::ensure);
        assertTrue(e.getMessage().contains("symbolic link"), e.getMessage());
        assertEquals(KNOWN, Files.readString(real.resolve("dhchap-key")).trim(), "nothing is read or changed");
    }

    @Test
    void anUntrustedConfigFileAlsoReplacesTheKey() throws IOException {
        Files.createDirectories(nvme.resolve("lightbits"));
        Files.writeString(key.keyFile(), KNOWN + "\n");
        Path elsewhere = Files.writeString(tmp.resolve("planted.json"), LightbitsHostKey.config(NQN, HOST_ID, KNOWN));
        Files.createSymbolicLink(key.configFile(), elsewhere);
        String k = key.ensure();
        assertNotEquals(KNOWN, k, "the key the untrusted configuration held is not reused");
        assertFalse(Files.isSymbolicLink(key.configFile()));
        assertTrue(Files.readString(key.configFile()).contains(k));
        assertEquals(LightbitsHostKey.config(NQN, HOST_ID, KNOWN), Files.readString(elsewhere), "the link target is not written");
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

    private static final String POOL = "387e455e-bb68-498f-9ef3-04c463815e5d";
    private static final String SUB = "nqn.2016-01.com.lightbitslabs:uuid:9c17511f-50e4-4620-9d0f-dc139863abcf";

    @Test
    void theControllerKeyIsMadeRootOnlyKeptAndDistinct() throws IOException {
        String hostKey = key.ensure();
        String ctrl = key.ensureCtrlKey();
        assertTrue(LightbitsHostKey.valid(ctrl));
        assertNotEquals(hostKey, ctrl);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(key.ctrlKeyFile())));
        assertEquals(ctrl, key.ensureCtrlKey(), "kept");
        assertEquals(hostKey, key.ensure(), "the host key is not touched");
    }

    @Test
    void theTwoWayConfigNamesEveryPortWithBothKeysAndSkipsOddValues() throws IOException {
        java.nio.file.Path f = key.ensureTwoWayConfig(POOL, List.of(
                new LightbitsHostKey.Port(SUB, "10.0.0.1", "4420"),
                new LightbitsHostKey.Port(SUB, "10.0.0.2", "4420"),
                new LightbitsHostKey.Port(SUB, "10.0.0.3\",\"x", "4420"),
                new LightbitsHostKey.Port("not-an-nqn", "10.0.0.4", "4420")));
        String k = key.ensure();
        String c = key.ensureCtrlKey();
        assertEquals(nvme.resolve("lightbits").resolve("auth-" + POOL + ".json"), f);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
        String json = Files.readString(f);
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        com.fasterxml.jackson.databind.JsonNode h = root.get(0);
        assertEquals(NQN, h.get("hostnqn").asText());
        assertEquals(k, h.get("dhchap_key").asText());
        com.fasterxml.jackson.databind.JsonNode ports = h.get("subsystems").get(0).get("ports");
        assertEquals(SUB, h.get("subsystems").get(0).get("nqn").asText());
        assertEquals(2, ports.size(), "the port with an odd address and the one with no NQN are left out");
        assertEquals("10.0.0.2", ports.get(1).get("traddr").asText());
        assertEquals(k, ports.get(0).get("dhchap_key").asText());
        assertEquals(c, ports.get(0).get("dhchap_ctrl_key").asText());
        assertThrows(CloudRuntimeException.class, () -> key.ensureTwoWayConfig("../etc", List.of()), "the pool UUID is checked");
    }

    @Test
    void anUntrustedTwoWayConfigRotatesEveryKey() throws IOException {
        String k = key.ensure();
        String c = key.ensureCtrlKey();
        java.nio.file.Path f = key.ensureTwoWayConfig(POOL, List.of(new LightbitsHostKey.Port(SUB, "10.0.0.1", "4420")));
        java.nio.file.Path planted = Files.writeString(tmp.resolve("planted.json"), Files.readString(f));
        Files.delete(f);
        Files.createSymbolicLink(f, planted);
        String k2 = key.ensure();
        assertNotEquals(k, k2, "the host key is replaced");
        assertNotEquals(c, key.ensureCtrlKey(), "the controller key is replaced");
        assertFalse(Files.exists(f, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the linked configuration is removed");
    }

    @Test
    void deletingTheKeysMakesNewOnesNextTime() throws IOException {
        String k = key.ensure();
        String c = key.ensureCtrlKey();
        key.ensureTwoWayConfig(POOL, List.of(new LightbitsHostKey.Port(SUB, "10.0.0.1", "4420")));
        key.deleteKeys();
        try (var files = Files.list(nvme.resolve("lightbits"))) {
            assertEquals(0, files.count(), "no key or configuration file is left");
        }
        assertNotEquals(k, key.ensure());
        assertNotEquals(c, key.ensureCtrlKey());
    }
}
