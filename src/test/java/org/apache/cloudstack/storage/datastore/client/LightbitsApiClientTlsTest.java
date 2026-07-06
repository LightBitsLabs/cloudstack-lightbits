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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.cloudstack.storage.datastore.client.LightbitsApiException.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Security gate (test-plan §1): certificate validation is TESTED, not assumed.
 * WireMock's HTTPS listener uses a self-signed certificate — the default client
 * must refuse it; only the clearly-marked dev-only client may accept it.
 */
class LightbitsApiClientTlsTest {
    private WireMockServer wm;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort().dynamicHttpsPort());
        wm.start();
        wm.stubFor(WireMock.get(WireMock.urlEqualTo("/api/v2/cluster"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withBody("{\"UUID\":\"c-1\",\"health\":{\"state\":\"OK\"}}")));
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void defaultClientRejectsSelfSignedCertificate() {
        LightbitsApiClient client = new LightbitsApiClient(
                "https://localhost:" + wm.httpsPort(), "jwt", "default",
                java.net.http.HttpClient.newHttpClient(), 1);
        LightbitsApiException e = assertThrows(LightbitsApiException.class, client::getClusterInfo);
        assertEquals(Kind.TRANSIENT, e.getKind()); // SSL handshake surfaces as connection failure
    }

    @Test
    void insecureDevClientStillEnforcesHostnameVerification() {
        // Even the trust-all dev client cannot skip hostname verification: the JDK
        // HttpClient enforces it per-VM. WireMock's cert doesn't carry a SAN for
        // localhost, so this must fail — proving the bypass is trust-only.
        LightbitsApiClient client = LightbitsApiClient.insecureForDevelopment(
                "https://localhost:" + wm.httpsPort(), "jwt", "default");
        LightbitsApiException e = assertThrows(LightbitsApiException.class, client::getClusterInfo);
        assertEquals(Kind.TRANSIENT, e.getKind());
        assertTrue(String.valueOf(e.getMessage()).contains("No name matching"),
                "expected hostname verification failure, got: " + e.getMessage());
    }
}
