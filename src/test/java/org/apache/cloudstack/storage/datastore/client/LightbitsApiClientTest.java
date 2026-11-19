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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.apache.cloudstack.storage.datastore.client.LightbitsApiException.Kind;
import org.apache.cloudstack.storage.datastore.client.model.Volume;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

class LightbitsApiClientTest {
    private static final String VOL_UUID = "1514b669-a22e-4af4-af0b-f7b8b26f951c";
    private static final String PROJECT = "default";
    // Project-scoped volume collection — the client uses this route (a project-scoped JWT is
    // denied the flat /api/v2/volumes?project_name= route).
    private static final String VOLUMES = "/api/v2/projects/default/volumes";

    private WireMockServer wm;
    private LightbitsApiClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        client = new LightbitsApiClient("http://localhost:" + wm.port(), "test-jwt", PROJECT, java.net.http.HttpClient.newHttpClient(), 1);
        client.setSettleWait(2000, 1);
        // Defaults (lowest priority) for the settle polls every create/update now performs:
        // the volume and the snapshot are Available. Tests about settling override these.
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + VOL_UUID)).atPriority(10)
                .willReturn(aResponse().withStatus(200).withBody(volumeJson("Available"))));
        wm.stubFor(get(urlEqualTo("/api/v2/projects/default/snapshots/snap-1")).atPriority(10)
                .willReturn(aResponse().withStatus(200).withBody(
                    "{\"UUID\":\"snap-1\",\"name\":\"cs-snap\",\"state\":\"Available\",\"size\":\"4294967296\"}")));
    }

    /** GET of the volume answers ETag 1 until the given PUT is sent, then ETag 2 (the update applied). */
    private void stubEtagMovesAfterPut(String putUrl, String sizeAfter) {
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + VOL_UUID)).inScenario("update")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody(volumeJson("Available", "1", "4294967296"))));
        wm.stubFor(put(urlEqualTo(putUrl)).inScenario("update")
                .willReturn(aResponse().withStatus(200).withBody("{}")).willSetStateTo("applied"));
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + VOL_UUID)).inScenario("update").whenScenarioStateIs("applied")
                .willReturn(aResponse().withStatus(200).withBody(volumeJson("Available", "2", sizeAfter))));
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private static String volumeJson(String state) {
        return volumeJson(state, "1", "4294967296");
    }

    private static String volumeJson(String state, String etag, String size) {
        return "{\"UUID\":\"" + VOL_UUID + "\",\"name\":\"cs-abc\",\"state\":\"" + state
                + "\",\"size\":\"" + size + "\",\"replicaCount\":3,\"projectName\":\"default\","
                + "\"ETag\":\"" + etag + "\",\"acl\":{\"values\":[\"ALLOW_NONE\"]}}";
    }

    // ---- sunny day ----

    @Test
    void nodeCapacityAggregatesStatisticsAcrossNodes() {
        wm.stubFor(get(urlEqualTo("/api/v2/nodes")).willReturn(aResponse().withStatus(200).withBody(
            "{\"nodes\":["
            + "{\"UUID\":\"n0\",\"state\":\"Active\",\"statistics\":{\"effectivePhysicalStorage\":\"1000\",\"freePhysicalStorage\":\"800\"}},"
            + "{\"UUID\":\"n1\",\"state\":\"Active\",\"statistics\":{\"effectivePhysicalStorage\":\"1000\",\"freePhysicalStorage\":\"600\"}}]}")));
        LightbitsApiClient.NodeCapacity nc = client.nodeCapacity();
        assertEquals(2, nc.nodeCount);
        assertEquals(2000L, nc.effectivePhysicalBytes);
        assertEquals(1400L, nc.freePhysicalBytes);
    }

    @Test
    void nodeCapacitySkipsNonActiveNodes() {
        // Activating/Inactive nodes cannot serve I/O: they must not inflate the replica
        // upper bound or the advertised capacity.
        wm.stubFor(get(urlEqualTo("/api/v2/nodes")).willReturn(aResponse().withStatus(200).withBody(
            "{\"nodes\":["
            + "{\"UUID\":\"n0\",\"state\":\"Active\",\"statistics\":{\"effectivePhysicalStorage\":\"1000\",\"freePhysicalStorage\":\"800\"}},"
            + "{\"UUID\":\"n1\",\"state\":\"Activating\",\"statistics\":{\"effectivePhysicalStorage\":\"1000\",\"freePhysicalStorage\":\"1000\"}},"
            + "{\"UUID\":\"n2\",\"state\":\"Inactive\",\"statistics\":{\"effectivePhysicalStorage\":\"1000\",\"freePhysicalStorage\":\"1000\"}}]}")));
        LightbitsApiClient.NodeCapacity nc = client.nodeCapacity();
        assertEquals(1, nc.nodeCount);
        assertEquals(1000L, nc.effectivePhysicalBytes);
        assertEquals(800L, nc.freePhysicalBytes);
        assertEquals(1, client.countNodes());
    }

    @Test
    void createVolumeSendsMandatoryProjectName() {
        // trial finding F1: projectName is mandatory — assert we always send it
        wm.stubFor(post(urlEqualTo(VOLUMES))
                .withRequestBody(matchingJsonPath("$.projectName", equalTo(PROJECT)))
                .willReturn(aResponse().withStatus(200).withBody(volumeJson("Creating"))));
        Volume v = client.createVolume("cs-abc", 4294967296L, 3, null, false, null);
        assertEquals(VOL_UUID, v.getUuid());
        wm.verify(postRequestedFor(urlEqualTo(VOLUMES))
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
        stubEtagMovesAfterPut(VOLUMES + "/" + VOL_UUID, "8589934592");
        client.resizeVolume(VOL_UUID, 8589934592L);
        wm.verify(1, com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor(urlEqualTo(VOLUMES + "/" + VOL_UUID))
                .withRequestBody(matchingJsonPath("$.size", equalTo("8589934592")))
                .withRequestBody(matchingJsonPath("$.projectName", equalTo(PROJECT)))
                .withHeader("If-Match", equalTo("1")));
    }

    @Test
    void resizeFailsWhenTheClusterDoesNotApplyTheNewSize() {
        // the PUT is accepted and the ETag moves, but the size did not change: never report success
        stubEtagMovesAfterPut(VOLUMES + "/" + VOL_UUID, "4294967296");
        assertThrows(LightbitsApiException.class, () -> client.resizeVolume(VOL_UUID, 8589934592L));
    }

    @Test
    void getVolumeByNameFiltersDeletedStates() {
        // trial finding F3: deleted volumes linger in list responses
        wm.stubFor(get(urlPathEqualTo(VOLUMES))
                .willReturn(aResponse().withStatus(200).withBody(
                    "{\"volumes\":[" + volumeJson("Deleted") + "," + volumeJson("Available") + "]}")));
        Optional<Volume> v = client.getVolumeByName("cs-abc");
        assertTrue(v.isPresent());
        assertEquals("Available", v.get().getState());
    }

    @Test
    void listVolumesExcludesDeletedAndDeleting() {
        wm.stubFor(get(urlPathEqualTo(VOLUMES))
                .willReturn(aResponse().withStatus(200).withBody(
                    "{\"volumes\":[" + volumeJson("Deleted") + "," + volumeJson("Deleting") + "," + volumeJson("Available") + "]}")));
        List<Volume> vols = client.listVolumes();
        assertEquals(1, vols.size());
    }

    // ---- rainy day: idempotency ----

    @Test
    void createVolumeIsIdempotentOnConflict() {
        wm.stubFor(post(urlEqualTo(VOLUMES))
                .willReturn(aResponse().withStatus(409).withBody("{\"message\":\"already exists\"}")));
        wm.stubFor(get(urlPathEqualTo(VOLUMES))
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + volumeJson("Available") + "]}")));
        Volume v = client.createVolume("cs-abc", 4294967296L, 3, null, false, null);
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
        wm.stubFor(get(urlPathEqualTo(VOLUMES)).inScenario("flaky")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503).withBody("busy"))
                .willSetStateTo("second"));
        wm.stubFor(get(urlPathEqualTo(VOLUMES)).inScenario("flaky")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + volumeJson("Available") + "]}")));
        assertEquals(1, client.listVolumes().size());
    }

    @Test
    void transientErrorGivesUpAfterMaxAttempts() {
        wm.stubFor(get(urlPathEqualTo(VOLUMES))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class, () -> client.listVolumes());
        assertEquals(Kind.TRANSIENT, e.getKind());
        wm.verify(3, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo(VOLUMES)));
    }

    @Test
    void authFailureMapsToAuthKindAndDoesNotRetry() {
        wm.stubFor(get(urlPathEqualTo(VOLUMES))
                .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"bad token\"}")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class, () -> client.listVolumes());
        assertEquals(Kind.AUTH, e.getKind());
        wm.verify(1, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo(VOLUMES)));
    }

    @Test
    void missingProjectNameErrorMapsToInvalid() {
        wm.stubFor(post(urlEqualTo(VOLUMES))
                .willReturn(aResponse().withStatus(400).withBody("{\"code\":3,\"message\":\"ProjectName must be provided\"}")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class,
                () -> client.createVolume("cs-abc", 1L, 3, null, false, null));
        assertEquals(Kind.INVALID, e.getKind());
    }

    @Test
    void quotaExhaustionMapsToQuota() {
        wm.stubFor(post(urlEqualTo(VOLUMES))
                .willReturn(aResponse().withStatus(507).withBody("{\"message\":\"quota exceeded\"}")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class,
                () -> client.createVolume("cs-abc", 1L, 3, null, false, null));
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
        wm.stubFor(get(urlPathEqualTo(VOLUMES))
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":["
                    + "{\"UUID\":\"v-1\",\"name\":\"cs-env-other\",\"state\":\"Available\"},"
                    + "{\"UUID\":\"v-2\",\"name\":\"cs-env-wanted\",\"state\":\"Available\"}]}")));
        // the name filter is sent as a server-side hint (hot path; avoids pulling the
        // full project list), but matching stays exact and client-side
        assertEquals("v-2", client.getVolumeByName("cs-env-wanted").orElseThrow().getUuid());
        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo(VOLUMES))
                .withQueryParam("name", equalTo("cs-env-wanted")));
        assertTrue(client.getVolumeByName("cs-env-nope").isEmpty());
    }

    @Test
    void rollbackVolumeUsesProjectScopedPut() {
        stubEtagMovesAfterPut(VOLUMES + "/" + VOL_UUID + "/rollback", "4294967296");
        client.rollbackVolume(VOL_UUID, "snap-1");
        wm.verify(1, com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor(
                urlEqualTo("/api/v2/projects/default/volumes/" + VOL_UUID + "/rollback"))
                .withRequestBody(matchingJsonPath("$.srcSnapshotUUID", equalTo("snap-1")))
                .withRequestBody(matchingJsonPath("$.projectName", equalTo(PROJECT))));
    }

    @Test
    void rollbackDoesNotReturnBeforeTheVolumeChanged() {
        // The rollback PUT answers {} at once and the volume is Available both before and
        // after: if its ETag never moves the rollback was not applied and must not be reported.
        wm.stubFor(put(urlEqualTo(VOLUMES + "/" + VOL_UUID + "/rollback"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        client.setSettleWait(50, 1);
        LightbitsApiException e = assertThrows(LightbitsApiException.class, () -> client.rollbackVolume(VOL_UUID, "snap-1"));
        assertTrue(e.getMessage().contains("change not applied"), e.getMessage());
    }

    // ---- settling: create/clone/snapshot report success only for an Available object ----

    @Test
    void createVolumeWaitsUntilAvailable() {
        wm.stubFor(post(urlEqualTo(VOLUMES)).willReturn(aResponse().withStatus(200).withBody(volumeJson("Creating", "", "4294967296"))));
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + VOL_UUID)).inScenario("create")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody(volumeJson("Creating", "", "4294967296"))).willSetStateTo("ready"));
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + VOL_UUID)).inScenario("create").whenScenarioStateIs("ready")
                .willReturn(aResponse().withStatus(200).withBody(volumeJson("Available"))));
        Volume v = client.createVolume("cs-abc", 4294967296L, 3, null, false, null);
        assertTrue(v.isAvailable());
    }

    @Test
    void createVolumeThatLandsInFailedIsRemovedAndReported() {
        wm.stubFor(post(urlEqualTo(VOLUMES)).willReturn(aResponse().withStatus(200).withBody(volumeJson("Creating"))));
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + VOL_UUID)).willReturn(aResponse().withStatus(200).withBody(volumeJson("Failed"))));
        wm.stubFor(delete(urlEqualTo(VOLUMES + "/" + VOL_UUID)).willReturn(aResponse().withStatus(200).withBody("{}")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class,
                () -> client.createVolume("cs-abc", 4294967296L, 3, null, false, null));
        assertTrue(e.getMessage().contains("Failed"), e.getMessage());
        assertTrue(e.getMessage().contains("cs-abc"), e.getMessage());
        // no orphan: the half-made volume was deleted
        wm.verify(1, com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor(urlEqualTo(VOLUMES + "/" + VOL_UUID)));
    }

    @Test
    void createVolumeThatNeverSettlesIsRemovedAndReported() {
        wm.stubFor(post(urlEqualTo(VOLUMES)).willReturn(aResponse().withStatus(200).withBody(volumeJson("Creating"))));
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + VOL_UUID)).willReturn(aResponse().withStatus(200).withBody(volumeJson("Creating"))));
        wm.stubFor(delete(urlEqualTo(VOLUMES + "/" + VOL_UUID)).willReturn(aResponse().withStatus(200).withBody("{}")));
        client.setSettleWait(50, 1);
        assertThrows(LightbitsApiException.class, () -> client.createVolume("cs-abc", 4294967296L, 3, null, false, null));
        wm.verify(1, com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor(urlEqualTo(VOLUMES + "/" + VOL_UUID)));
    }

    @Test
    void createSnapshotWaitsUntilAvailable() {
        // a clone from a snapshot that is still Creating is refused by the cluster (HTTP 503)
        wm.stubFor(post(urlEqualTo("/api/v2/projects/default/snapshots")).willReturn(aResponse().withStatus(200).withBody(
                "{\"UUID\":\"snap-1\",\"name\":\"cs-snap\",\"state\":\"Creating\"}")));
        wm.stubFor(get(urlEqualTo("/api/v2/projects/default/snapshots/snap-1")).inScenario("snap")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody("{\"UUID\":\"snap-1\",\"name\":\"cs-snap\",\"state\":\"Creating\"}"))
                .willSetStateTo("ready"));
        wm.stubFor(get(urlEqualTo("/api/v2/projects/default/snapshots/snap-1")).inScenario("snap").whenScenarioStateIs("ready")
                .willReturn(aResponse().withStatus(200).withBody("{\"UUID\":\"snap-1\",\"name\":\"cs-snap\",\"state\":\"Available\"}")));
        assertTrue(client.createSnapshot("cs-snap", VOL_UUID).isAvailable());
    }

    @Test
    void deleteSnapshotTreatsAnErrorOnAnAlreadyGoneSnapshotAsSuccess() {
        // any error status (here 412) on a snapshot that is in fact gone or Deleting is a completed delete
        wm.stubFor(delete(urlEqualTo("/api/v2/projects/default/snapshots/snap-1"))
                .willReturn(aResponse().withStatus(412).withBody("{\"message\":\"precondition\"}")));
        wm.stubFor(get(urlEqualTo("/api/v2/projects/default/snapshots/snap-1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"UUID\":\"snap-1\",\"name\":\"cs-snap\",\"state\":\"Deleting\"}")));
        assertFalse(client.deleteSnapshot("snap-1"));
    }

    @Test
    void deleteSnapshotFailsWhenTheSnapshotIsStillThere() {
        wm.stubFor(delete(urlEqualTo("/api/v2/projects/default/snapshots/snap-1"))
                .willReturn(aResponse().withStatus(412).withBody("{\"message\":\"has clones\"}")));
        assertThrows(LightbitsApiException.class, () -> client.deleteSnapshot("snap-1"));
    }

    @Test
    void updateAclIsConditionalOnTheEtagAndStaleEtagIsPrecondition() {
        wm.stubFor(put(urlEqualTo(VOLUMES + "/" + VOL_UUID)).withHeader("If-Match", equalTo("7"))
                .willReturn(aResponse().withStatus(412).withBody("{\"message\":\"provided etag 7 don't match current etag 8\"}")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class,
                () -> client.updateVolumeAcl(VOL_UUID, List.of("nqn.host.a"), "7"));
        assertEquals(Kind.PRECONDITION, e.getKind());
    }

    @Test
    void deleteSnapshotUsesProjectScopedRouteAndIsIdempotent() {
        wm.stubFor(delete(urlEqualTo("/api/v2/projects/default/snapshots/snap-1"))
                .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"gone\"}")));
        assertFalse(client.deleteSnapshot("snap-1"));
    }

    @Test
    void createVolumeFromSnapshotClones() {
        wm.stubFor(post(urlEqualTo(VOLUMES))
                .withRequestBody(matchingJsonPath("$.sourceSnapshotUUID", equalTo("snap-1")))
                .withRequestBody(matchingJsonPath("$.size", equalTo("1073741824")))
                .willReturn(aResponse().withStatus(200).withBody(volumeJson("Creating"))));
        Volume v = client.createVolumeFromSnapshot("cs-clone", "snap-1", 1073741824L, 3, null, false, null);
        assertEquals(VOL_UUID, v.getUuid());
    }

    @Test
    void updateAclSendsValues() {
        stubEtagMovesAfterPut(VOLUMES + "/" + VOL_UUID, "4294967296");
        client.updateVolumeAcl(VOL_UUID, List.of("nqn.host.a"), "1");
        wm.verify(1, com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor(urlEqualTo(VOLUMES + "/" + VOL_UUID))
                .withRequestBody(matchingJsonPath("$.acl.values[0]", equalTo("nqn.host.a")))
                .withRequestBody(matchingJsonPath("$.projectName", equalTo(PROJECT)))
                .withHeader("If-Match", equalTo("1")));
    }

    @Test
    void malformedResponseIsAClearError() {
        wm.stubFor(get(urlPathEqualTo(VOLUMES))
                .willReturn(aResponse().withStatus(200).withBody("this is not json")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class, () -> client.listVolumes());
        assertEquals(Kind.UNKNOWN, e.getKind());
    }

    @Test
    void jwtIsSentAsBearerAndNeverLogged() {
        wm.stubFor(get(urlPathEqualTo(VOLUMES))
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[]}")));
        client.listVolumes();
        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo(VOLUMES))
                .withHeader("Authorization", equalTo("Bearer test-jwt")));
        // error messages must not contain the token — fail the exact route getVolume calls,
        // so the assertion is guaranteed to run (assertThrows, not a maybe-entered catch)
        wm.stubFor(get(urlPathEqualTo(VOLUMES + "/x"))
                .willReturn(aResponse().withStatus(401).withBody("denied")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class, () -> client.getVolume("x"));
        assertFalse(String.valueOf(e.getMessage()).contains("test-jwt"));
    }

    // ---- verifyProjectAccess: project-scoped reachability probe ----

    @Test
    void verifyProjectAccessSucceedsAndReturnsProjectUuid() {
        // (1) volume list via the project-scoped route — the authorization proof
        wm.stubFor(get(urlPathEqualTo(VOLUMES))
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[]}")));
        // (2) project object — best-effort UUID
        wm.stubFor(get(urlEqualTo("/api/v2/projects/default"))
                .willReturn(aResponse().withStatus(200).withBody(
                    "{\"UUID\":\"12f11016-15cf-419a-887c-cefc791e52d3\",\"name\":\"default\"}")));
        assertEquals("12f11016-15cf-419a-887c-cefc791e52d3", client.verifyProjectAccess());
        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo(VOLUMES)));
    }

    @Test
    void verifyProjectAccessThrowsWhenVolumeListDenied() {
        // A token that cannot even list the project's volumes must fail registration.
        wm.stubFor(get(urlPathEqualTo(VOLUMES))
                .willReturn(aResponse().withStatus(403).withBody("{\"message\":\"permission denied\"}")));
        assertThrows(LightbitsApiException.class, () -> client.verifyProjectAccess());
    }

    @Test
    void verifyProjectAccessSucceedsEvenIfProjectObjectUnavailable() {
        // Reachability is proven by the volume list; the project-object read is best-effort,
        // so a failure there must NOT fail the probe (UUID just comes back null).
        wm.stubFor(get(urlPathEqualTo(VOLUMES))
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[]}")));
        wm.stubFor(get(urlEqualTo("/api/v2/projects/default"))
                .willReturn(aResponse().withStatus(403).withBody("{\"message\":\"permission denied\"}")));
        assertEquals(null, client.verifyProjectAccess());
    }

    // ---- QoS policy: attach by name + registration-time existence check ----

    @Test
    void createVolumeSendsQosPolicyNameWhenProvided() {
        wm.stubFor(post(urlEqualTo(VOLUMES))
                .withRequestBody(matchingJsonPath("$.qosPolicyName", equalTo("256rw")))
                .willReturn(aResponse().withStatus(200).withBody(volumeJson("Creating"))));
        Volume v = client.createVolume("cs-abc", 4294967296L, 3, null, false, "256rw");
        assertEquals(VOL_UUID, v.getUuid());
        wm.verify(postRequestedFor(urlEqualTo(VOLUMES))
                .withRequestBody(matchingJsonPath("$.qosPolicyName", equalTo("256rw"))));
    }

    @Test
    void createVolumeFromSnapshotSendsQosPolicyName() {
        wm.stubFor(post(urlEqualTo(VOLUMES))
                .withRequestBody(matchingJsonPath("$.sourceSnapshotUUID", equalTo("snap-1")))
                .withRequestBody(matchingJsonPath("$.qosPolicyName", equalTo("256rw")))
                .willReturn(aResponse().withStatus(200).withBody(volumeJson("Creating"))));
        Volume v = client.createVolumeFromSnapshot("cs-clone", "snap-1", 1073741824L, 3, null, false, "256rw");
        assertEquals(VOL_UUID, v.getUuid());
    }

    @Test
    void qosPolicyExistsMatchesByExactNameForProject() {
        wm.stubFor(get(urlPathEqualTo("/api/v2/policies"))
                .willReturn(aResponse().withStatus(200).withBody("{\"policies\":["
                    + "{\"UUID\":\"5a8c979f-8992-4f40-b64e-e7a854c570df\",\"name\":\"no-limit-policy\",\"state\":\"Active\"},"
                    + "{\"UUID\":\"86d734f2-5557-48ee-abd7-3bdaf4841f11\",\"name\":\"256rw\",\"state\":\"Active\"}]}")));
        assertTrue(client.qosPolicyExists("256rw"));
        assertTrue(client.qosPolicyExists("no-limit-policy"));
        // server may ignore the ?name= filter and return the full list; exact match client-side
        assertFalse(client.qosPolicyExists("does-not-exist"));
        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/api/v2/policies"))
                .withQueryParam("projectName", equalTo(PROJECT)));
    }

    @Test
    void qosPolicyExistsFalseForNullOrBlank() {
        assertFalse(client.qosPolicyExists(null));
        assertFalse(client.qosPolicyExists("   "));
    }

    @Test
    void qosPolicyExistsEmptyListMeansAbsent() {
        wm.stubFor(get(urlPathEqualTo("/api/v2/policies"))
                .willReturn(aResponse().withStatus(200).withBody("{\"policies\":[]}")));
        assertFalse(client.qosPolicyExists("256rw"));
    }

    // ---- failure paths raised in review ----

    @Test
    void aVolumeFoundThroughAConflictIsNeverDeletedWhenItsLookupFails() {
        // 409: the volume existed before this call. Its settle poll fails; it must be left alone.
        wm.stubFor(post(urlEqualTo(VOLUMES)).willReturn(aResponse().withStatus(409).withBody("{\"message\":\"exists\"}")));
        wm.stubFor(get(urlPathEqualTo(VOLUMES)).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + volumeJson("Available") + "]}")));
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + VOL_UUID)).willReturn(aResponse().withStatus(500).withBody("boom")));
        client.setSettleWait(50, 1);
        assertThrows(LightbitsApiException.class, () -> client.createVolume("cs-abc", 4294967296L, 3, null, false, null));
        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly(1),
                com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo(VOLUMES + "/" + VOL_UUID)));
        wm.verify(0, com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor(urlEqualTo(VOLUMES + "/" + VOL_UUID)));
    }

    @Test
    void aSnapshotKnowsWhetherThisCallCreatedIt() {
        String snaps = "/api/v2/projects/default/snapshots";
        wm.stubFor(post(urlEqualTo(snaps)).willReturn(aResponse().withStatus(200).withBody(
                "{\"UUID\":\"snap-1\",\"name\":\"cs-snap\",\"state\":\"Creating\"}")));
        assertTrue(client.createSnapshot("cs-snap", VOL_UUID).isCreatedByThisCall());
        wm.stubFor(post(urlEqualTo(snaps)).willReturn(aResponse().withStatus(409).withBody("{\"message\":\"exists\"}")));
        wm.stubFor(get(urlPathEqualTo(snaps)).willReturn(aResponse().withStatus(200).withBody(
                "{\"snapshots\":[{\"UUID\":\"snap-1\",\"name\":\"cs-snap\",\"state\":\"Available\"}]}")));
        assertFalse(client.createSnapshot("cs-snap", VOL_UUID).isCreatedByThisCall());
    }

    @Test
    void aSnapshotFoundThroughAConflictIsNeverDeletedWhenItsLookupFails() {
        String snaps = "/api/v2/projects/default/snapshots";
        wm.stubFor(post(urlEqualTo(snaps)).willReturn(aResponse().withStatus(409).withBody("{\"message\":\"exists\"}")));
        wm.stubFor(get(urlPathEqualTo(snaps)).willReturn(aResponse().withStatus(200).withBody(
                "{\"snapshots\":[{\"UUID\":\"snap-1\",\"name\":\"cs-snap\",\"state\":\"Available\"}]}")));
        wm.stubFor(get(urlEqualTo(snaps + "/snap-1")).willReturn(aResponse().withStatus(500).withBody("boom")));
        client.setSettleWait(50, 1);
        assertThrows(LightbitsApiException.class, () -> client.createSnapshot("cs-snap", VOL_UUID));
        // the settle poll of the existing snapshot was reached (and failed): not an earlier error
        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly(1),
                com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo(snaps + "/snap-1")));
        wm.verify(0, com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor(urlEqualTo(snaps + "/snap-1")));
    }

    @Test
    void aFailedCreateWaitsForTheRemovalOfTheVolumeItMade() {
        wm.stubFor(post(urlEqualTo(VOLUMES)).willReturn(aResponse().withStatus(200).withBody(volumeJson("Creating"))));
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + VOL_UUID)).inScenario("gone")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody(volumeJson("Failed"))));
        wm.stubFor(delete(urlEqualTo(VOLUMES + "/" + VOL_UUID)).inScenario("gone")
                .willReturn(aResponse().withStatus(200).withBody("{}")).willSetStateTo("deleting"));
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + VOL_UUID)).inScenario("gone").whenScenarioStateIs("deleting")
                .willReturn(aResponse().withStatus(200).withBody(volumeJson("Deleting"))).willSetStateTo("removed"));
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + VOL_UUID)).inScenario("gone").whenScenarioStateIs("removed")
                .willReturn(aResponse().withStatus(404).withBody("{}")));
        assertThrows(LightbitsApiException.class, () -> client.createVolume("cs-abc", 4294967296L, 3, null, false, null));
        // polled past Deleting until the 404: the caller may now delete the clone's source snapshot
        wm.verify(3, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo(VOLUMES + "/" + VOL_UUID)));
    }

    @Test
    void aTransientPollFailureIsRiddenOutUntilTheVolumeSettles() {
        wm.stubFor(post(urlEqualTo(VOLUMES)).willReturn(aResponse().withStatus(200).withBody(volumeJson("Creating"))));
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + VOL_UUID)).inScenario("blip")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withBody("no leader")).willSetStateTo("back"));
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + VOL_UUID)).inScenario("blip").whenScenarioStateIs("back")
                .willReturn(aResponse().withStatus(200).withBody(volumeJson("Available"))));
        assertTrue(client.createVolume("cs-abc", 4294967296L, 3, null, false, null).isAvailable());
        wm.verify(0, com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor(urlEqualTo(VOLUMES + "/" + VOL_UUID)));
    }

    @Test
    void theSettleWaitEndsAtItsDeadlineWhenThePollsKeepFailing() {
        wm.stubFor(post(urlEqualTo(VOLUMES)).willReturn(aResponse().withStatus(200).withBody(volumeJson("Creating"))));
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + VOL_UUID)).willReturn(aResponse().withStatus(503).withBody("no leader")));
        wm.stubFor(delete(urlEqualTo(VOLUMES + "/" + VOL_UUID)).willReturn(aResponse().withStatus(200).withBody("{}")));
        client.setSettleWait(200, 5);
        long t0 = System.nanoTime();
        assertThrows(LightbitsApiException.class, () -> client.createVolume("cs-abc", 4294967296L, 3, null, false, null));
        // two bounded waits (settle, then removal), not attempts x request timeout
        assertTrue((System.nanoTime() - t0) / 1_000_000L < 5000, "the wait overran its deadline");
    }

    @Test
    void lookupForDeleteStillSeesAVolumeThatIsDeleting() {
        wm.stubFor(get(urlPathEqualTo(VOLUMES)).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + volumeJson("Deleting") + "]}")));
        assertTrue(client.getVolumeByName("cs-abc").isEmpty());
        Volume v = client.getVolumeByNameIncludingDeleting("cs-abc").orElseThrow();
        assertTrue(v.isDeletedOrDeleting());
    }

    @Test
    void lookupForDeleteIgnoresADeletedVolume() {
        wm.stubFor(get(urlPathEqualTo(VOLUMES)).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + volumeJson("Deleted") + "]}")));
        assertTrue(client.getVolumeByNameIncludingDeleting("cs-abc").isEmpty());
    }

    @Test
    void rollbackIsConditionalOnTheRevisionTheCallerChecked() {
        wm.stubFor(put(urlEqualTo(VOLUMES + "/" + VOL_UUID + "/rollback")).withHeader("If-Match", equalTo("7"))
                .willReturn(aResponse().withStatus(412).withBody("{\"message\":\"provided etag 7 does not match current etag 8\"}")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class, () -> client.rollbackVolume(VOL_UUID, "snap-1", "7"));
        assertEquals(Kind.PRECONDITION, e.getKind());
    }
}
