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
package org.apache.cloudstack.storage.datastore.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LightbitsPoolConfigTest {

    @Test
    void parsesFullUrl() {
        LightbitsPoolConfig c = LightbitsPoolConfig.fromUrl(
                "lightbits://192.0.2.10:443/my-project?replicas=3&compression=true&qosPolicy=gold&environment=prod-cs1", "jwt-x");
        assertEquals("192.0.2.10", c.getApiHost());
        assertEquals(443, c.getApiPort());
        assertEquals("my-project", c.getProject());
        assertEquals(3, c.getReplicaCount());
        assertTrue(c.isCompression());
        assertEquals("gold", c.getQosPolicy());
        assertEquals("prod-cs1", c.getEnvironment());
        assertEquals("https://192.0.2.10:443", c.apiEndpoint());
    }

    @Test
    void defaultsPortAndReplicasAndCompression() {
        LightbitsPoolConfig c = LightbitsPoolConfig.fromUrl("lightbits://host/proj?environment=cs1", "jwt");
        assertEquals(443, c.getApiPort());
        assertEquals(3, c.getReplicaCount());
        assertFalse(c.isCompression());
    }

    @Test
    void rejectsMissingEnvironment() {
        // multiple CloudStacks may share a cluster; environment is required for
        // collision-free, identifiable volume names
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LightbitsPoolConfig.fromUrl("lightbits://host/proj", "jwt"));
        assertTrue(e.getMessage().contains("environment"));
    }

    @Test
    void rejectsMissingProject() {
        // trial finding F1: without a project the cluster rejects every volume op,
        // so we refuse to create a half-formed pool
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LightbitsPoolConfig.fromUrl("lightbits://host", "jwt"));
        assertTrue(e.getMessage().contains("project"));
    }

    @Test
    void rejectsWrongScheme() {
        assertThrows(IllegalArgumentException.class,
                () -> LightbitsPoolConfig.fromUrl("nfs://host/proj", "jwt"));
    }

    @Test
    void rejectsMissingHost() {
        assertThrows(IllegalArgumentException.class,
                () -> LightbitsPoolConfig.fromUrl("lightbits:///proj", "jwt"));
    }

    @Test
    void rejectsMissingJwt() {
        assertThrows(IllegalArgumentException.class,
                () -> LightbitsPoolConfig.fromUrl("lightbits://host/proj", "  "));
    }

    @Test
    void rejectsOutOfRangeReplicas() {
        assertThrows(IllegalArgumentException.class,
                () -> LightbitsPoolConfig.fromUrl("lightbits://host/proj?replicas=5", "jwt"));
        assertThrows(IllegalArgumentException.class,
                () -> LightbitsPoolConfig.fromUrl("lightbits://host/proj?replicas=zero", "jwt"));
    }

    @Test
    void detailsRoundTripExcludesSecret() {
        LightbitsPoolConfig c = LightbitsPoolConfig.fromUrl(
                "lightbits://h/p?replicas=2&compression=true&environment=cs1", "secret-jwt");
        Map<String, String> details = c.toDetailsWithoutSecret();
        assertFalse(details.containsValue("secret-jwt"), "JWT must never be in the details map");
        assertFalse(details.containsKey(LightbitsPoolConfig.KEY_JWT));

        LightbitsPoolConfig back = LightbitsPoolConfig.fromDetails(details, "secret-jwt");
        assertEquals("h", back.getApiHost());
        assertEquals("p", back.getProject());
        assertEquals(2, back.getReplicaCount());
        assertTrue(back.isCompression());
        assertEquals("cs1", back.getEnvironment());
        assertEquals("secret-jwt", back.getJwt());
    }

    @Test
    void chunkedValueRoundTripsForLongJwt() {
        // a real LightOS JWT is ~570 chars; encrypted, longer — must survive the
        // varchar(255) detail column by chunking
        StringBuilder jwt = new StringBuilder();
        for (int i = 0; i < 800; i++) {
            jwt.append((char) ('A' + (i % 26)));
        }
        Map<String, String> details = new HashMap<>();
        LightbitsPoolConfig.putChunked(details, "jwt", jwt.toString());
        // every stored chunk fits the column
        for (Map.Entry<String, String> e : details.entrySet()) {
            assertTrue(e.getValue().length() <= 255, "chunk " + e.getKey() + " exceeds 255");
        }
        assertEquals(jwt.toString(), LightbitsPoolConfig.readChunked(details, "jwt"));
    }

    @Test
    void readChunkedReturnsNullWhenAbsent() {
        assertEquals(null, LightbitsPoolConfig.readChunked(new HashMap<>(), "jwt"));
    }

    @Test
    void fromDetailsRejectsMissingRequiredKeys() {
        Map<String, String> incomplete = new HashMap<>();
        incomplete.put(LightbitsPoolConfig.KEY_API_HOST, "h");
        // no project
        assertThrows(IllegalArgumentException.class,
                () -> LightbitsPoolConfig.fromDetails(incomplete, "jwt"));
    }

    // ---- API failover endpoints and certificate pinning ----

    private static final String FP = "CA:B9:B1:57:67:D8:52:B0:DF:32:7F:FB:58:70:37:02:68:B9:3F:7E:76:70:75:8B:23:45:B4:FF:E8:A8:21:67";

    @Test
    void endpointsAndFingerprintAreParsedAndSurviveTheDetailsRoundTrip() {
        LightbitsPoolConfig cfg = LightbitsPoolConfig.fromUrl(
                "lightbits://10.0.0.1/proj?environment=cs1&endpoints=10.0.0.2,10.0.0.3:8443&fingerprint=" + FP, "jwt");
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("https://10.0.0.1:443", "https://10.0.0.2:443", "https://10.0.0.3:8443"), cfg.apiEndpoints());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(FP.replace(":", "")), cfg.getFingerprints());
        LightbitsPoolConfig back = LightbitsPoolConfig.fromDetails(cfg.toDetailsWithoutSecret(), "jwt");
        org.junit.jupiter.api.Assertions.assertEquals(cfg.apiEndpoints(), back.apiEndpoints());
        org.junit.jupiter.api.Assertions.assertEquals(cfg.getFingerprints(), back.getFingerprints());
    }

    @Test
    void aPoolWithoutEndpointsOrFingerprintKeepsOneEndpointAndNoPin() {
        LightbitsPoolConfig cfg = LightbitsPoolConfig.fromUrl("lightbits://10.0.0.1/proj?environment=cs1", "jwt");
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("https://10.0.0.1:443"), cfg.apiEndpoints());
        org.junit.jupiter.api.Assertions.assertTrue(cfg.getFingerprints().isEmpty());
        org.junit.jupiter.api.Assertions.assertFalse(cfg.toDetailsWithoutSecret().containsKey(LightbitsPoolConfig.KEY_TLS_FINGERPRINT));
    }

    @Test
    void theUrlHostIsNotListedTwiceAndTwoFingerprintsAreAcceptedForARotation() {
        LightbitsPoolConfig cfg = LightbitsPoolConfig.fromUrl(
                "lightbits://10.0.0.1/proj?environment=cs1&endpoints=10.0.0.1,10.0.0.2&fingerprint=" + FP + "," + "ab".repeat(32), "jwt");
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("https://10.0.0.1:443", "https://10.0.0.2:443"), cfg.apiEndpoints());
        org.junit.jupiter.api.Assertions.assertEquals(2, cfg.getFingerprints().size());
    }

    @Test
    void malformedEndpointOrFingerprintIsRejectedAtRegistration() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> LightbitsPoolConfig.fromUrl(
                "lightbits://10.0.0.1/proj?environment=cs1&endpoints=http://10.0.0.2", "jwt"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> LightbitsPoolConfig.fromUrl(
                "lightbits://10.0.0.1/proj?environment=cs1&fingerprint=12:34", "jwt"));
    }

    // ---- NVMe discovery endpoints for the KVM hosts ----

    @Test
    void discoveryEndpointsDefaultToEveryApiHostOnTheDiscoveryPort() {
        LightbitsPoolConfig cfg = LightbitsPoolConfig.fromUrl(
                "lightbits://10.0.0.1:8443/proj?environment=cs1&endpoints=10.0.0.2,10.0.0.3:9443", "jwt");
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("10.0.0.1:8009", "10.0.0.2:8009", "10.0.0.3:8009"), cfg.discoveryEndpoints());
        org.junit.jupiter.api.Assertions.assertFalse(cfg.toDetailsWithoutSecret().containsKey(LightbitsPoolConfig.KEY_NVME_DISCOVERY));
    }

    @Test
    void anExplicitDiscoveryListWinsAndSurvivesTheDetailsRoundTrip() {
        LightbitsPoolConfig cfg = LightbitsPoolConfig.fromUrl(
                "lightbits://10.0.0.1/proj?environment=cs1&discovery=192.168.5.1,192.168.5.2:8010", "jwt");
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("192.168.5.1:8009", "192.168.5.2:8010"), cfg.discoveryEndpoints());
        LightbitsPoolConfig back = LightbitsPoolConfig.fromDetails(cfg.toDetailsWithoutSecret(), null);
        org.junit.jupiter.api.Assertions.assertEquals(cfg.discoveryEndpoints(), back.discoveryEndpoints());
    }

    @Test
    void anEndpointTheHttpClientCannotUseIsRejectedAtRegistration() {
        for (String bad : new String[] {"bad..host:443", "host:0", "host:70000", "a-.b"}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> LightbitsPoolConfig.parseEndpoints(bad, 443), bad);
        }
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("10.0.0.2:443", "lb-2.example.com:8443"),
                LightbitsPoolConfig.parseEndpoints("10.0.0.2, lb-2.example.com:8443", 443));
    }

    @Test
    void anEndpointListTooLongToStoreIsRejectedAtRegistration() {
        String name = "n".repeat(61) + "." + "d".repeat(59) + ".io";   // 124 characters
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LightbitsPoolConfig.parseEndpoints("a" + name.substring(1) + "," + "b" + name.substring(1), 443));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("255"), e.getMessage());
        org.junit.jupiter.api.Assertions.assertEquals(1, LightbitsPoolConfig.parseEndpoints(name, 443).size());
    }

    @Test
    void ipv6ApiHostIsRefusedAtRegistration() {
        // the KVM hosts could not attach from such a pool: refuse it instead of creating it
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LightbitsPoolConfig.fromUrl("lightbits://[2001:db8::1]:443/proj?environment=cs1", "jwt"));
        assertTrue(e.getMessage().contains("IPv6") && e.getMessage().contains("[2001:db8::1]"), e.getMessage());
    }

    @Test
    void qosFromIopsIsOffUnlessSetAndSurvivesTheDetailsRoundTrip() {
        LightbitsPoolConfig off = LightbitsPoolConfig.fromUrl("lightbits://host/proj?environment=cs1", "jwt");
        org.junit.jupiter.api.Assertions.assertFalse(off.isQosFromIops());
        org.junit.jupiter.api.Assertions.assertFalse(off.toDetailsWithoutSecret().containsKey(LightbitsPoolConfig.KEY_QOS_FROM_IOPS));
        LightbitsPoolConfig on = LightbitsPoolConfig.fromUrl("lightbits://host/proj?environment=cs1&qosFromIops=true", "jwt");
        assertTrue(on.isQosFromIops());
        assertTrue(LightbitsPoolConfig.fromDetails(on.toDetailsWithoutSecret(), "jwt").isQosFromIops());
    }

    @Test
    void qosFromIopsRefusesAnythingButTrueOrFalse() {
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LightbitsPoolConfig.fromUrl("lightbits://host/proj?environment=cs1&qosFromIops=yes", "jwt"));
        assertTrue(e.getMessage().contains("qosFromIops"));
    }
}
