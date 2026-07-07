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
package org.apache.cloudstack.storage.datastore.lifecycle;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.storage.datastore.client.LightbitsApiClient;
import org.apache.cloudstack.storage.datastore.lifecycle.LightbitsPrimaryDataStoreLifeCycle.PreflightResult;
import org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

class LightbitsLifeCyclePreflightTest {
    private WireMockServer wm;
    private LightbitsPrimaryDataStoreLifeCycle lc;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        lc = new LightbitsPrimaryDataStoreLifeCycle();
        // stub factory: talk to WireMock over plain HTTP instead of the cluster
        lc.setClientFactory(cfg -> new LightbitsApiClient(
                "http://localhost:" + wm.port(), cfg.getJwt(), cfg.getProject(),
                java.net.http.HttpClient.newHttpClient(), 1));
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private static Map<String, Object> dsInfos(String url, String jwt) {
        Map<String, Object> m = new HashMap<>();
        m.put("url", url);
        Map<String, String> details = new HashMap<>();
        if (jwt != null) {
            details.put(LightbitsPoolConfig.KEY_JWT, jwt);
        }
        m.put("details", details);
        return m;
    }

    /** Stub a healthy cluster with the given number of nodes. */
    private void stubHealthyCluster(int nodeCount) {
        wm.stubFor(get(urlEqualTo("/api/v2/cluster")).willReturn(aResponse().withStatus(200).withBody(
            "{\"UUID\":\"c-1\",\"health\":{\"state\":\"OK\"},"
            + "\"statistics\":{\"effectivePhysicalStorage\":\"102392961820261\"}}")));
        StringBuilder nodes = new StringBuilder("{\"nodes\":[");
        for (int i = 0; i < nodeCount; i++) {
            nodes.append(i == 0 ? "" : ",").append("{\"UUID\":\"n").append(i).append("\",\"state\":\"Active\"}");
        }
        nodes.append("]}");
        wm.stubFor(get(urlEqualTo("/api/v2/nodes")).willReturn(aResponse().withStatus(200).withBody(nodes.toString())));
    }

    @Test
    void preflightSucceedsAgainstHealthyCluster() {
        stubHealthyCluster(3);
        PreflightResult r = lc.preflight(dsInfos("lightbits://10.19.43.20/default?replicas=3&environment=cs1", "jwt-x"));
        assertEquals("default", r.config.getProject());
        assertEquals(102392961820261L, r.capacityBytes);
        assertFalse(r.detailsWithoutSecret.containsKey(LightbitsPoolConfig.KEY_JWT),
                "persisted details must not carry the JWT");
    }

    @Test
    void preflightSucceedsOnSingleNodeClusterWithOneReplica() {
        stubHealthyCluster(1);
        PreflightResult r = lc.preflight(dsInfos("lightbits://h/default?replicas=1&environment=cs1", "jwt"));
        assertEquals(1, r.config.getReplicaCount());
    }

    @Test
    void preflightRejectsMoreReplicasThanNodes() {
        stubHealthyCluster(1);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lc.preflight(dsInfos("lightbits://h/default?replicas=3&environment=cs1", "jwt")));
        assertTrue(e.getMessage().contains("only 1 node"), e.getMessage());
        assertTrue(e.getMessage().contains("replicas=1"), e.getMessage());
    }

    @Test
    void preflightSkipsReplicaCheckWhenNodesUnavailable() {
        // /api/v2/cluster healthy, /api/v2/nodes not stubbed (404) -> check skipped, not fatal
        wm.stubFor(get(urlEqualTo("/api/v2/cluster")).willReturn(aResponse().withStatus(200).withBody(
            "{\"UUID\":\"c-1\",\"health\":{\"state\":\"OK\"},"
            + "\"statistics\":{\"effectivePhysicalStorage\":\"1\"}}")));
        PreflightResult r = lc.preflight(dsInfos("lightbits://h/default?replicas=3&environment=cs1", "jwt"));
        assertEquals(3, r.config.getReplicaCount());
    }

    @Test
    void preflightRejectsUnhealthyCluster() {
        wm.stubFor(get(urlEqualTo("/api/v2/cluster")).willReturn(aResponse().withStatus(200).withBody(
            "{\"UUID\":\"c-1\",\"health\":{\"state\":\"Degraded\"}}")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lc.preflight(dsInfos("lightbits://h/default?environment=cs1", "jwt")));
        assertTrue(e.getMessage().contains("not healthy"));
    }

    @Test
    void preflightRejectsUnreachableClusterWithActionableMessage() {
        wm.stubFor(get(urlEqualTo("/api/v2/cluster")).willReturn(aResponse().withStatus(401).withBody("denied")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lc.preflight(dsInfos("lightbits://h/default?environment=cs1", "bad-jwt")));
        assertTrue(e.getMessage().contains("cannot reach Lightbits cluster"));
        assertFalse(e.getMessage().contains("bad-jwt"), "credentials must not leak into errors");
    }

    @Test
    void preflightRejectsBadParamsBeforeAnyNetworkCall() {
        // no project in URL -> fails at parse, cluster never contacted
        assertThrows(IllegalArgumentException.class,
                () -> lc.preflight(dsInfos("lightbits://h", "jwt")));
    }
}
