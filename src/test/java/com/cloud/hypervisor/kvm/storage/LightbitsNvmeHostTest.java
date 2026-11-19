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
        int slept;

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
            for (Map.Entry<String, LightbitsNvmeHost.Result> e : results.entrySet()) {
                if (cmd.contains(e.getKey())) {
                    return e.getValue();
                }
            }
            return new LightbitsNvmeHost.Result(0, "");
        }

        @Override public boolean exists(String path) { return links.containsKey(path) || files.containsKey(path); }
        @Override public String read(String path) { return files.get(path); }
        @Override public String canonical(String path) { return links.get(path); }
        @Override public void sleep(long millis) { slept++; }

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
}
