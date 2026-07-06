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
                "lightbits://10.19.43.20:443/my-project?replicas=3&compression=true&qosPolicy=gold&environment=prod-cs1", "jwt-x");
        assertEquals("10.19.43.20", c.getApiHost());
        assertEquals(443, c.getApiPort());
        assertEquals("my-project", c.getProject());
        assertEquals(3, c.getReplicaCount());
        assertTrue(c.isCompression());
        assertEquals("gold", c.getQosPolicy());
        assertEquals("prod-cs1", c.getEnvironment());
        assertEquals("https://10.19.43.20:443", c.apiEndpoint());
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
}
