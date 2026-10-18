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
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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

/**
 * Preflight runs entirely with a PROJECT-scoped JWT: access is proven via the
 * project-scoped volume list (not the cluster-wide GET /api/v2/cluster, which such a
 * token is denied), and capacity comes from GET /api/v2/nodes.
 */
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

    /** Stub the project-scoped access probe: volume list + project object. */
    private void stubProjectAccess() {
        wm.stubFor(get(urlPathEqualTo("/api/v2/projects/default/volumes"))
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[]}")));
        wm.stubFor(get(urlEqualTo("/api/v2/projects/default"))
                .willReturn(aResponse().withStatus(200).withBody(
                    "{\"UUID\":\"12f11016-15cf-419a-887c-cefc791e52d3\",\"name\":\"default\"}")));
    }

    /** Stub the node list with the given per-node capacity statistics. */
    private void stubNodes(int nodeCount, long effectivePerNode, long freePerNode) {
        StringBuilder nodes = new StringBuilder("{\"nodes\":[");
        for (int i = 0; i < nodeCount; i++) {
            nodes.append(i == 0 ? "" : ",").append("{\"UUID\":\"n").append(i).append("\",\"state\":\"Active\",")
                 .append("\"statistics\":{\"effectivePhysicalStorage\":\"").append(effectivePerNode)
                 .append("\",\"freePhysicalStorage\":\"").append(freePerNode).append("\"}}");
        }
        nodes.append("]}");
        wm.stubFor(get(urlEqualTo("/api/v2/nodes")).willReturn(aResponse().withStatus(200).withBody(nodes.toString())));
    }

    @Test
    void preflightSucceedsWithProjectScopedToken() {
        stubProjectAccess();
        stubNodes(3, 30_000_000_000L, 20_000_000_000L);
        PreflightResult r = lc.preflight(dsInfos("lightbits://192.0.2.10/default?replicas=3&environment=cs1", "jwt-x"));
        assertEquals("default", r.config.getProject());
        // capacity = sum of effectivePhysicalStorage across nodes / replicas
        assertEquals(3 * 30_000_000_000L / 3, r.capacityBytes);
        assertFalse(r.detailsWithoutSecret.containsKey(LightbitsPoolConfig.KEY_JWT),
                "persisted details must not carry the JWT");
    }

    @Test
    void preflightSucceedsOnSingleNodeClusterWithOneReplica() {
        stubProjectAccess();
        stubNodes(1, 10_000_000_000L, 9_000_000_000L);
        PreflightResult r = lc.preflight(dsInfos("lightbits://h/default?replicas=1&environment=cs1", "jwt"));
        assertEquals(1, r.config.getReplicaCount());
        assertEquals(10_000_000_000L, r.capacityBytes);
    }

    @Test
    void preflightRejectsMoreReplicasThanNodes() {
        stubProjectAccess();
        stubNodes(1, 10_000_000_000L, 9_000_000_000L);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lc.preflight(dsInfos("lightbits://h/default?replicas=3&environment=cs1", "jwt")));
        assertTrue(e.getMessage().contains("only 1 node"), e.getMessage());
        assertTrue(e.getMessage().contains("replicas=1"), e.getMessage());
    }

    @Test
    void preflightRejectsWhenNodeListUnavailable() {
        // The node list is the pool's capacity source; without it registration must fail
        // with an actionable error, never proceed on a fabricated capacity figure.
        stubProjectAccess();
        // /api/v2/nodes not stubbed -> 404
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lc.preflight(dsInfos("lightbits://h/default?replicas=3&environment=cs1", "jwt")));
        assertTrue(e.getMessage().contains("node list"), e.getMessage());
    }

    @Test
    void preflightRejectsWhenNodesReportNoCapacity() {
        stubProjectAccess();
        stubNodes(3, 0L, 0L);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lc.preflight(dsInfos("lightbits://h/default?replicas=3&environment=cs1", "jwt")));
        assertTrue(e.getMessage().contains("no effective physical capacity"), e.getMessage());
    }

    @Test
    void preflightRejectsWhenProjectAccessDenied() {
        // A token that cannot list the project's volumes (403) must fail registration
        // with a message pointing at the project-scoped JWT requirement.
        wm.stubFor(get(urlPathEqualTo("/api/v2/projects/default/volumes"))
                .willReturn(aResponse().withStatus(403).withBody("{\"message\":\"permission denied\"}")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lc.preflight(dsInfos("lightbits://h/default?environment=cs1", "jwt")));
        assertTrue(e.getMessage().contains("cannot access Lightbits project"), e.getMessage());
        assertTrue(e.getMessage().contains("project-admin"), e.getMessage());
    }

    @Test
    void preflightRejectsUnreachableClusterWithActionableMessage() {
        wm.stubFor(get(urlPathEqualTo("/api/v2/projects/default/volumes"))
                .willReturn(aResponse().withStatus(401).withBody("denied")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lc.preflight(dsInfos("lightbits://h/default?environment=cs1", "bad-jwt")));
        assertTrue(e.getMessage().contains("cannot access Lightbits project"), e.getMessage());
        assertFalse(e.getMessage().contains("bad-jwt"), "credentials must not leak into errors");
    }

    @Test
    void preflightRejectsUnknownQosPolicy() {
        // A pool-default ?qosPolicy= that the project cannot see must fail registration,
        // not every subsequent volume create.
        stubProjectAccess();
        stubNodes(3, 30_000_000_000L, 20_000_000_000L);
        wm.stubFor(get(urlPathEqualTo("/api/v2/policies"))
                .willReturn(aResponse().withStatus(200).withBody("{\"policies\":[]}")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lc.preflight(dsInfos("lightbits://h/default?replicas=3&environment=cs1&qosPolicy=256rw", "jwt")));
        assertTrue(e.getMessage().contains("QoS policy '256rw'"), e.getMessage());
    }

    @Test
    void preflightRejectsWhenQosPolicyCheckFails() {
        // an unreadable policies route must surface as the same actionable error as the
        // other preflight checks, not as a raw API exception
        stubProjectAccess();
        stubNodes(3, 30_000_000_000L, 20_000_000_000L);
        wm.stubFor(get(urlPathEqualTo("/api/v2/policies"))
                .willReturn(aResponse().withStatus(403).withBody("{\"message\":\"permission denied\"}")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lc.preflight(dsInfos("lightbits://h/default?replicas=3&environment=cs1&qosPolicy=256rw", "jwt")));
        assertTrue(e.getMessage().contains("cannot verify Lightbits QoS policy"), e.getMessage());
    }

    @Test
    void preflightAcceptsKnownQosPolicy() {
        stubProjectAccess();
        stubNodes(3, 30_000_000_000L, 20_000_000_000L);
        wm.stubFor(get(urlPathEqualTo("/api/v2/policies"))
                .willReturn(aResponse().withStatus(200).withBody("{\"policies\":["
                    + "{\"UUID\":\"86d734f2-5557-48ee-abd7-3bdaf4841f11\",\"name\":\"256rw\",\"state\":\"Active\"}]}")));
        PreflightResult r = lc.preflight(
                dsInfos("lightbits://h/default?replicas=3&environment=cs1&qosPolicy=256rw", "jwt"));
        assertEquals("256rw", r.config.getQosPolicy());
    }

    @Test
    void preflightRejectsBadParamsBeforeAnyNetworkCall() {
        // no project in URL -> fails at parse, cluster never contacted
        assertThrows(IllegalArgumentException.class,
                () -> lc.preflight(dsInfos("lightbits://h", "jwt")));
    }
}
