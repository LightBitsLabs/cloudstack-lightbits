/*
 * Copyright 2026 Lightbits Labs Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cloudstack.storage.datastore.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreCapabilities;
import org.junit.jupiter.api.Test;

class LightbitsPrimaryDataStoreDriverTest {

    /**
     * Every snapshot operation the driver implements must be advertised: CloudStack picks the
     * snapshot strategy by capability, and an unadvertised one fails in the core ("supported
     * only for KVM hypervisor") without the driver being called.
     */
    @Test
    void advertisesEveryImplementedSnapshotCapability() {
        Map<String, String> caps = new LightbitsPrimaryDataStoreDriver().getCapabilities();
        for (DataStoreCapabilities c : new DataStoreCapabilities[] {
                DataStoreCapabilities.STORAGE_SYSTEM_SNAPSHOT,
                DataStoreCapabilities.CAN_REVERT_VOLUME_TO_SNAPSHOT,
                DataStoreCapabilities.CAN_CREATE_VOLUME_FROM_SNAPSHOT,
                DataStoreCapabilities.CAN_CREATE_VOLUME_FROM_VOLUME}) {
            assertEquals("true", caps.get(c.toString()), c.toString());
        }
    }

    // ---- per-volume statistics ----

    private static final String VOL = "03c281aa-a348-4933-8a6e-ee5bab91a0df";

    private static com.cloud.utils.Pair<Long, Long> statsFor(int status, String body) {
        com.github.tomakehurst.wiremock.WireMockServer wm = new com.github.tomakehurst.wiremock.WireMockServer(
                com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
        wm.start();
        try {
            wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                    com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/api/v2/projects/default/volumes/" + VOL))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(status).withBody(body)));
            org.apache.cloudstack.storage.datastore.client.LightbitsApiClient client =
                    new org.apache.cloudstack.storage.datastore.client.LightbitsApiClient("http://localhost:" + wm.port(), "jwt",
                            "default", java.net.http.HttpClient.newHttpClient(), 1);
            return LightbitsPrimaryDataStoreDriver.volumeStats(client, VOL);
        } finally {
            wm.stop();
        }
    }

    private static String volume(String state, String size, String statistics) {
        return "{\"UUID\":\"" + VOL + "\",\"name\":\"cs-x\",\"state\":\"" + state + "\",\"size\":\"" + size + "\""
                + (statistics == null ? "" : ",\"statistics\":" + statistics) + "}";
    }

    @Test
    void volumeStatsReportLogicalUseAndSize() {
        com.cloud.utils.Pair<Long, Long> stats = statsFor(200, volume("Available", "8589934592",
                "{\"logicalUsedStorage\":\"903396608\",\"physicalUsedStorage\":\"1024\",\"compressionRatio\":0.1}"));
        assertEquals(903396608L, stats.first());
        assertEquals(8589934592L, stats.second());
    }

    @Test
    void volumeStatsNeverReportMoreUsedThanTheSize() {
        com.cloud.utils.Pair<Long, Long> stats = statsFor(200, volume("Available", "1073741824", "{\"logicalUsedStorage\":\"2147483648\"}"));
        assertEquals(1073741824L, stats.first());
    }

    @Test
    void volumeStatsAreAbsentRatherThanMadeUp() {
        org.junit.jupiter.api.Assertions.assertNull(statsFor(404, "{}"), "volume not on the cluster");
        org.junit.jupiter.api.Assertions.assertNull(statsFor(200, volume("Deleting", "1073741824", "{\"logicalUsedStorage\":\"5\"}")));
        org.junit.jupiter.api.Assertions.assertNull(statsFor(200, volume("Available", "1073741824", null)), "no statistics reported");
        org.junit.jupiter.api.Assertions.assertNull(statsFor(200, volume("Available", "1073741824", "{\"logicalUsedStorage\":\"n/a\"}")));
        org.junit.jupiter.api.Assertions.assertNull(LightbitsPrimaryDataStoreDriver.volumeStats(null, "../../nodes"), "not a volume UUID");
        org.junit.jupiter.api.Assertions.assertNull(LightbitsPrimaryDataStoreDriver.volumeStats(null, null));
    }

    @Test
    void aClusterThatCannotBeReachedIsAnErrorNotAnEmptyVolume() {
        org.junit.jupiter.api.Assertions.assertThrows(org.apache.cloudstack.storage.datastore.client.LightbitsApiException.class,
                () -> statsFor(503, "no leader"));
    }

    /**
     * CloudStack's loop (StorageManagerImpl.getVolumeStats, 4.22 and 4.23): one request per
     * host, and the whole answer is dropped at the first volume with no sample.
     */
    private static Map<String, com.cloud.utils.Pair<Long, Long>> cloudStackRound(
            java.util.function.Function<String, com.cloud.utils.Pair<Long, Long>> driver, String... volumes) {
        Map<String, com.cloud.utils.Pair<Long, Long>> answer = new java.util.HashMap<>();
        for (String v : volumes) {
            com.cloud.utils.Pair<Long, Long> p = driver.apply(v);
            if (p == null) {
                return null;
            }
            answer.put(v, p);
        }
        return answer;
    }

    @Test
    void aVolumeWithNoSampleThisRoundDoesNotCostTheOthersTheirs() {
        String a = "aaaaaaaa-0000-0000-0000-000000000001", b = "bbbbbbbb-0000-0000-0000-000000000002",
                c = "cccccccc-0000-0000-0000-000000000003";
        com.github.tomakehurst.wiremock.WireMockServer wm = new com.github.tomakehurst.wiremock.WireMockServer(
                com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
        wm.start();
        try {
            org.apache.cloudstack.storage.datastore.client.LightbitsApiClient client =
                    new org.apache.cloudstack.storage.datastore.client.LightbitsApiClient("http://localhost:" + wm.port(), "jwt",
                            "default", java.net.http.HttpClient.newHttpClient(), 1);
            Map<String, com.cloud.utils.Pair<Long, Long>> last = new java.util.HashMap<>();
            java.util.function.BiConsumer<String, String> stub = (uuid, used) -> wm.stubFor(
                    com.github.tomakehurst.wiremock.client.WireMock.get(
                            com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/api/v2/projects/default/volumes/" + uuid))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200).withBody(
                            "{\"UUID\":\"" + uuid + "\",\"name\":\"x\",\"state\":\"Available\",\"size\":\"1000\""
                                    + (used == null ? "" : ",\"statistics\":{\"logicalUsedStorage\":\"" + used + "\"}") + "}")));
            stub.accept(a, "100");
            stub.accept(b, "200");
            stub.accept(c, "300");
            assertEquals(3, cloudStackRound(v -> LightbitsPrimaryDataStoreDriver.volumeStats(client, v, last), a, b, c).size());

            stub.accept(a, "110");
            stub.accept(b, null);    // the cluster has no statistics for b this round
            stub.accept(c, "330");
            Map<String, com.cloud.utils.Pair<Long, Long>> round2 =
                    cloudStackRound(v -> LightbitsPrimaryDataStoreDriver.volumeStats(client, v, last), a, b, c);
            assertEquals(110L, round2.get(a).first());
            assertEquals(200L, round2.get(b).first(), "b keeps its last sample, nothing made up");
            assertEquals(330L, round2.get(c).first());

            wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                    com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/api/v2/projects/default/volumes/" + b))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(404).withBody("{}")));
            assertEquals(200L, LightbitsPrimaryDataStoreDriver.volumeStats(client, b, last).first(), "gone: last sample once");
            org.junit.jupiter.api.Assertions.assertNull(LightbitsPrimaryDataStoreDriver.volumeStats(client, b, last),
                    "then forgotten");

            wm.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                    com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/api/v2/projects/default/volumes/" + a))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(503).withBody("no leader")));
            assertEquals(110L, LightbitsPrimaryDataStoreDriver.volumeStats(client, a, last).first(),
                    "a failed read with an earlier sample reports that sample");
        } finally {
            wm.stop();
        }
    }
}
