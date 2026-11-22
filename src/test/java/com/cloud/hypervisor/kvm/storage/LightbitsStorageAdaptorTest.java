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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.exception.CloudRuntimeException;

/** The disk lookup of the adaptor: the agent polls it up to 100 times before it connects a disk. */
class LightbitsStorageAdaptorTest {
    private static final String VOL = "9a62657b-b976-43bb-91bc-c76d9a2f3985";
    private static final String NQN = "nqn.2016-01.com.lightbitslabs:uuid:5657b32a-f5d8-4abc-a08e-822f6a222c4b";
    private static final String LINK = "/dev/disk/by-id/nvme-uuid." + VOL;
    private static final String POOL_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String POOL_B = "bbbbbbbb-0000-0000-0000-000000000002";

    /** The fake host of the NVMe tests, with a clock and the timeout of every connect. */
    static class TimedSys extends LightbitsNvmeHostTest.FakeSys {
        long now = 1_000_000;
        long connectTakes;
        final List<Long> connectTimeouts = new ArrayList<>();

        @Override
        public LightbitsNvmeHost.Result run(long timeoutMillis, String... argv) {
            if (argv.length > 1 && "connect-all".equals(argv[1])) {
                connectTimeouts.add(timeoutMillis);
                now += connectTakes;
            }
            return super.run(timeoutMillis, argv);
        }

        long connects(String address) {
            return commands.stream().filter(c -> c.startsWith("nvme connect-all") && c.contains("-a " + address + " ")).count();
        }

        long rescans() {
            return commands.stream().filter(c -> c.startsWith("nvme ns-rescan")).count();
        }
    }

    private TimedSys sys;
    private LightbitsStorageAdaptor adaptor;
    private KVMStoragePool poolA;
    private KVMStoragePool poolB;

    @BeforeEach
    void setUp() {
        sys = new TimedSys();
        adaptor = new LightbitsStorageAdaptor(new LightbitsNvmeHost(sys));
        adaptor.clock = () -> sys.now;
        poolA = pool(POOL_A, "10.0.0.1");
        poolB = pool(POOL_B, "10.0.1.1");
    }

    // The adaptor's pool registry is static (one per agent JVM): leave it empty for the other test classes.
    @AfterEach
    void tearDown() {
        adaptor.deleteStoragePool(POOL_A);
        adaptor.deleteStoragePool(POOL_B);
    }

    private KVMStoragePool pool(String uuid, String discovery) {
        return adaptor.createStoragePool(uuid, discovery, 443, "/proj", null, StoragePoolType.IscsiLUN,
                Map.of(LightbitsStorageAdaptor.DETAIL_NVME_DISCOVERY, discovery), true);
    }

    @Test
    void aReRegistrationWithoutDetailsKeepsTheDiscoveryEndpoints() {
        // cancelling maintenance re-registers the pool through CloudStack's generic path, with no details
        adaptor.createStoragePool(POOL_A, "10.0.0.1", 443, "/proj", null, StoragePoolType.IscsiLUN,
                Map.of(LightbitsStorageAdaptor.DETAIL_NVME_DISCOVERY, "10.0.9.1:8009,10.0.9.2:8009"), true);
        List<String> before = LightbitsStorageAdaptor.discoveryEndpoints(adaptor.getStoragePool(POOL_A));
        KVMStoragePool again = adaptor.createStoragePool(POOL_A, "10.0.0.1", 443, "/proj", null, StoragePoolType.IscsiLUN, null, true);
        assertEquals(before, LightbitsStorageAdaptor.discoveryEndpoints(again));
        assertEquals(before, LightbitsStorageAdaptor.discoveryEndpoints(adaptor.getStoragePool(POOL_A)));
        // the Lightbits host-connect path always sends details: those replace the previous ones
        KVMStoragePool fresh = adaptor.createStoragePool(POOL_A, "10.0.0.1", 443, "/proj", null, StoragePoolType.IscsiLUN,
                Map.of(LightbitsStorageAdaptor.DETAIL_NVME_DISCOVERY, "10.0.8.1:8009"), true);
        assertEquals(LightbitsNvmeHost.discoveryEndpoints("10.0.8.1:8009", "10.0.0.1"), LightbitsStorageAdaptor.discoveryEndpoints(fresh));
    }

    private void noEndpointAnswers() {
        sys.results.put("connect-all", new LightbitsNvmeHost.Result(124, "nvme timed out"));
    }

    private void deviceAppears() {
        sys.namespace("nvme3n2", VOL, NQN);
        sys.links.put(LINK, "/dev/nvme3n2");
    }

    @Test
    void lookupOfOnePoolDoesNotHoldBackTheConnectOfAnother() {
        assertNull(adaptor.getPhysicalDisk(VOL, poolA));
        assertNull(adaptor.getPhysicalDisk(VOL, poolB));
        assertEquals(1, sys.connects("10.0.0.1"));
        assertEquals(1, sys.connects("10.0.1.1"), "pool B must connect although pool A connected a moment ago");
    }

    @Test
    void lookupsOfOnePoolShareOneConnectPerInterval() {
        adaptor.getPhysicalDisk(VOL, poolA);
        sys.now += 3_000;
        adaptor.getPhysicalDisk(VOL, poolA);
        assertEquals(1, sys.connects("10.0.0.1"));
        sys.now += LightbitsStorageAdaptor.LOOKUP_CONNECT_INTERVAL_MILLIS;
        adaptor.getPhysicalDisk(VOL, poolA);
        assertEquals(2, sys.connects("10.0.0.1"));
    }

    @Test
    void lookupConnectGivesAnEndpointTheShortTimeout() {
        adaptor.getPhysicalDisk(VOL, poolA);
        assertEquals(List.of(LightbitsStorageAdaptor.LOOKUP_CONNECT_TIMEOUT_MILLIS), sys.connectTimeouts);
    }

    @Test
    void attachConnectKeepsTheFullTimeout() {
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        deviceAppears();
        assertTrue(adaptor.connectPhysicalDisk(VOL, poolA, null, false));
        assertEquals(1, sys.connectTimeouts.size());
        assertTrue(sys.connectTimeouts.get(0) > LightbitsStorageAdaptor.LOOKUP_CONNECT_TIMEOUT_MILLIS);
    }

    @Test
    void failedLookupConnectIsNotRepeatedByEveryPoll() {
        noEndpointAnswers();
        sys.connectTakes = LightbitsStorageAdaptor.LOOKUP_CONNECT_TIMEOUT_MILLIS;
        CloudRuntimeException first = assertThrows(CloudRuntimeException.class, () -> adaptor.getPhysicalDisk(VOL, poolA));
        assertTrue(first.getMessage().contains("10.0.0.1:8009"), first.getMessage());
        assertEquals(1, sys.connects("10.0.0.1"));

        // the agent polls every three seconds: the next polls get the reason, without connect work
        for (int poll = 0; poll < 6; poll++) {
            sys.now += 3_000;
            CloudRuntimeException again = assertThrows(CloudRuntimeException.class, () -> adaptor.getPhysicalDisk(VOL, poolA));
            assertTrue(again.getMessage().contains("10.0.0.1:8009") && again.getMessage().contains("next attempt in"),
                    again.getMessage());
        }
        assertEquals(1, sys.connects("10.0.0.1"), "a failed connect must not be repeated before its backoff is over");

        sys.now += 3_000; // 21 s after the failed attempt ended: the first backoff (20 s) is over
        assertThrows(CloudRuntimeException.class, () -> adaptor.getPhysicalDisk(VOL, poolA));
        assertEquals(2, sys.connects("10.0.0.1"));

        sys.now += 39_000; // the second backoff is 40 s
        assertThrows(CloudRuntimeException.class, () -> adaptor.getPhysicalDisk(VOL, poolA));
        assertEquals(2, sys.connects("10.0.0.1"));
        sys.now += 2_000;
        assertThrows(CloudRuntimeException.class, () -> adaptor.getPhysicalDisk(VOL, poolA));
        assertEquals(3, sys.connects("10.0.0.1"));
    }

    @Test
    void hundredPollsAgainstADeadClusterStayBounded() {
        // CloudStack's lookup loop: 100 polls, three seconds apart, then a last one
        noEndpointAnswers();
        sys.connectTakes = LightbitsStorageAdaptor.LOOKUP_CONNECT_TIMEOUT_MILLIS;
        long start = sys.now;
        for (int poll = 0; poll <= 100; poll++) {
            assertThrows(CloudRuntimeException.class, () -> adaptor.getPhysicalDisk(VOL, poolA));
            sys.now += 3_000;
        }
        assertTrue(sys.connects("10.0.0.1") <= 6, "connect attempts: " + sys.connects("10.0.0.1"));
        assertTrue(sys.now - start < 10 * 60_000, "the whole lookup took " + (sys.now - start) / 1000 + "s");
    }

    @Test
    void backoffIsCapped() {
        noEndpointAnswers();
        for (int attempt = 0; attempt < 12; attempt++) {
            assertThrows(CloudRuntimeException.class, () -> adaptor.getPhysicalDisk(VOL, poolA));
            sys.now += LightbitsStorageAdaptor.LOOKUP_CONNECT_MAX_BACKOFF_MILLIS;
        }
        assertEquals(12, sys.connects("10.0.0.1"), "an attempt is due again after the maximum backoff at the latest");
    }

    @Test
    void aSuccessfulAttachClearsTheRememberedFailure() {
        noEndpointAnswers();
        assertThrows(CloudRuntimeException.class, () -> adaptor.getPhysicalDisk(VOL, poolA));
        sys.results.clear();
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        deviceAppears();
        assertTrue(adaptor.connectPhysicalDisk(VOL, poolA, null, false));
        // the volume is detached again; the next lookup must not report the old failure
        sys.links.remove(LINK);
        assertNull(adaptor.getPhysicalDisk(VOL, poolA));
    }

    @Test
    void failureOfOnePoolIsNotReportedForAnother() {
        sys.results.put("-a 10.0.0.1 ", new LightbitsNvmeHost.Result(124, "nvme timed out"));
        assertThrows(CloudRuntimeException.class, () -> adaptor.getPhysicalDisk(VOL, poolA));
        assertNull(adaptor.getPhysicalDisk(VOL, poolB));
    }

    @Test
    void discoveryThatOnlyFallsBackOnPathsOfAnotherClusterBacksOff() {
        // the host is connected to pool A's cluster; none of pool B's endpoints answers
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        sys.results.put("-a 10.0.1.1 ", new LightbitsNvmeHost.Result(124, "nvme timed out"));
        sys.connectTakes = LightbitsStorageAdaptor.LOOKUP_CONNECT_TIMEOUT_MILLIS;
        for (int poll = 0; poll <= 100; poll++) {
            assertNull(adaptor.getPhysicalDisk(VOL, poolB));
            sys.now += 3_000;
        }
        assertTrue(sys.connects("10.0.1.1") <= 6, "connect attempts without backoff: " + sys.connects("10.0.1.1"));
    }

    @Test
    void fallbackOnAnotherClustersPathKeepsTheReasonForWhenThatPathGoes() {
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        sys.results.put("-a 10.0.1.1 ", new LightbitsNvmeHost.Result(124, "nvme timed out"));
        assertNull(adaptor.getPhysicalDisk(VOL, poolB));
        // the only live path (pool A's cluster) goes away during pool B's backoff
        sys.files.put("/sys/class/nvme/nvme3/state", "connecting");
        sys.now += 3_000;
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class, () -> adaptor.getPhysicalDisk(VOL, poolB));
        assertTrue(e.getMessage().contains("10.0.1.1:8009") && e.getMessage().contains(POOL_B), e.getMessage());
        assertEquals(1, sys.connects("10.0.1.1"));
    }

    @Test
    void rememberedFailureDoesNotBlockTheRescanOnceTheHostIsConnected() {
        noEndpointAnswers();
        assertThrows(CloudRuntimeException.class, () -> adaptor.getPhysicalDisk(VOL, poolA));
        // another pool of the same cluster connects the host; the namespace needs a rescan to show
        sys.results.clear();
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        sys.onRescan = this::deviceAppears;
        sys.now += 3_000; // pool A is still in its backoff
        assertNotNull(adaptor.getPhysicalDisk(VOL, poolA));
        assertEquals(1, sys.connects("10.0.0.1"), "no new connect during the backoff");
    }

    @Test
    void lookupRescansForADeviceMissedOnAConnectedHost() {
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        sys.onRescan = this::deviceAppears; // the namespace only shows up when the controller is rescanned
        KVMPhysicalDisk disk = adaptor.getPhysicalDisk(VOL, poolA);
        assertNotNull(disk, "the lookup must find a namespace whose event was missed");
        assertEquals(LINK, disk.getPath());
        assertEquals(1, sys.rescans());
    }

    @Test
    void lookupRescanIsRateLimited() {
        sys.controller("nvme3", "traddr=10.0.0.1,trsvcid=4420", "live");
        assertNull(adaptor.getPhysicalDisk(VOL, poolA));
        sys.now += 3_000;
        assertNull(adaptor.getPhysicalDisk(VOL, poolA));
        assertEquals(1, sys.rescans());
        sys.now += LightbitsStorageAdaptor.LOOKUP_RESCAN_INTERVAL_MILLIS;
        assertNull(adaptor.getPhysicalDisk(VOL, poolA));
        assertEquals(2, sys.rescans());
    }

    @Test
    void removedPoolStartsFresh() {
        noEndpointAnswers();
        assertThrows(CloudRuntimeException.class, () -> adaptor.getPhysicalDisk(VOL, poolA));
        adaptor.deleteStoragePool(POOL_A);
        poolA = pool(POOL_A, "10.0.0.1");
        assertThrows(CloudRuntimeException.class, () -> adaptor.getPhysicalDisk(VOL, poolA));
        assertEquals(2, sys.connects("10.0.0.1"), "a pool that was removed and added again is tried at once");
    }
}
