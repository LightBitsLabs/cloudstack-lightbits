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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cloud.utils.exception.CloudRuntimeException;

class LightbitsNvmeHostTest {
    private static final String VOL = "9a62657b-b976-43bb-91bc-c76d9a2f3985";
    private static final String OTHER = "11111111-2222-3333-4444-555555555555";
    private static final String NQN = "nqn.2016-01.com.lightbitslabs:uuid:5657b32a-f5d8-4abc-a08e-822f6a222c4b";
    private static final String LINK = "/dev/disk/by-id/nvme-uuid." + VOL;

    /** A host made of a few files and a command log. */
    static class FakeSys implements LightbitsNvmeHost.Sys {
        final Map<String, String> files = new HashMap<>();
        final Map<String, String> links = new HashMap<>();
        final List<String> commands = new ArrayList<>();
        final Map<String, LightbitsNvmeHost.Result> results = new HashMap<>();
        Runnable onRescan = () -> { };
        Runnable onConnect = () -> { };
        boolean disconnectRemoves = true;
        final java.util.Set<String> unreadable = new java.util.HashSet<>();
        int slept;
        /** Options written to /dev/nvme-fabrics, and the error the kernel gives per address. */
        final List<String> fabrics = new ArrayList<>();
        final Map<String, String> fabricsErrors = new HashMap<>();
        final List<Long> fabricsTimeouts = new ArrayList<>();
        long fabricsDelayMillis;
        long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void connectFabrics(String options, long timeoutMillis) {
            fabrics.add(options);
            fabricsTimeouts.add(timeoutMillis);
            nanos += java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(fabricsDelayMillis);
            for (Map.Entry<String, String> e : fabricsErrors.entrySet()) {
                if (options.contains("traddr=" + e.getKey() + ",")) {
                    throw new com.cloud.utils.exception.CloudRuntimeException(e.getValue());
                }
            }
            onConnect.run();
        }

        @Override
        public LightbitsNvmeHost.Result run(long timeoutMillis, String... argv) {
            String cmd = String.join(" ", argv);
            commands.add(cmd);
            if (cmd.startsWith("nvme ns-rescan")) {
                onRescan.run();
            }
            if (cmd.startsWith("nvme connect-all")) {
                onConnect.run();
            }
            if (cmd.startsWith("nvme disconnect -n ") && disconnectRemoves) {
                // like the kernel: a subsystem goes away with its last controller
                String nqn = cmd.substring("nvme disconnect -n ".length());
                List<String> gone = new ArrayList<>();
                for (Map.Entry<String, String> f : files.entrySet()) {
                    if (f.getKey().startsWith("/sys/class/nvme-subsystem/") && f.getKey().endsWith("/subsysnqn")
                            && nqn.equals(f.getValue())) {
                        gone.add(f.getKey().substring(0, f.getKey().length() - "subsysnqn".length()));
                    }
                }
                files.keySet().removeIf(k -> gone.stream().anyMatch(k::startsWith));
            }
            for (Map.Entry<String, LightbitsNvmeHost.Result> e : results.entrySet()) {
                if (cmd.contains(e.getKey())) {
                    return e.getValue();
                }
            }
            return new LightbitsNvmeHost.Result(0, "");
        }

        @Override public boolean exists(String path) { return links.containsKey(path) || files.containsKey(path); }
        @Override public String read(String path) { return unreadable.contains(path) ? null : files.get(path); }
        @Override public String canonical(String path) { return links.get(path); }
        @Override public void sleep(long millis) { slept++; }

        @Override
        public List<String> listOrNull(String dir) {
            return unreadable.contains(dir) ? null : list(dir);
        }

        @Override
        public List<String> list(String dir) {
            TreeSet<String> names = new TreeSet<>();
            for (String f : files.keySet()) {
                if (f.startsWith(dir + "/")) {
                    names.add(f.substring(dir.length() + 1).split("/")[0]);
                }
            }
            return new ArrayList<>(names);
        }

        void controller(String name, String address, String state) {
            files.put("/sys/class/nvme/" + name + "/subsysnqn", NQN);
            files.put("/sys/class/nvme/" + name + "/address", address);
            files.put("/sys/class/nvme/" + name + "/state", state);
        }

        void namespace(String kernelName, String uuid, String nqn) {
            files.put("/sys/block/" + kernelName + "/uuid", uuid);
            files.put("/sys/block/" + kernelName + "/device/subsysnqn", nqn);
            files.put("/sys/block/" + kernelName + "/size", "4194304");
        }
    }

    private FakeSys sys;
    private LightbitsNvmeHost host;

    @BeforeEach
    void setUp() {
        sys = new FakeSys();
        host = new LightbitsNvmeHost(sys);
    }

    private void attached() {
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        sys.namespace("nvme3n2", VOL, NQN);
        sys.links.put(LINK, "/dev/nvme3n2");
    }

    // ---- connect ----

    @Test
    void connectUsesAnArgumentVectorPerEndpoint() {
        host.connect(List.of("10.0.0.1:8009"));
        assertTrue(sys.commands.contains("modprobe nvme-tcp"));
        assertTrue(sys.commands.contains("nvme connect-all -t tcp -a 10.0.0.1 -s 8009"));
    }

    @Test
    void connectFallsBackToTheNextDiscoveryEndpoint() {
        sys.results.put("-a 10.0.0.1 ", new LightbitsNvmeHost.Result(1, "failed to add controller"));
        host.connect(List.of("10.0.0.1:8009", "10.0.0.2:8009"));
        assertTrue(sys.commands.contains("nvme connect-all -t tcp -a 10.0.0.2 -s 8009"));
    }

    @Test
    void connectFailsFastNamingEveryEndpointWhenTheHostHasNoPath() {
        sys.results.put("connect-all", new LightbitsNvmeHost.Result(1, "failed to add controller, error failed to write to nvme-fabrics device"));
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class,
                () -> host.connect(List.of("10.0.0.1:8009", "10.0.0.2:8009")));
        assertTrue(e.getMessage().contains("10.0.0.1:8009") && e.getMessage().contains("10.0.0.2:8009"), e.getMessage());
        assertTrue(e.getMessage().contains("failed to write to nvme-fabrics device"), e.getMessage());
    }

    @Test
    void connectGoesOnWhenDiscoveryIsDownButLivePathsExist() {
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        sys.results.put("connect-all", new LightbitsNvmeHost.Result(1, "refused"));
        host.connect(List.of("10.0.0.1:8009"));
    }

    @Test
    void aMissingKernelModuleIsReported() {
        sys.results.put("modprobe", new LightbitsNvmeHost.Result(1, "Module nvme-tcp not found"));
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> host.connect(List.of("10.0.0.1:8009")));
        assertTrue(e.getMessage().contains("nvme-tcp"), e.getMessage());
    }

    // ---- device identity ----

    @Test
    void aDeviceWhoseUuidMatchesIsAccepted() {
        attached();
        assertEquals(LINK, host.waitForDevice(VOL, 5, null));
        assertEquals("nvme3n2", host.verifyIdentity(VOL));
        assertEquals(4194304L * 512, host.sizeBytes("nvme3n2"));
    }

    @Test
    void aStaleLinkPointingAtAnotherVolumeIsRefused() {
        attached();
        sys.namespace("nvme3n2", OTHER, NQN);   // the namespace id was reused by another volume
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> host.waitForDevice(VOL, 5, null));
        assertTrue(e.getMessage().contains("refusing to hand out the wrong disk"), e.getMessage());
        assertTrue(e.getMessage().contains(OTHER), e.getMessage());
    }

    @Test
    void aLinkThatDoesNotResolveToAnNvmeNamespaceIsRefused() {
        sys.links.put(LINK, "/dev/sda");
        assertThrows(CloudRuntimeException.class, () -> host.verifyIdentity(VOL));
        sys.links.put(LINK, null);
        assertThrows(CloudRuntimeException.class, () -> host.verifyIdentity(VOL));
    }

    @Test
    void aNamespaceOfAForeignSubsystemIsRefused() {
        attached();
        sys.namespace("nvme3n2", VOL, "nqn.2014-08.org.example:other");
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> host.verifyIdentity(VOL));
        assertTrue(e.getMessage().contains("not a Lightbits cluster"), e.getMessage());
    }

    @Test
    void aMissedEventIsRecoveredByARescan() {
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        sys.onRescan = () -> {
            sys.namespace("nvme3n2", VOL, NQN);
            sys.links.put(LINK, "/dev/nvme3n2");
        };
        assertEquals(LINK, host.waitForDevice(VOL, 10, null));
        assertTrue(sys.commands.contains("nvme ns-rescan /dev/nvme3"));
        assertTrue(sys.commands.contains("udevadm settle --timeout=10"));
    }

    @Test
    void aTimeoutOnAConnectedHostPointsAtTheAcl() {
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class,
                () -> host.waitForDevice(VOL, 4, "nqn.2014-08.org.nvmexpress:uuid:host-a"));
        assertTrue(e.getMessage().contains("ACL"), e.getMessage());
        assertTrue(e.getMessage().contains("nqn.2014-08.org.nvmexpress:uuid:host-a"), e.getMessage());
        assertTrue(e.getMessage().contains("nvme3"), e.getMessage());
    }

    @Test
    void aTimeoutOnAHostWithoutPathsPointsAtTheNetwork() {
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> host.waitForDevice(VOL, 2, null));
        assertTrue(e.getMessage().contains("no live path"), e.getMessage());
        assertFalse(e.getMessage().contains("ACL"), e.getMessage());
    }

    // ---- input validation ----

    @Test
    void onlyAVolumeUuidReachesAPathOrACommand() {
        for (String bad : new String[] {null, "", "../../etc/passwd", VOL + ";reboot", "$(reboot)", VOL + "/x"}) {
            assertThrows(CloudRuntimeException.class, () -> host.waitForDevice(bad, 1, null), String.valueOf(bad));
            assertThrows(CloudRuntimeException.class, () -> host.deviceLinkExists(bad), String.valueOf(bad));
        }
        assertTrue(sys.commands.isEmpty());
    }

    @Test
    void discoveryEndpointsAreValidatedAndDefaultToThePoolHost() {
        assertEquals(List.of("10.0.0.1:8009", "10.0.0.2:9009"), LightbitsNvmeHost.discoveryEndpoints("10.0.0.1, 10.0.0.2:9009", "x"));
        assertEquals(List.of("api.service:8009"), LightbitsNvmeHost.discoveryEndpoints(null, "api.service"));
        assertThrows(CloudRuntimeException.class, () -> LightbitsNvmeHost.discoveryEndpoints("10.0.0.1;reboot", "x"));
        assertThrows(CloudRuntimeException.class, () -> LightbitsNvmeHost.discoveryEndpoints("-a", "x"));
    }

    @Test
    void qemuImgRunsAsAnArgumentVectorAndItsFailureIsReported() {
        host.convertToRaw("/mnt/sec/template with space.qcow2", LINK, true, 0);
        assertTrue(sys.commands.contains("qemu-img convert -n -O raw /mnt/sec/template with space.qcow2 " + LINK));
        assertThrows(CloudRuntimeException.class, () -> host.convertToRaw("-o evil", LINK, true, 0));
        sys.results.put("qemu-img", new LightbitsNvmeHost.Result(1, "No space left on device"));
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> host.convertToRaw("/a.qcow2", LINK, false, 0));
        assertTrue(e.getMessage().contains("No space left on device"), e.getMessage());
    }

    // ---- paths ----

    @Test
    void pathsListOnlyLightbitsControllers() {
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        sys.controller("nvme4", "traddr=10.0.0.2,trsvcid=4420", "connecting");
        sys.files.put("/sys/class/nvme/nvme0/subsysnqn", "nqn.2014-08.org.example:local-disk");
        assertEquals(2, host.paths().size());
        assertEquals(1, host.livePaths().size());
        host.warnOnDegradedPaths(3);   // logs; must not throw
    }

    // ---- plugin version ----

    @Test
    void theBuildKnowsItsVersion() {
        String v = org.apache.cloudstack.storage.datastore.util.LightbitsVersion.version();
        assertFalse(v.contains("unknown"), v);
        assertFalse(v.contains("${"), v);
        assertTrue(org.apache.cloudstack.storage.datastore.util.LightbitsVersion.describe().contains("against CloudStack 4."));
    }

    @Test
    void anAgentOnAnotherBuildThanTheManagementServerIsFlagged() {
        assertTrue(LightbitsStorageAdaptor.warnOnVersionMismatch("1.0.0+2027-01-01T00:00:00Z", "0.9.0+2026-11-19T00:00:00Z"));
        assertFalse(LightbitsStorageAdaptor.warnOnVersionMismatch("1.0.0+x", "1.0.0+x"));
        assertFalse(LightbitsStorageAdaptor.warnOnVersionMismatch(null, "1.0.0+x"));   // older management server
    }

    // ---- lookup before connect (the agent builds a VM's disk definitions before it connects them) ----

    /** A cold host: connect-all brings the controller up, and with it the volume's namespace. */
    private void namespaceAppearsOnConnect() {
        sys.results.clear();
        sys.onConnect = () -> attached();
    }

    private static KVMStoragePool pool() {
        return new LightbitsStoragePool("pool-uuid", "10.0.0.1", 443, com.cloud.storage.Storage.StoragePoolType.IscsiLUN, null,
                java.util.Map.of(LightbitsStorageAdaptor.DETAIL_NVME_DISCOVERY, "10.0.0.1:8009,10.0.0.2:8009"));
    }

    @Test
    void aLookupOnAHostWithNoConnectionConnectsItFirst() {
        namespaceAppearsOnConnect();
        LightbitsStorageAdaptor adaptor = new LightbitsStorageAdaptor(host);
        KVMPhysicalDisk disk = adaptor.getPhysicalDisk(VOL, pool());
        assertTrue(disk != null, "the disk must be found by the lookup alone");
        assertEquals(LINK, disk.getPath());
        assertEquals(1, sys.commands.stream().filter(c -> c.startsWith("nvme connect-all")).count());
    }

    @Test
    void lookupsDoNotConnectWhenTheDeviceIsThereAndConnectAtMostOncePerInterval() {
        attached();
        LightbitsStorageAdaptor adaptor = new LightbitsStorageAdaptor(host);
        adaptor.getPhysicalDisk(VOL, pool());
        assertEquals(0, sys.commands.stream().filter(c -> c.startsWith("nvme connect-all")).count());
        // a volume that is not there (not in the ACL yet): polled repeatedly, connected once
        for (int i = 0; i < 5; i++) {
            assertEquals(null, adaptor.getPhysicalDisk(OTHER, pool()));
        }
        assertEquals(1, sys.commands.stream().filter(c -> c.startsWith("nvme connect-all")).count());
    }

    @Test
    void aLookupOnAHostThatCannotConnectReportsWhy() {
        sys.results.put("connect-all", new LightbitsNvmeHost.Result(1, "failed to add controller"));
        LightbitsStorageAdaptor adaptor = new LightbitsStorageAdaptor(host);
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> adaptor.getPhysicalDisk(VOL, pool()));
        assertTrue(e.getMessage().contains("could not connect this host"), e.getMessage());
    }

    // ---- disconnect when the last pool is removed ----

    private static final String SUBSYS = "/sys/class/nvme-subsystem/";

    private void subsystem(String name, String nqn, String... entries) {
        sys.files.put(SUBSYS + name + "/subsysnqn", nqn);
        for (String e : entries) {
            sys.files.put(SUBSYS + name + "/" + e + "/uevent", "");
        }
    }

    @Test
    void anIdleLightbitsSubsystemIsDisconnected() {
        subsystem("nvme-subsys3", NQN, "nvme3", "nvme4", "nvme5");
        assertEquals(List.of(NQN), host.disconnectIdleSubsystems());
        assertTrue(sys.commands.contains("nvme disconnect -n " + NQN));
    }

    @Test
    void aScopedIdleDisconnectLeavesTheOtherClustersSubsystemsAlone() {
        String other = "nqn.2016-01.com.lightbitslabs:uuid:db336dd6-5e4c-4fc4-b7ff-9b74f95f75a2";
        subsystem("nvme-subsys3", NQN, "nvme3");
        subsystem("nvme-subsys4", other, "nvme4");
        assertEquals(List.of(NQN), host.disconnectIdleSubsystems(NQN));
        assertTrue(sys.commands.stream().noneMatch(c -> c.equals("nvme disconnect -n " + other)), sys.commands.toString());
    }

    @Test
    void aSubsystemWithANamespaceIsNeverDisconnected() {
        subsystem("nvme-subsys3", NQN, "nvme3", "nvme3n2", "ng3n2");
        subsystem("nvme-subsys4", NQN + "-b", "nvme6", "nvme6c6n1");
        assertTrue(host.disconnectIdleSubsystems().isEmpty());
        assertTrue(sys.commands.stream().noneMatch(c -> c.startsWith("nvme disconnect")), sys.commands.toString());
    }

    @Test
    void aNamespaceBelowAControllerKeepsTheSubsystemWhenNativeMultipathIsOff() {
        subsystem("nvme-subsys3", NQN, "nvme3", "nvme4", "nvme3/nvme3n1");
        assertTrue(host.disconnectIdleSubsystems().isEmpty());
        assertTrue(sys.commands.stream().noneMatch(c -> c.startsWith("nvme disconnect")), sys.commands.toString());
    }

    @Test
    void controllersWithNoNamespaceBelowThemAreStillIdle() {
        subsystem("nvme-subsys3", NQN, "nvme3", "nvme3/device", "nvme4/state");
        assertEquals(List.of(NQN), host.disconnectIdleSubsystems());
    }

    @Test
    void subsystemsOfOtherVendorsAndOddNqnsAreLeftAlone() {
        subsystem("nvme-subsys0", "nqn.2014-08.org.nvmexpress:uuid:local-disk", "nvme0");
        subsystem("nvme-subsys1", "nqn.2016-01.com.lightbitslabs:uuid:x; rm -rf /", "nvme1");
        assertTrue(host.disconnectIdleSubsystems().isEmpty());
        assertTrue(sys.commands.isEmpty(), sys.commands.toString());
    }

    @Test
    void aFailedDisconnectIsNotReportedAsDone() {
        subsystem("nvme-subsys3", NQN, "nvme3");
        sys.results.put("nvme disconnect", new LightbitsNvmeHost.Result(1, "busy"));
        assertTrue(host.disconnectIdleSubsystems().isEmpty());
    }

    @Test
    void aSubsystemWhoseNamespacesCannotBeReadIsKept() {
        subsystem("nvme-subsys3", NQN, "nvme3", "nvme3n1");              // a namespace is there
        subsystem("nvme-subsys4", NQN + "-b", "nvme4", "nvme4/nvme4n1");  // one below a controller
        subsystem("nvme-subsys5", NQN + "-c", "nvme5");                  // really idle
        sys.unreadable.add(SUBSYS + "nvme-subsys3");
        sys.unreadable.add(SUBSYS + "nvme-subsys4/nvme4");
        assertEquals(List.of(NQN + "-c"), host.disconnectIdleSubsystems());
        assertTrue(sys.commands.stream().noneMatch(c -> c.equals("nvme disconnect -n " + NQN)
                || c.equals("nvme disconnect -n " + NQN + "-b")), sys.commands.toString());
    }

    @Test
    void aDisconnectThatLeavesAControllerIsNotReportedAsDone() {
        // nvme-cli 2.8 returns 0 from 'disconnect -n' when a controller deletion fails
        subsystem("nvme-subsys3", NQN, "nvme3", "nvme4");
        sys.disconnectRemoves = false;
        assertTrue(host.disconnectIdleSubsystems().isEmpty());
        assertTrue(sys.commands.contains("nvme disconnect -n " + NQN));
    }

    @Test
    void removingTheLastPoolDisconnectsAndRemovingOneOfTwoDoesNot() {
        subsystem("nvme-subsys3", NQN, "nvme3");
        LightbitsStorageAdaptor adaptor = new LightbitsStorageAdaptor(host);
        adaptor.createStoragePool("pool-a", "10.0.0.1", 443, null, null, com.cloud.storage.Storage.StoragePoolType.IscsiLUN, null, true);
        adaptor.createStoragePool("pool-b", "10.0.0.1", 443, null, null, com.cloud.storage.Storage.StoragePoolType.IscsiLUN, null, true);
        assertTrue(adaptor.deleteStoragePool("pool-a"));
        assertTrue(adaptor.deleteStoragePool("never-registered"));
        assertTrue(sys.commands.stream().noneMatch(c -> c.startsWith("nvme disconnect")), sys.commands.toString());
        assertTrue(adaptor.deleteStoragePool("pool-b"));
        assertTrue(sys.commands.contains("nvme disconnect -n " + NQN));
    }

    @Test
    void aPoolWithdrawnForMaintenanceDisconnectsToo() {
        // KVMStoragePoolManager calls the (uuid, details) form for ModifyStoragePoolCommand add=false
        subsystem("nvme-subsys3", NQN, "nvme3");
        LightbitsStorageAdaptor adaptor = new LightbitsStorageAdaptor(host);
        adaptor.createStoragePool("pool-a", "10.0.0.1", 443, null, null, com.cloud.storage.Storage.StoragePoolType.IscsiLUN, null, true);
        assertTrue(adaptor.deleteStoragePool("pool-a", new java.util.HashMap<>()));
        assertTrue(sys.commands.contains("nvme disconnect -n " + NQN), sys.commands.toString());
        assertTrue(adaptor.deleteStoragePool("pool-a", null));
    }

    // ---- in-band authentication ----

    @Test
    void aConnectWithAKeyPassesTheConfigFileAndNoKey() {
        host.connect(List.of("10.0.0.1:8009"), 1000, java.nio.file.Path.of("/etc/nvme/lightbits/auth.json"));
        assertTrue(sys.commands.contains("nvme connect-all -t tcp -a 10.0.0.1 -s 8009 --config=/etc/nvme/lightbits/auth.json"),
                sys.commands.toString());
        assertFalse(sys.commands.stream().anyMatch(c -> c.contains("DHHC-1")), "the key never reaches a command line");
    }

    @Test
    void pathsWithoutTheCurrentKeyAreCounted() {
        String current = "DHHC-1:00:current:";
        sys.controller("nvme1", "traddr=10.0.0.1,trsvcid=4420", "live");
        sys.controller("nvme2", "traddr=10.0.0.2,trsvcid=4420", "live");
        sys.controller("nvme3", "traddr=10.0.0.3,trsvcid=4420", "connecting");
        sys.controller("nvme4", "traddr=10.0.0.1,trsvcid=4420", "live");
        sys.files.put("/sys/class/nvme/nvme1/dhchap_secret", "none");
        sys.files.put("/sys/class/nvme/nvme2/dhchap_secret", current + "\n");
        // nvme3: a kernel without the attribute counts as keyless
        // nvme4: a key from before the key file was replaced (rotation, or the file deleted)
        sys.files.put("/sys/class/nvme/nvme4/dhchap_secret", "DHHC-1:00:old:");
        sys.files.put("/sys/class/nvme/nvme9/subsysnqn", "nqn.2014-08.org.example:other");
        assertEquals(3, host.pathsWithoutKey(current));

        sys.unreadable.add("/sys/class/nvme");
        assertThrows(CloudRuntimeException.class, () -> host.pathsWithoutKey(current),
                "an unreadable controller list is an unknown count, not zero");

        sys.unreadable.remove("/sys/class/nvme");
        sys.unreadable.add("/sys/class/nvme/nvme2/subsysnqn");
        assertThrows(CloudRuntimeException.class, () -> host.pathsWithoutKey(current),
                "a listed controller whose NQN cannot be read is an unknown count, not skipped");
        assertEquals(2, host.livePaths().size(), "paths() still skips it for its other callers (nvme1, nvme4)");
    }

    private static final String DISCOVERY_JSON = "Warning: some nvme-cli notice\n{\"genctr\":7,\"records\":["
            + "{\"trtype\":\"tcp\",\"subtype\":\"discovery subsystem referral\",\"traddr\":\"10.0.0.2\",\"trsvcid\":\"8009\","
            + "\"subnqn\":\"nqn.2014-08.org.nvmexpress.discovery\"},"
            + "{\"trtype\":\"tcp\",\"subtype\":\"nvme subsystem\",\"traddr\":\"10.0.0.1\",\"trsvcid\":\"4420\",\"subnqn\":\"" + NQN + "\"},"
            + "{\"trtype\":\"tcp\",\"subtype\":\"nvme subsystem\",\"traddr\":\"10.0.0.2\",\"trsvcid\":\"4420\",\"subnqn\":\"" + NQN + "\"}]}";

    @Test
    void discoveryKeepsTheDataPortsAndDropsReferrals() {
        sys.results.put("nvme discover -t tcp -a 10.0.0.1 -s 8009 -o json", new LightbitsNvmeHost.Result(0, DISCOVERY_JSON));
        List<LightbitsHostKey.Port> ports = host.discover("10.0.0.1:8009", 1000);
        assertEquals(2, ports.size());
        assertEquals("10.0.0.1", ports.get(0).address);
        assertEquals("4420", ports.get(1).service);
        assertEquals(NQN, ports.get(1).subsystemNqn);

        sys.results.put("nvme discover -t tcp -a 10.0.0.9", new LightbitsNvmeHost.Result(1, "failed to connect"));
        assertThrows(CloudRuntimeException.class, () -> host.discover("10.0.0.9:8009", 1000));
        assertThrows(CloudRuntimeException.class, () -> LightbitsNvmeHost.parseDiscovery("no json here"));
    }

    @Test
    void aConfigThatCannotBePreparedCountsAsThatEndpointFailing() {
        java.nio.file.Path cfg = java.nio.file.Path.of("/etc/nvme/lightbits/auth-x.json");
        List<String> asked = new ArrayList<>();
        boolean answered = host.connectWith(List.of("10.0.0.1:8009", "10.0.0.2:8009"), 1000, endpoint -> {
            asked.add(endpoint);
            if (asked.size() == 1) {
                throw new CloudRuntimeException("'nvme discover' failed");
            }
            return cfg;
        });
        assertTrue(answered);
        assertEquals(2, asked.size(), "the second endpoint is tried after the first one's configuration failed");
        List<String> connects = sys.commands.stream().filter(c -> c.startsWith("nvme connect-all")).collect(java.util.stream.Collectors.toList());
        assertEquals(1, connects.size());
        assertTrue(connects.get(0).endsWith("--config=" + cfg), connects.get(0));
        assertTrue(sys.commands.stream().noneMatch(c -> c.contains("DHHC-1")), "no key on any command line");
    }

    private static LightbitsNvmeHost.Target target(String address) {
        return new LightbitsNvmeHost.Target(NQN, address, "4420", "transport=tcp,traddr=" + address + ",trsvcid=4420,nqn=" + NQN
                + ",dhchap_secret=DHHC-1:00:host:,dhchap_ctrl_secret=DHHC-1:00:ctrl:");
    }

    @Test
    void aTwoWayConnectMakesExactlyTheDiscoveredPortsWithoutConnectAll() {
        sys.controller("nvme1", "traddr=10.0.0.1,trsvcid=4420,src_addr=10.0.0.9", "connecting");   // already there, any state
        boolean answered = host.connectDirect(List.of("10.0.0.1:8009"), 1000,
                endpoint -> List.of(target("10.0.0.1"), target("10.0.0.2"), target("10.0.0.3")));
        assertTrue(answered);
        assertEquals(2, sys.fabrics.size(), "one controller per port, none for the port that has a path");
        assertTrue(sys.fabrics.get(0).contains("traddr=10.0.0.2,") && sys.fabrics.get(1).contains("traddr=10.0.0.3,"));
        assertTrue(sys.fabrics.stream().allMatch(o -> o.contains("dhchap_ctrl_secret=DHHC-1:00:ctrl:")), "every port gets the controller key");
        assertTrue(sys.commands.stream().noneMatch(c -> c.startsWith("nvme connect")), "no nvme-cli connect, so no second discovery");
        assertTrue(sys.commands.stream().noneMatch(c -> c.contains("DHHC-1")), "no key on any command line");
        assertFalse(target("10.0.0.2").toString().contains("DHHC-1"), "a target prints without its keys");
    }

    @Test
    void aTwoWayConnectGoesOnWhenSomePortsFailAndTriesTheNextEndpointWhenAllDo() {
        sys.fabricsErrors.put("10.0.0.2", "Operation already in progress");   // made meanwhile: connected
        sys.fabricsErrors.put("10.0.0.3", "Key was rejected by service");
        assertTrue(host.connectDirect(List.of("10.0.0.1:8009"), 1000, endpoint -> List.of(target("10.0.0.2"), target("10.0.0.3"))));

        sys.fabrics.clear();
        sys.fabricsErrors.put("10.0.0.4", "Key was rejected by service");
        // the first endpoint tried rotates: each endpoint behaves the same whatever the order
        java.util.function.Function<String, List<LightbitsNvmeHost.Target>> perEndpoint = endpoint -> {
            if (endpoint.startsWith("10.0.0.1:")) {
                throw new CloudRuntimeException("'nvme discover' failed");
            }
            return endpoint.startsWith("10.0.0.2:") ? List.of(target("10.0.0.4")) : List.of(target("10.0.0.5"));
        };
        for (int i = 0; i < 3; i++) {
            sys.fabrics.clear();
            assertTrue(host.connectDirect(List.of("10.0.0.1:8009", "10.0.0.2:8009", "10.0.0.3:8009"), 1000, perEndpoint),
                    "a failed discovery and an endpoint whose every port failed are both passed over");
            assertTrue(sys.fabrics.get(sys.fabrics.size() - 1).contains("traddr=10.0.0.5,"));
        }
    }

    @Test
    void theDataPortsOfAnEndpointShareItsTimeout() {
        sys.fabricsDelayMillis = 100;
        assertTrue(host.connectDirect(List.of("10.0.0.1:8009"), 150,
                endpoint -> List.of(target("10.0.0.2"), target("10.0.0.3"), target("10.0.0.4"))));
        assertEquals(2, sys.fabrics.size(), "the third port is not tried once the endpoint's time ran out");
        assertTrue(sys.fabricsTimeouts.get(0) <= 150 && sys.fabricsTimeouts.get(1) < sys.fabricsTimeouts.get(0),
                String.valueOf(sys.fabricsTimeouts));
    }

    @Test
    void aTwoWayConnectThatNoEndpointServesFailsWithoutALivePath() {
        sys.fabricsErrors.put("10.0.0.4", "Key was rejected by service");
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> host.connectDirect(List.of("10.0.0.1:8009"), 1000,
                endpoint -> List.of(target("10.0.0.4"))));
        assertTrue(e.getMessage().contains("Key was rejected by service") && !e.getMessage().contains("DHHC-1"), e.getMessage());
        e = assertThrows(CloudRuntimeException.class, () -> host.connectDirect(List.of("10.0.0.1:8009"), 1000, endpoint -> List.of()));
        assertTrue(e.getMessage().contains("lists no data port"), e.getMessage());
        sys.controller("nvme1", "traddr=10.0.0.1,trsvcid=4420", "live");
        assertFalse(host.connectDirect(List.of("10.0.0.1:8009"), 1000, endpoint -> List.of(target("10.0.0.4"))),
                "with a live path the call goes on, reporting that no endpoint answered");
    }

    @Test
    void pathsWithoutTheCurrentControllerKeyAreCounted() {
        String ctrl = "DHHC-1:00:ctrl:";
        sys.controller("nvme1", "traddr=10.0.0.1,trsvcid=4420", "live");
        sys.controller("nvme2", "traddr=10.0.0.2,trsvcid=4420", "live");
        sys.controller("nvme3", "traddr=10.0.0.3,trsvcid=4420", "live");
        sys.files.put("/sys/class/nvme/nvme1/dhchap_ctrl_secret", "none");
        sys.files.put("/sys/class/nvme/nvme2/dhchap_ctrl_secret", ctrl + "\n");
        sys.files.put("/sys/class/nvme/nvme3/dhchap_ctrl_secret", "DHHC-1:00:old:");
        assertEquals(2, host.pathsWithoutCtrlKey(ctrl));
    }

    private LightbitsHostKey keyIn(java.nio.file.Path tmp) throws java.io.IOException {
        java.nio.file.Files.writeString(tmp.resolve("hostnqn"), "nqn.2014-08.org.nvmexpress:uuid:60c10b61-c740-478c-a800-402344d475c9");
        return new LightbitsHostKey(tmp.resolve("lightbits"), tmp, (Integer) java.nio.file.Files.getAttribute(tmp, "unix:uid"));
    }

    @Test
    void anIdleHostRotatesItsKeysAfterDroppingItsIdlePaths(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        LightbitsHostKey key = keyIn(tmp);
        String k = key.ensure();
        String c = key.ensureCtrlKey();
        // a per-pool two-way configuration an earlier version left behind
        java.nio.file.Files.writeString(key.twoWayConfigFile("387e455e-bb68-498f-9ef3-04c463815e5d"), "[]\n");
        subsystem("nvme-subsys3", NQN, "nvme3");
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        // the disconnect removes the controller with its subsystem, as the kernel does
        LightbitsNvmeHost nvme = new LightbitsNvmeHost(new FakeSys() {
            { files.putAll(sys.files); }
            @Override
            public LightbitsNvmeHost.Result run(long t, String... argv) {
                LightbitsNvmeHost.Result r = super.run(t, argv);
                if (String.join(" ", argv).startsWith("nvme disconnect")) {
                    files.keySet().removeIf(f -> f.startsWith("/sys/class/nvme/nvme3/"));
                }
                return r;
            }
        });
        LightbitsStorageAdaptor.rotateInbandKeys(nvme, key);
        assertNotEquals(k, key.ensure(), "a new host key");
        assertNotEquals(c, key.ensureCtrlKey(), "a new controller key");
        assertTrue(java.nio.file.Files.list(tmp.resolve("lightbits")).noneMatch(f -> f.getFileName().toString().startsWith("auth-")),
                "the old two-way configurations are gone");
    }

    @Test
    void aRotationForOneClusterKeepsTheOtherClustersKeysConfigurationAndPaths(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        String other = "nqn.2016-01.com.lightbitslabs:uuid:db336dd6-5e4c-4fc4-b7ff-9b74f95f75a2";
        LightbitsHostKey base = keyIn(tmp);
        LightbitsHostKey a = base.forScope(NQN);
        LightbitsHostKey b = base.forScope(other);
        String ka = a.ensure();
        String kb = b.ensure();
        java.nio.file.Path configB = b.configFile();
        java.nio.file.Path leftover = base.twoWayConfigFile("387e455e-bb68-498f-9ef3-04c463815e5d");
        java.nio.file.Files.writeString(leftover, "[]\n");
        subsystem("nvme-subsys3", NQN, "nvme3");      // cluster A: idle
        subsystem("nvme-subsys4", other, "nvme4");    // cluster B: idle too
        LightbitsStorageAdaptor.rotateInbandKeys(host, a, NQN);
        assertTrue(sys.commands.contains("nvme disconnect -n " + NQN));
        assertTrue(sys.commands.stream().noneMatch(c -> c.equals("nvme disconnect -n " + other)), "cluster B's paths are kept");
        assertNotEquals(ka, a.ensure(), "a new cluster A key");
        assertEquals(kb, b.ensure(), "cluster B's key is kept");
        assertTrue(java.nio.file.Files.exists(configB), "cluster B's configuration is kept");
        assertFalse(java.nio.file.Files.exists(leftover), "a per-pool file of an earlier version is removed");
    }

    @Test
    void aHostWithAVolumeInUseRefusesToRotate(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        LightbitsHostKey key = keyIn(tmp);
        String k = key.ensure();
        subsystem("nvme-subsys3", NQN, "nvme3", "nvme3n2");
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> LightbitsStorageAdaptor.rotateInbandKeys(host, key));
        assertTrue(e.getMessage().contains("maintenance"), e.getMessage());
        assertEquals(k, key.ensure(), "the keys are kept");
        assertTrue(sys.commands.stream().noneMatch(cmd -> cmd.startsWith("nvme disconnect")), "a subsystem in use is not touched");
    }

    @Test
    void anUnknownPathCountRefusesTheRotation(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        LightbitsHostKey key = keyIn(tmp);
        String k = key.ensure();
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        sys.unreadable.add("/sys/class/nvme/nvme3/subsysnqn");
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> LightbitsStorageAdaptor.rotateInbandKeys(host, key));
        assertTrue(e.getMessage().contains("could not be counted"), e.getMessage());
        assertEquals(k, key.ensure(), "the keys are kept");
        sys.unreadable.clear();
        sys.unreadable.add("/sys/class/nvme");
        assertThrows(CloudRuntimeException.class, () -> LightbitsStorageAdaptor.rotateInbandKeys(host, key));
        assertEquals(k, key.ensure());
    }

    @Test
    void newPathsWithoutTheControllerKeyAreDroppedOldOnesKept() {
        String ctrl = "DHHC-1:00:ctrl:";
        sys.controller("nvme1", "traddr=10.0.0.1,trsvcid=4420", "live");   // older one-way path, may carry volumes
        java.util.Set<String> before = host.pathNames();
        sys.controller("nvme2", "traddr=10.0.0.2,trsvcid=4420", "live");
        sys.controller("nvme3", "traddr=10.0.0.3,trsvcid=4420", "live");
        sys.files.put("/sys/class/nvme/nvme2/dhchap_ctrl_secret", ctrl);
        sys.files.put("/sys/class/nvme/nvme3/dhchap_ctrl_secret", "none");
        assertEquals(List.of("nvme3"), host.dropNewPathsWithoutCtrlKey(before, ctrl));
        assertTrue(sys.commands.contains("nvme disconnect -d nvme3"), sys.commands.toString());
        assertTrue(sys.commands.stream().noneMatch(c -> c.equals("nvme disconnect -d nvme1")), "an older path is left alone");

        sys.results.put("nvme disconnect -d nvme3", new LightbitsNvmeHost.Result(1, "failed"));
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> host.dropNewPathsWithoutCtrlKey(before, ctrl));
        assertTrue(e.getMessage().contains("could not be disconnected"), e.getMessage());
        // an unreadable listing fails the check and forgets nothing
        sys.unreadable.add("/sys/class/nvme");
        assertThrows(CloudRuntimeException.class, () -> host.dropNewPathsWithoutCtrlKey(before, ctrl));
        sys.unreadable.clear();
        // nor does a controller whose subsystem NQN cannot be read for a moment
        sys.unreadable.add("/sys/class/nvme/nvme3/subsysnqn");
        host.dropNewPathsWithoutCtrlKey(host.pathNames(), ctrl);
        sys.unreadable.clear();
        // the next connect sees nvme3 among the paths that were already there: it stays rejected
        java.util.Set<String> next = host.pathNames();
        assertTrue(next.contains("nvme3"));
        assertThrows(CloudRuntimeException.class, () -> host.dropNewPathsWithoutCtrlKey(next, ctrl));
        sys.results.remove("nvme disconnect -d nvme3");
        assertEquals(List.of("nvme3"), host.dropNewPathsWithoutCtrlKey(next, ctrl), "dropped once the disconnect works");
        // (the fake keeps nvme3 in sysfs) once dropped it is no longer rejected: an old path again, left alone
        assertEquals(List.of(), host.dropNewPathsWithoutCtrlKey(next, ctrl));
    }

    @Test
    void aSuccessfulCommandKeepsItsWholeOutputAFailureIsShortened() {
        LightbitsNvmeHost.RealSys real = new LightbitsNvmeHost.RealSys();
        assertEquals(5000, real.run(10_000, "sh", "-c", "head -c 5000 /dev/zero | tr '\\0' a").output.length());
        assertEquals(LightbitsNvmeHost.MAX_MESSAGE_OUTPUT, real.run(10_000, "sh", "-c", "head -c 5000 /dev/zero | tr '\\0' a; exit 3").output.length());
    }

    @Test
    void twoWayHandoffNeedsTheControllerKeyOnEveryPathOfTheSubsystem() {
        String ctrl = "DHHC-1:00:ctrl:";
        sys.files.put("/sys/block/nvme3n1/device/subsysnqn", NQN);
        sys.controller("nvme1", "traddr=10.0.0.1,trsvcid=4420", "live");
        sys.controller("nvme2", "traddr=10.0.0.2,trsvcid=4420", "live");
        sys.files.put("/sys/class/nvme/nvme1/dhchap_ctrl_secret", ctrl);
        sys.files.put("/sys/class/nvme/nvme2/dhchap_ctrl_secret", ctrl + "\n");
        sys.files.put("/sys/class/nvme/nvme9/subsysnqn", "nqn.2016-01.com.lightbitslabs:uuid:another-cluster");
        sys.files.put("/sys/class/nvme/nvme9/dhchap_ctrl_secret", "none");
        host.requireTwoWayPaths("nvme3n1", ctrl);   // a path of another subsystem does not count

        sys.files.put("/sys/class/nvme/nvme2/dhchap_ctrl_secret", "none");
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> host.requireTwoWayPaths("nvme3n1", ctrl));
        assertTrue(e.getMessage().contains("nvme2") && !e.getMessage().contains("nvme1 ") && e.getMessage().contains("not handed over"),
                e.getMessage());
        sys.files.put("/sys/class/nvme/nvme2/dhchap_ctrl_secret", "DHHC-1:00:old:");
        assertThrows(CloudRuntimeException.class, () -> host.requireTwoWayPaths("nvme3n1", ctrl), "an old controller key");
        sys.files.put("/sys/class/nvme/nvme2/dhchap_ctrl_secret", ctrl);

        sys.unreadable.add("/sys/class/nvme/nvme2/subsysnqn");
        assertThrows(CloudRuntimeException.class, () -> host.requireTwoWayPaths("nvme3n1", ctrl), "unreadable: refused");
        sys.unreadable.clear();
        sys.unreadable.add("/sys/block/nvme3n1/device/subsysnqn");
        assertThrows(CloudRuntimeException.class, () -> host.requireTwoWayPaths("nvme3n1", ctrl));
    }

    @Test
    void aDeviceIsOpenByAVmOnlyWhenAQemuProcessHoldsIt() {
        assertFalse(host.openByVm("nvme3n1"), "no process");
        sys.files.put("/proc/self/comm", "java");
        sys.files.put("/proc/812/comm", "bash");
        sys.files.put("/proc/812/fd/3", "");
        sys.links.put("/proc/812/fd/3", "/dev/nvme3n1");   // a shell reading the device is not a VM
        sys.files.put("/proc/4711/comm", "qemu-kvm");
        sys.files.put("/proc/4711/fd/0", "");
        sys.files.put("/proc/4711/fd/21", "");
        sys.links.put("/proc/4711/fd/21", "/dev/nvme3n2");
        assertFalse(host.openByVm("nvme3n1"));
        assertTrue(host.openByVm("nvme3n2"));
        assertFalse(host.openByVm("nvme3n"), "the whole device name, not a prefix");
    }

    @Test
    void aRejectedPathThatCouldNotBeDisconnectedBlocksTheHandoff() {
        String ctrl = "DHHC-1:00:ctrl:";
        sys.files.put("/sys/block/nvme3n1/device/subsysnqn", NQN);
        java.util.Set<String> before = host.pathNames();
        sys.controller("nvme4", "traddr=10.0.0.4,trsvcid=4420", "live");
        sys.files.put("/sys/class/nvme/nvme4/dhchap_ctrl_secret", "none");
        sys.results.put("nvme disconnect -d nvme4", new LightbitsNvmeHost.Result(1, "failed"));
        assertThrows(CloudRuntimeException.class, () -> host.dropNewPathsWithoutCtrlKey(before, ctrl));
        // even if the path later shows the key, it was rejected and stays so until it is gone
        sys.files.put("/sys/class/nvme/nvme4/dhchap_ctrl_secret", ctrl);
        assertThrows(CloudRuntimeException.class, () -> host.requireTwoWayPaths("nvme3n1", ctrl));
        // once the path is gone the rejection is forgotten (the static set does not leak into other tests)
        sys.files.keySet().removeIf(f -> f.startsWith("/sys/class/nvme/nvme4/"));
        host.dropNewPathsWithoutCtrlKey(java.util.Set.of(), ctrl);
        host.requireTwoWayPaths("nvme3n1", ctrl);
    }

    @Test
    void pathCountsCanBeLimitedToOneCluster() {
        String key = "DHHC-1:00:k:";
        String other = "nqn.2016-01.com.lightbitslabs:uuid:0b0b0b0b-1111-2222-3333-444444444444";
        sys.controller("nvme1", "traddr=10.0.0.1,trsvcid=4420", "live");
        sys.files.put("/sys/class/nvme/nvme1/dhchap_secret", key);
        sys.files.put("/sys/class/nvme/nvme1/dhchap_ctrl_secret", key);
        sys.files.put("/sys/class/nvme/nvme7/subsysnqn", other);
        sys.files.put("/sys/class/nvme/nvme7/state", "live");
        sys.files.put("/sys/class/nvme/nvme7/dhchap_secret", "none");
        assertEquals(1, host.pathsWithoutKey(key), "all clusters");
        assertEquals(0, host.pathsWithoutKey(key, NQN), "only this cluster's paths count");
        assertEquals(1, host.pathsWithoutKey(key, other));
        assertEquals(0, host.pathsWithoutCtrlKey(key, NQN));
        assertEquals(2, host.lightbitsPathCount());
        assertEquals(1, host.lightbitsPathCount(other));
    }

    @Test
    void aControllerNameReusedForANewKeyedPathIsNotTheRejectedOne() {
        String ctrl = "DHHC-1:00:ctrl:";
        sys.files.put("/sys/block/nvme3n1/device/subsysnqn", NQN);
        java.util.Set<String> before = host.pathNames();
        sys.controller("nvme4", "traddr=10.0.0.4,trsvcid=4420", "live");
        sys.files.put("/sys/class/nvme/nvme4/cntlid", "17");
        sys.files.put("/sys/class/nvme/nvme4/dhchap_ctrl_secret", "none");
        sys.results.put("nvme disconnect -d nvme4", new LightbitsNvmeHost.Result(1, "failed"));
        assertThrows(CloudRuntimeException.class, () -> host.dropNewPathsWithoutCtrlKey(before, ctrl));
        // the same controller showing the key later stays rejected
        sys.files.put("/sys/class/nvme/nvme4/dhchap_ctrl_secret", ctrl);
        assertThrows(CloudRuntimeException.class, () -> host.requireTwoWayPaths("nvme3n1", ctrl));
        // disconnected by hand; the next connect makes a new controller with the right key under the same name
        sys.files.keySet().removeIf(f -> f.startsWith("/sys/class/nvme/nvme4/"));
        host.forgetVanishedRejections();
        sys.controller("nvme4", "traddr=10.0.0.4,trsvcid=4420", "live");
        sys.files.put("/sys/class/nvme/nvme4/cntlid", "18");
        sys.files.put("/sys/class/nvme/nvme4/dhchap_ctrl_secret", ctrl);
        host.requireTwoWayPaths("nvme3n1", ctrl);
    }

    @Test
    void aReusedNameWithANewControllerIdIsNotRejectedEvenWithoutAPruneInBetween() {
        String ctrl = "DHHC-1:00:ctrl:";
        sys.files.put("/sys/block/nvme3n1/device/subsysnqn", NQN);
        java.util.Set<String> before = host.pathNames();
        sys.controller("nvme4", "traddr=10.0.0.4,trsvcid=4420", "live");
        sys.files.put("/sys/class/nvme/nvme4/cntlid", "17");
        sys.files.put("/sys/class/nvme/nvme4/dhchap_ctrl_secret", "none");
        sys.results.put("nvme disconnect -d nvme4", new LightbitsNvmeHost.Result(1, "failed"));
        assertThrows(CloudRuntimeException.class, () -> host.dropNewPathsWithoutCtrlKey(before, ctrl));
        sys.files.put("/sys/class/nvme/nvme4/cntlid", "18");
        sys.files.put("/sys/class/nvme/nvme4/dhchap_ctrl_secret", ctrl);
        host.requireTwoWayPaths("nvme3n1", ctrl);
        sys.files.keySet().removeIf(f -> f.startsWith("/sys/class/nvme/nvme4/"));
        host.forgetVanishedRejections();
    }

    @Test
    void aClustersCleanupLeavesAnotherClustersRejectedPathAlone() {
        String ctrlA = "DHHC-1:00:ctrlA:";
        String ctrlB = "DHHC-1:00:ctrlB:";
        String other = "nqn.2016-01.com.lightbitslabs:uuid:0b0b0b0b-1111-2222-3333-444444444444";
        java.util.Set<String> before = host.pathNames();
        sys.controller("nvme4", "traddr=10.0.0.4,trsvcid=4420", "live");
        sys.files.put("/sys/class/nvme/nvme4/cntlid", "21");
        sys.files.put("/sys/class/nvme/nvme4/dhchap_ctrl_secret", "none");
        sys.results.put("nvme disconnect -d nvme4", new LightbitsNvmeHost.Result(1, "failed"));
        assertThrows(CloudRuntimeException.class, () -> host.dropNewPathsWithoutCtrlKey(before, ctrlA, NQN));
        sys.files.put("/sys/class/nvme/nvme4/dhchap_ctrl_secret", ctrlA);
        // cluster B's connect: nvme4 belongs to cluster A and is not checked against B's key
        java.util.Set<String> beforeB = host.pathNames();
        assertTrue(host.dropNewPathsWithoutCtrlKey(beforeB, ctrlB, other).isEmpty());
        sys.files.keySet().removeIf(f -> f.startsWith("/sys/class/nvme/nvme4/"));
        host.forgetVanishedRejections();
    }
}
