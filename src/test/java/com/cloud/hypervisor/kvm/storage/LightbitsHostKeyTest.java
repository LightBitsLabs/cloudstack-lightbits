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
    void reusedFilesWithAWiderModeAreMadeRootOnlyKeptAndFlaggedForRotation() throws IOException {
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
        assertTrue(key.keysPossiblyExposed(), "keys that were readable by others are flagged for rotation");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(key.rotationMarker())));
        assertEquals(KNOWN, key.ensure(), "still kept on later calls (a live host keeps working)");
        assertTrue(key.keysPossiblyExposed(), "and still flagged");
        key.deleteKeys();
        assertNotEquals(KNOWN, key.ensure(), "a rotation replaces them");
        assertFalse(key.keysPossiblyExposed(), "new keys are not flagged");
    }

    @Test
    void ownerOnlyModesAreTightenedWithoutAFlag() throws IOException {
        Path dir = Files.createDirectories(nvme.resolve("lightbits"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(key.keyFile(), KNOWN + "\n");
        Files.setPosixFilePermissions(key.keyFile(), PosixFilePermissions.fromString("rwx------"));

        assertEquals(KNOWN, key.ensure());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(key.keyFile())));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        assertFalse(key.keysPossiblyExposed(), "a key only root could read, in a traversable directory, is not exposed");
    }

    @Test
    void freshKeysAreNotFlagged() {
        key.ensure();
        key.ensureCtrlKey();
        key.ensure();
        assertFalse(key.keysPossiblyExposed());
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
    void theTwoWayTargetsCarryBothKeysForEveryPortAndSkipOddValues() throws IOException {
        java.nio.file.Path leftover = key.twoWayConfigFile(POOL);
        key.ensure();
        Files.writeString(leftover, "[]\n");   // a per-pool file an earlier version wrote
        List<LightbitsNvmeHost.Target> t = key.twoWayTargets(POOL, List.of(
                new LightbitsHostKey.Port(SUB, "10.0.0.1", "4420"),
                new LightbitsHostKey.Port(SUB, "10.0.0.2", "4420"),
                new LightbitsHostKey.Port(SUB, "10.0.0.3,hostnqn=x", "4420"),
                new LightbitsHostKey.Port("not-an-nqn", "10.0.0.4", "4420")));
        String k = key.ensure();
        String c = key.ensureCtrlKey();
        assertEquals(2, t.size(), "the port with an odd address and the one with no NQN are left out");
        String o = t.get(1).options;
        assertTrue(o.startsWith("transport=tcp,traddr=10.0.0.2,trsvcid=4420,nqn=" + SUB + ",hostnqn=" + NQN + ",hostid="), "addressing");
        assertTrue(o.endsWith(",dhchap_secret=" + k + ",dhchap_ctrl_secret=" + c), "both keys");
        assertEquals(SUB + " 10.0.0.1:4420", t.get(0).port());
        assertFalse(Files.exists(leftover), "the earlier per-pool file, which holds the keys, is removed");
        assertThrows(CloudRuntimeException.class, () -> key.twoWayTargets("../etc", List.of()), "the pool UUID is checked");
    }

    @Test
    void anUntrustedTwoWayConfigRotatesEveryKey() throws IOException {
        String k = key.ensure();
        String c = key.ensureCtrlKey();
        java.nio.file.Path f = key.twoWayConfigFile(POOL);   // a per-pool file an earlier version wrote
        java.nio.file.Path planted = Files.writeString(tmp.resolve("planted.json"), "[]\n");
        Files.writeString(f, "[]\n");
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
        Files.writeString(key.twoWayConfigFile(POOL), "[]\n");   // a per-pool file an earlier version wrote
        key.deleteKeys();
        try (var files = Files.list(nvme.resolve("lightbits"))) {
            assertEquals(0, files.count(), "no key or configuration file is left");
        }
        assertNotEquals(k, key.ensure());
        assertNotEquals(c, key.ensureCtrlKey());
    }

    private static final String SUB_B = "nqn.2016-01.com.lightbitslabs:uuid:0b0b0b0b-1111-2222-3333-444444444444";

    @Test
    void eachClusterStartsWithTheHostWideKeyAndGetsItsOwnAtItsRotation() throws IOException {
        String hostWide = key.ensure();
        String hostWideCtrl = key.ensureCtrlKey();
        LightbitsHostKey a = key.forScope(SUB);
        LightbitsHostKey b = key.forScope(SUB_B);
        assertEquals(hostWide, a.ensure(), "copied: the host's existing paths hold it");
        assertEquals(hostWide, b.ensure());
        assertEquals(hostWideCtrl, a.ensureCtrlKey());
        assertNotEquals(key.keyFile(), a.keyFile());
        assertNotEquals(a.keyFile(), b.keyFile());
        assertTrue(a.configFile().getFileName().toString().startsWith("auth-"));

        a.deleteKeys();   // cluster A rotates
        String newA = a.ensure();
        assertNotEquals(hostWide, newA, "after its rotation cluster A has its own key, not a copy");
        assertNotEquals(hostWideCtrl, a.ensureCtrlKey());
        assertEquals(hostWide, b.ensure(), "cluster B keeps its key");
        assertEquals(hostWide, key.ensure(), "and so do the host-wide keys");
        a.deleteKeys();
        assertNotEquals(newA, a.ensure(), "a second rotation makes another new key, never the host-wide one again");
        assertNotEquals(hostWide, a.ensure());
        assertTrue(key.forScope(null) == key && key.forScope(" ") == key);
        assertThrows(CloudRuntimeException.class, () -> key.forScope("../x"));
    }

    @Test
    void anOperatorsOwnFileInTheDirectoryIsNeitherCheckedNorDeleted() throws IOException {
        String k = key.ensure();
        Path backup = Files.writeString(key.keyFile().getParent().resolve("notes.txt"), "mine");
        Path linked = key.keyFile().getParent().resolve("old-backup.json");
        Files.createSymbolicLink(linked, backup);
        assertEquals(k, key.ensure(), "an unmanaged link does not make the keys untrusted");
        assertTrue(Files.exists(backup) && Files.isSymbolicLink(linked), "and is left alone");
    }

    @Test
    void aClusterThatRotatedKeepsItsOwnKeysAfterAnUntrustedFileCleanup() throws IOException {
        String hostWide = key.ensure();
        LightbitsHostKey a = key.forScope(SUB);
        a.ensure();
        a.deleteKeys();   // cluster A rotated: its keys are its own from now on
        a.ensure();
        Path elsewhere = Files.writeString(tmp.resolve("planted.json"), LightbitsHostKey.config(NQN, HOST_ID, KNOWN));
        Files.delete(key.configFile());
        Files.createSymbolicLink(key.configFile(), elsewhere);
        String newHostWide = key.ensure();   // the cleanup replaces every key, host-wide ones made again first
        assertNotEquals(hostWide, newHostWide);
        assertNotEquals(newHostWide, a.ensure(), "cluster A makes new keys of its own, not a copy of the new host-wide ones");
    }

    @Test
    void aCleanupMarksOnlyClustersThatHadFiles() throws IOException {
        key.ensure();
        key.ensureCtrlKey();
        LightbitsHostKey a = key.forScope(SUB);   // never used: no files of its own
        Path elsewhere = Files.writeString(tmp.resolve("planted.json"), LightbitsHostKey.config(NQN, HOST_ID, KNOWN));
        Files.delete(a.configFile().getParent().resolve("auth.json"));
        Files.createSymbolicLink(a.configFile().getParent().resolve("auth.json"), elsewhere);
        a.ensure();   // the cleanup runs from the unused cluster's own request
        assertFalse(Files.exists(a.ownMarker()), "a cluster that had no files is not marked: it keeps its first seeding");
    }

    @Test
    void anExposureMarkCoversEveryClustersKeys() throws IOException {
        key.ensure();
        LightbitsHostKey a = key.forScope(SUB);
        LightbitsHostKey b = key.forScope(SUB_B);
        a.ensure();
        b.ensure();
        Files.setPosixFilePermissions(b.keyFile(), PosixFilePermissions.fromString("rw-r--r--"));
        a.ensure();   // any use of the keys checks the whole directory
        assertTrue(a.keysPossiblyExposed() && b.keysPossiblyExposed() && key.keysPossiblyExposed());
        a.deleteKeys();
        assertFalse(a.keysPossiblyExposed(), "cleared for the cluster that rotated");
        assertTrue(b.keysPossiblyExposed(), "still set for the others");
    }

    @Test
    void aClusterSeededFromExposedHostWideKeysIsFlaggedToo() throws IOException {
        Path dir = Files.createDirectories(nvme.resolve("lightbits"));
        Files.writeString(key.keyFile(), KNOWN + "\n");
        Files.setPosixFilePermissions(key.keyFile(), PosixFilePermissions.fromString("rw-r--r--"));
        key.ensure();
        assertTrue(key.keysPossiblyExposed());
        LightbitsHostKey a = key.forScope(SUB);
        assertEquals(KNOWN, a.ensure());
        assertTrue(a.keysPossiblyExposed(), "a copy of a possibly copied key is flagged");
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    }
}
