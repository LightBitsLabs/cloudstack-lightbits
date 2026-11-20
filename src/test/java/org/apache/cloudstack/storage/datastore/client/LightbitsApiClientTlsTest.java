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
        wm.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v2/projects/default/volumes"))
                .willReturn(WireMock.aResponse().withStatus(200)
                        .withBody("{\"volumes\":[]}")));
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
        LightbitsApiException e = assertThrows(LightbitsApiException.class, client::listVolumes);
        assertEquals(Kind.TRANSIENT, e.getKind()); // SSL handshake surfaces as connection failure
        assertTrue(e.getMessage().contains("tlsFingerprint"), "the error must say what to do: " + e.getMessage());
    }

    /** SHA-256 fingerprint of the certificate the WireMock HTTPS listener presents. */
    private String serverFingerprint() throws Exception {
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        final java.security.cert.X509Certificate[][] seen = new java.security.cert.X509Certificate[1][];
        ctx.init(null, new javax.net.ssl.TrustManager[] {new javax.net.ssl.X509TrustManager() {
            @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
            @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { seen[0] = c; }
            @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
        }}, null);
        try (javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) ctx.getSocketFactory().createSocket("localhost", wm.httpsPort())) {
            s.startHandshake();
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(seen[0][0].getEncoded())) {
            sb.append(sb.length() == 0 ? "" : ":").append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static final String OTHER_FINGERPRINT = "AA".repeat(32);

    @Test
    void pinnedClientAcceptsTheCertificateWithThePinnedFingerprint() throws Exception {
        // the listener's certificate is self-signed and carries no name for "localhost":
        // a pin accepts it on the fingerprint alone, which is how a Lightbits cluster is verified
        LightbitsApiClient client = LightbitsApiClient.pinned(java.util.List.of("https://localhost:" + wm.httpsPort()),
                "jwt", "default", java.util.List.of(serverFingerprint()), 1);
        assertEquals(0, client.listVolumes().size());
    }

    @Test
    void pinnedClientRefusesAnyOtherCertificate() {
        LightbitsApiClient client = LightbitsApiClient.pinned(java.util.List.of("https://localhost:" + wm.httpsPort()),
                "jwt", "default", java.util.List.of(OTHER_FINGERPRINT), 1);
        LightbitsApiException e = assertThrows(LightbitsApiException.class, client::listVolumes);
        assertTrue(e.getMessage().contains("does not match the fingerprint pinned"), e.getMessage());
        assertTrue(e.isNeverSent(), "a refused handshake means no request was sent");
        wm.verify(0, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/api/v2/projects/default/volumes")));
    }

    @Test
    void pinnedClientAcceptsEitherFingerprintDuringARotation() throws Exception {
        LightbitsApiClient client = LightbitsApiClient.pinned(java.util.List.of("https://localhost:" + wm.httpsPort()),
                "jwt", "default", java.util.List.of(OTHER_FINGERPRINT, serverFingerprint().toUpperCase()), 1);
        assertEquals(0, client.listVolumes().size());
    }

    @Test
    void malformedFingerprintIsRejectedUpFront() {
        assertThrows(IllegalArgumentException.class, () -> LightbitsApiClient.normalizeFingerprint("12:34"));
        assertThrows(IllegalArgumentException.class, () -> LightbitsApiClient.normalizeFingerprint(null));
        assertEquals("AB".repeat(32), LightbitsApiClient.normalizeFingerprint("ab:".repeat(31) + "ab"));
    }
}
