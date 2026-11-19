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
package org.apache.cloudstack.storage.datastore.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.util.List;

import org.apache.cloudstack.storage.datastore.client.LightbitsApiException.Kind;
import org.apache.cloudstack.storage.datastore.client.model.Volume;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Failover across the API endpoints of one cluster. Node A is either down (a closed port:
 * connection refused, the request provably never left) or answering 5xx (the request arrived,
 * its outcome is unknown); node B is healthy.
 */
class LightbitsApiClientFailoverTest {
    private static final String VOLUMES = "/api/v2/projects/default/volumes";
    private static final String UUID = "11111111-2222-3333-4444-555555555555";
    private static final String VOLUME = "{\"UUID\":\"" + UUID + "\",\"name\":\"cs-abc\",\"state\":\"Available\","
            + "\"size\":\"4294967296\",\"replicaCount\":3,\"projectName\":\"default\",\"ETag\":\"1\"}";

    private WireMockServer a;
    private WireMockServer b;
    private String deadEndpoint;

    @BeforeEach
    void setUp() throws Exception {
        a = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        b = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        a.start();
        b.start();
        try (ServerSocket s = new ServerSocket(0)) {
            deadEndpoint = "http://localhost:" + s.getLocalPort();   // closed again: connection refused
        }
        b.stubFor(get(urlPathEqualTo(VOLUMES)).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + VOLUME + "]}")));
        b.stubFor(get(urlEqualTo(VOLUMES + "/" + UUID)).willReturn(aResponse().withStatus(200).withBody(VOLUME)));
        b.stubFor(post(urlEqualTo(VOLUMES)).willReturn(aResponse().withStatus(200).withBody(VOLUME)));
    }

    @AfterEach
    void tearDown() {
        a.stop();
        b.stop();
    }

    private LightbitsApiClient client(String first, String second) {
        LightbitsApiClient c = new LightbitsApiClient(List.of(first, second), "jwt", "default", HttpClient.newHttpClient(), 1);
        c.setSettleWait(2000, 1);
        return c;
    }

    private String url(WireMockServer s) {
        return "http://localhost:" + s.port();
    }

    @Test
    void readFailsOverWhenTheFirstEndpointIsDown() {
        assertEquals(1, client(deadEndpoint, url(b)).listVolumes().size());
    }

    @Test
    void readFailsOverOnA5xx() {
        a.stubFor(get(urlPathEqualTo(VOLUMES)).willReturn(aResponse().withStatus(503).withBody("no leader")));
        assertEquals(1, client(url(a), url(b)).listVolumes().size());
    }

    @Test
    void mutationFailsOverWhenTheRequestNeverLeft() {
        // the pool URL names the node that is down: a create still goes through
        Volume v = client(deadEndpoint, url(b)).createVolume("cs-abc", 4294967296L, 3, null, false, null);
        assertEquals(UUID, v.getUuid());
        b.verify(1, postRequestedFor(urlEqualTo(VOLUMES)));
    }

    @Test
    void theEndpointThatAnsweredIsPreferredAfterwards() {
        a.stubFor(get(urlPathEqualTo(VOLUMES)).willReturn(aResponse().withStatus(503)));
        LightbitsApiClient c = client(url(a), url(b));
        c.listVolumes();
        c.listVolumes();
        c.listVolumes();
        // node A was probed once, not once per call
        a.verify(1, getRequestedFor(urlPathEqualTo(VOLUMES)));
        b.verify(3, getRequestedFor(urlPathEqualTo(VOLUMES)));
    }

    @Test
    void mutationIsNotSentAgainAfterA5xx() {
        // A answered 500 to the DELETE: it may have been applied. The request is not replayed on
        // B; the volume's state (read through B) decides. Here the volume is still there: fail.
        a.stubFor(delete(urlEqualTo(VOLUMES + "/" + UUID)).willReturn(aResponse().withStatus(500).withBody("boom")));
        a.stubFor(get(urlEqualTo(VOLUMES + "/" + UUID)).willReturn(aResponse().withStatus(200).withBody(VOLUME)));
        b.stubFor(delete(urlEqualTo(VOLUMES + "/" + UUID)).willReturn(aResponse().withStatus(200).withBody("{}")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class, () -> client(url(a), url(b)).deleteVolume(UUID));
        assertEquals(Kind.TRANSIENT, e.getKind());
        assertFalse(e.isNeverSent());
        a.verify(1, deleteRequestedFor(urlEqualTo(VOLUMES + "/" + UUID)));
        b.verify(0, deleteRequestedFor(urlEqualTo(VOLUMES + "/" + UUID)));
    }

    @Test
    void deleteThatFailedAfterItWasAppliedIsASuccess() {
        a.stubFor(delete(urlEqualTo(VOLUMES + "/" + UUID)).willReturn(aResponse().withStatus(500).withBody("boom")));
        a.stubFor(get(urlEqualTo(VOLUMES + "/" + UUID)).willReturn(aResponse().withStatus(404).withBody("{}")));
        assertTrue(client(url(a), url(b)).deleteVolume(UUID));
        b.verify(0, deleteRequestedFor(urlEqualTo(VOLUMES + "/" + UUID)));
    }

    @Test
    void createThatFailedAfterItWasAppliedAdoptsTheVolume() {
        // POST answered 500 but the volume exists under our name: no second POST, no orphan
        a.stubFor(post(urlEqualTo(VOLUMES)).willReturn(aResponse().withStatus(500).withBody("boom")));
        a.stubFor(get(urlPathEqualTo(VOLUMES)).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + VOLUME + "]}")));
        a.stubFor(get(urlEqualTo(VOLUMES + "/" + UUID)).willReturn(aResponse().withStatus(200).withBody(VOLUME)));
        Volume v = client(url(a), url(b)).createVolume("cs-abc", 4294967296L, 3, null, false, null);
        assertEquals(UUID, v.getUuid());
        a.verify(1, postRequestedFor(urlEqualTo(VOLUMES)));
        b.verify(0, postRequestedFor(urlEqualTo(VOLUMES)));
    }

    @Test
    void createThatFailedAndLeftNothingIsReported() {
        a.stubFor(post(urlEqualTo(VOLUMES)).willReturn(aResponse().withStatus(500).withBody("boom")));
        a.stubFor(get(urlPathEqualTo(VOLUMES)).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[]}")));
        assertThrows(LightbitsApiException.class,
                () -> client(url(a), url(b)).createVolume("cs-abc", 4294967296L, 3, null, false, null));
        b.verify(0, postRequestedFor(urlEqualTo(VOLUMES)));
    }

    @Test
    void aDefinitiveAnswerIsNotRetriedOnAnotherEndpoint() {
        a.stubFor(get(urlPathEqualTo(VOLUMES)).willReturn(aResponse().withStatus(403).withBody("denied")));
        LightbitsApiException e = assertThrows(LightbitsApiException.class, () -> client(url(a), url(b)).listVolumes());
        assertEquals(Kind.AUTH, e.getKind());
        b.verify(0, getRequestedFor(urlPathEqualTo(VOLUMES)));
    }

    @Test
    void allEndpointsDownIsReportedAsNeverSent() {
        LightbitsApiException e = assertThrows(LightbitsApiException.class,
                () -> client(deadEndpoint, deadEndpoint).deleteVolume(UUID));
        assertEquals(Kind.TRANSIENT, e.getKind());
        assertTrue(e.isNeverSent());
    }

    @Test
    void aDeadEndpointIsRememberedAcrossClients() {
        // the plugin builds a client per operation: the next one must not wait on the dead node again
        client(deadEndpoint, url(b)).listVolumes();
        assertTrue(LightbitsApiClient.isMarkedDown(deadEndpoint));
        assertFalse(LightbitsApiClient.isMarkedDown(url(b)));
        long t0 = System.nanoTime();
        assertEquals(1, client(deadEndpoint, url(b)).listVolumes().size());
        assertTrue((System.nanoTime() - t0) / 1_000_000L < 2000);
    }

    @Test
    void anEndpointMarkedDownIsStillTriedWhenItIsTheOnlyOneLeft() {
        String live = url(b);
        client(deadEndpoint, live).listVolumes();
        b.stop();
        LightbitsApiException e = assertThrows(LightbitsApiException.class, () -> client(deadEndpoint, live).listVolumes());
        assertTrue(e.isNeverSent());
    }
}
