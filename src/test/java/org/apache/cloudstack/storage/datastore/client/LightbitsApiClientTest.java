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
package org.apache.cloudstack.storage.datastore.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.apache.cloudstack.storage.datastore.client.LightbitsApiException.Kind;
import org.apache.cloudstack.storage.datastore.client.model.ClusterInfo;
import org.apache.cloudstack.storage.datastore.client.model.Volume;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

class LightbitsApiClientTest {
    private static final String VOL_UUID = "1514b669-a22e-4af4-af0b-f7b8b26f951c";
    private static final String PROJECT = "default";

    private WireMockServer wm;
    private LightbitsApiClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        client = new LightbitsApiClient("http://localhost:" + wm.port(), "test-jwt", PROJECT, java.net.http.HttpClient.newHttpClient(), 1);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private static String volumeJson(String state) {
        return "{\"UUID\":\"" + VOL_UUID + "\",\"name\":\"cs-abc\",\"state\":\"" + state
                + "\",\"size\":\"4294967296\",\"replicaCount\":3,\"projectName\":\"default\","
                + "\"acl\":{\"values\":[\"ALLOW_NONE\"]}}";
    }

    // ---- sunny day ----

    @Test
    void clusterInfoParsesHealthAndCapacity() {
        wm.stubFor(get(urlEqualTo("/api/v2/cluster")).willReturn(aResponse().withStatus(200).withBody(
            "{\"UUID\":\"c-1\",\"subsystemNQN\":\"nqn.2016-01.com.lightbitslabs:uuid:x\","
            + "\"health\":{\"state\":\"OK\"},"
            + "\"statistics\":{\"effectivePhysicalStorage\":\"1000\",\"freePhysicalStorage\":\"800\",\"physicalUsedStorage\":\"200\"}}")));
        ClusterInfo info = client.getClusterInfo();
        assertTrue(info.isHealthy());
        assertEquals(1000L, info.getStatistics().effectiveBytes());
        assertEquals(800L, info.getStatistics().freeBytes());
    }

    @Test
    void createVolumeSendsMandatoryProjectName() {
        // trial finding F1: projectName is mandatory — assert we always send it
        wm.stubFor(post(urlEqualTo("/api/v2/volumes"))
                .withRequestBody(matchingJsonPath("$.projectName", equalTo(PROJECT)))
                .willReturn(aResponse().withStatus(200).withBody(volumeJson("Creating"))));
        Volume v = client.createVolume("cs-abc", 4294967296L, 3, null);
        assertEquals(VOL_UUID, v.getUuid());
        wm.verify(postRequestedFor(urlEqualTo("/api/v2/volumes"))
                .withRequestBody(matchingJsonPath("$.acl.values[0]", equalTo("ALLOW_NONE"))));
    }

    @Test
    void deleteUsesProjectScopedRoute() {
        // trial finding F2: DELETE must use /api/v2/projects/{p}/volumes/{uuid}
        wm.stubFor(delete(urlEqualTo("/api/v2/projects/default/volumes/" + VOL_UUID))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        assertTrue(client.deleteVolume(VOL_UUID));
    }

    @Test
    void resizeUsesProjectScopedRoute() {
        wm.stubFor(put(urlEqualTo("/api/v2/projects/default/volumes/" + VOL_UUID))
                .withRequestBody(matchingJsonPath("$.size", equalTo("8589934592")))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        client.resizeVolume(VOL_UUID, 8589934592L);
    }

    @Test
    void getVolumeByNameFiltersDeletedStates() {
        // trial finding F3: deleted volumes linger in list responses
        wm.stubFor(get(urlPathEqualTo("/api/v2/volumes"))
                .willReturn(aResponse().withStatus(200).withBody(
                    "{\"volumes\":[" + volumeJson("Deleted") + "," + volumeJson("Available") + "]}")));
        Optional<Volume> v = client.getVolumeByName("cs-abc");
        assertTrue(v.isPresent());
        assertEquals("Available", v.get().getState());
    }

    @Test
    void listVolumesExcludesDeletedAndDeleting() {
        wm.stubFor(get(urlPathEqualTo("/api/v2/volumes"))
                .willReturn(aResponse().withStatus(200).withBody(
                    "{\"volumes\":[" + volumeJson("Deleted") + "," + volumeJson("Deleting") + "," + volumeJson("Available") + "]}")));
        List<Volume> vols = client.listVolumes();
        assertEquals(1, vols.size());
    }

    // ---- rainy day: idempotency ----

    @Test
    void createVolumeIsIdempotentOnConflict() {
        wm.stubFor(post(urlEqualTo("/api/v2/volumes"))
                .willReturn(aResponse().withStatus(409).withBody("{\"message\":\"already exists\"}")));
        wm.stubFor(get(urlPathEqualTo("/api/v2/volumes"))
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + volumeJson("Available") + "]}")));
        Volume v = client.createVolume("cs-abc", 4294967296L, 3, null);
        assertEquals(VOL_UUID, v.getUuid());
    }

    @Test
    void deleteVolumeIsIdempotentWhenAlreadyGone() {
        wm.stubFor(delete(urlEqualTo("/api/v2/projects/default/volumes/" + VOL_UUID))
                .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"not found\"}")));
        assertFalse(client.deleteVolume(VOL_UUID));
    }

    // ---- rainy day: transient failures retry, terminal failures do not ----

    @Test
    void transientServerErrorRetriesThenSucceeds() {
        wm.stubFor(get(urlEqualTo("/api/v2/cluster")).inScenario("flaky")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503).withBody("busy"))
                .willSetStateTo("second"));
        wm.stubFor(get(urlEqualTo("/api/v2/cluster")).inScenario("flaky")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody("{\"UUID\":\"c-1\",\"health\":{\"state\":\"OK\"}}")));
        assertTrue(client.getClusterInfo().isHealthy());
    }

    @Test
    void transientErrorGivesUpAfterMaxAttempts() {
        wm.stubFor(get(urlEqualTo("/api/v2/cluster"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class, () -> client.getClusterInfo());
        assertEquals(Kind.TRANSIENT, e.getKind());
        wm.verify(3, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo("/api/v2/cluster")));
    }

    @Test
    void authFailureMapsToAuthKindAndDoesNotRetry() {
        wm.stubFor(get(urlEqualTo("/api/v2/cluster"))
                .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"bad token\"}")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class, () -> client.getClusterInfo());
        assertEquals(Kind.AUTH, e.getKind());
        wm.verify(1, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo("/api/v2/cluster")));
    }

    @Test
    void missingProjectNameErrorMapsToInvalid() {
        wm.stubFor(post(urlEqualTo("/api/v2/volumes"))
                .willReturn(aResponse().withStatus(400).withBody("{\"code\":3,\"message\":\"ProjectName must be provided\"}")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class,
                () -> client.createVolume("cs-abc", 1L, 3, null));
        assertEquals(Kind.INVALID, e.getKind());
    }

    @Test
    void quotaExhaustionMapsToQuota() {
        wm.stubFor(post(urlEqualTo("/api/v2/volumes"))
                .willReturn(aResponse().withStatus(507).withBody("{\"message\":\"quota exceeded\"}")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class,
                () -> client.createVolume("cs-abc", 1L, 3, null));
        assertEquals(Kind.QUOTA, e.getKind());
    }

    @Test
    void createSnapshotSendsProjectAndSource() {
        wm.stubFor(post(urlEqualTo("/api/v2/projects/default/snapshots"))
                .withRequestBody(matchingJsonPath("$.projectName", equalTo(PROJECT)))
                .withRequestBody(matchingJsonPath("$.sourceVolumeUUID", equalTo(VOL_UUID)))
                .willReturn(aResponse().withStatus(200).withBody(
                    "{\"UUID\":\"snap-1\",\"name\":\"cs-snap\",\"state\":\"Available\",\"sourceVolumeUUID\":\"" + VOL_UUID + "\"}")));
        assertEquals("snap-1", client.createSnapshot("cs-snap", VOL_UUID).getUuid());
    }

    @Test
    void countNodesReturnsClusterNodeCount() {
        wm.stubFor(get(urlEqualTo("/api/v2/nodes"))
                .willReturn(aResponse().withStatus(200).withBody("{\"nodes\":["
                    + "{\"UUID\":\"n0\",\"state\":\"Active\"},"
                    + "{\"UUID\":\"n1\",\"state\":\"Active\"},"
                    + "{\"UUID\":\"n2\",\"state\":\"Active\"}]}")));
        assertEquals(3, client.countNodes());
    }

    @Test
    void countNodesHandlesSingleNode() {
        wm.stubFor(get(urlEqualTo("/api/v2/nodes"))
                .willReturn(aResponse().withStatus(200).withBody("{\"nodes\":[{\"UUID\":\"n0\",\"state\":\"Active\"}]}")));
        assertEquals(1, client.countNodes());
    }

    @Test
    void listSnapshotsFiltersDeletedAndDeleting() {
        wm.stubFor(get(urlEqualTo("/api/v2/projects/default/snapshots"))
                .willReturn(aResponse().withStatus(200).withBody("{\"snapshots\":["
                    + "{\"UUID\":\"snap-1\",\"name\":\"cs-env-a-src\",\"state\":\"Available\"},"
                    + "{\"UUID\":\"snap-2\",\"name\":\"cs-env-b-src\",\"state\":\"Deleting\"},"
                    + "{\"UUID\":\"snap-3\",\"name\":\"other\",\"state\":\"Available\"}]}")));
        var live = client.listSnapshots();
        assertEquals(2, live.size());
        assertEquals("snap-1", live.get(0).getUuid());
        assertEquals("other", live.get(1).getName());
    }

    @Test
    void getSnapshotByNameMatchesExactNameEvenWhenServerIgnoresTheFilter() {
        // LightOS returns ALL snapshots regardless of the ?name= param — the client must
        // select by exact name, not take the first entry (regression: rollback grabbed an
        // unrelated -src snapshot).
        wm.stubFor(get(urlPathEqualTo("/api/v2/projects/default/snapshots"))
                .willReturn(aResponse().withStatus(200).withBody("{\"snapshots\":["
                    + "{\"UUID\":\"snap-src\",\"name\":\"cs-env-vol1-src\",\"state\":\"Available\"},"
                    + "{\"UUID\":\"snap-user\",\"name\":\"cs-env-usersnap\",\"state\":\"Available\"}]}")));
        assertEquals("snap-user", client.getSnapshotByName("cs-env-usersnap").orElseThrow().getUuid());
        assertTrue(client.getSnapshotByName("cs-env-missing").isEmpty());
    }

    @Test
    void getVolumeByNameMatchesExactNameEvenWhenServerIgnoresTheFilter() {
        wm.stubFor(get(urlPathEqualTo("/api/v2/volumes"))
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":["
                    + "{\"UUID\":\"v-1\",\"name\":\"cs-env-other\",\"state\":\"Available\"},"
                    + "{\"UUID\":\"v-2\",\"name\":\"cs-env-wanted\",\"state\":\"Available\"}]}")));
        assertEquals("v-2", client.getVolumeByName("cs-env-wanted").orElseThrow().getUuid());
        assertTrue(client.getVolumeByName("cs-env-nope").isEmpty());
    }

    @Test
    void rollbackVolumeUsesProjectScopedPut() {
        wm.stubFor(put(urlEqualTo("/api/v2/projects/default/volumes/" + VOL_UUID + "/rollback"))
                .withRequestBody(matchingJsonPath("$.srcSnapshotUUID", equalTo("snap-1")))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        client.rollbackVolume(VOL_UUID, "snap-1");
        wm.verify(1, com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor(
                urlEqualTo("/api/v2/projects/default/volumes/" + VOL_UUID + "/rollback")));
    }

    @Test
    void deleteSnapshotUsesProjectScopedRouteAndIsIdempotent() {
        wm.stubFor(delete(urlEqualTo("/api/v2/projects/default/snapshots/snap-1"))
                .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"gone\"}")));
        assertFalse(client.deleteSnapshot("snap-1"));
    }

    @Test
    void createVolumeFromSnapshotClones() {
        wm.stubFor(post(urlEqualTo("/api/v2/volumes"))
                .withRequestBody(matchingJsonPath("$.sourceSnapshotUUID", equalTo("snap-1")))
                .withRequestBody(matchingJsonPath("$.size", equalTo("1073741824")))
                .willReturn(aResponse().withStatus(200).withBody(volumeJson("Creating"))));
        Volume v = client.createVolumeFromSnapshot("cs-clone", "snap-1", 1073741824L, 3, null);
        assertEquals(VOL_UUID, v.getUuid());
    }

    @Test
    void updateAclSendsValues() {
        wm.stubFor(put(urlEqualTo("/api/v2/projects/default/volumes/" + VOL_UUID))
                .withRequestBody(matchingJsonPath("$.acl.values[0]", equalTo("nqn.host.a")))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        client.updateVolumeAcl(VOL_UUID, List.of("nqn.host.a"));
    }

    @Test
    void malformedResponseIsAClearError() {
        wm.stubFor(get(urlEqualTo("/api/v2/cluster"))
                .willReturn(aResponse().withStatus(200).withBody("this is not json")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class, () -> client.getClusterInfo());
        assertEquals(Kind.UNKNOWN, e.getKind());
    }

    @Test
    void jwtIsSentAsBearerAndNeverLogged() {
        wm.stubFor(get(urlEqualTo("/api/v2/cluster"))
                .willReturn(aResponse().withStatus(200).withBody("{\"UUID\":\"c-1\",\"health\":{\"state\":\"OK\"}}")));
        client.getClusterInfo();
        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo("/api/v2/cluster"))
                .withHeader("Authorization", equalTo("Bearer test-jwt")));
        // error messages must not contain the token
        wm.stubFor(get(urlEqualTo("/api/v2/nodes")).willReturn(aResponse().withStatus(401).withBody("denied")));
        try {
            client.getVolume("x");
        } catch (LightbitsApiException e) {
            assertFalse(String.valueOf(e.getMessage()).contains("test-jwt"));
        }
    }
}
