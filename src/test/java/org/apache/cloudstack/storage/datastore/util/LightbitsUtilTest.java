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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
class LightbitsUtilTest {
    @Test
    void volumeNameCarriesEnvironmentAndPoolPrefix() {
        assertEquals("cs-prod-cs1-p7-abc-123", LightbitsUtil.volumeName("prod-cs1", 7L, "abc-123"));
    }
    @Test
    void poolPrefixIsUniquePerPool() {
        // same project + environment, different pool id -> different prefix, so a
        // pool-delete sweep can never match a sibling pool's volumes.
        assertEquals("cs-prod-cs1-p7-", LightbitsUtil.poolPrefix("prod-cs1", 7L));
        assertEquals("cs-prod-cs1-p8-", LightbitsUtil.poolPrefix("prod-cs1", 8L));
    }
    @Test
    void volumeNameStartsWithItsPoolPrefix() {
        String env = "prod-cs1";
        long poolId = 7L;
        String name = LightbitsUtil.volumeName(env, poolId, "abc-123");
        assertTrue(name.startsWith(LightbitsUtil.poolPrefix(env, poolId)), name);
    }
    @Test
    void parseReplicasDetailAcceptsRange() {
        assertEquals(1, LightbitsUtil.parseReplicasDetail("1"));
        assertEquals(2, LightbitsUtil.parseReplicasDetail(" 2 "));
        assertEquals(3, LightbitsUtil.parseReplicasDetail("3"));
    }
    @Test
    void parseReplicasDetailRejectsOutOfRange() {
        for (String bad : new String[]{"0", "4", "-1"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> LightbitsUtil.parseReplicasDetail(bad));
            assertTrue(e.getMessage().contains("between 1 and 3"), e.getMessage());
        }
    }
    @Test
    void parseReplicasDetailRejectsGarbage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LightbitsUtil.parseReplicasDetail("two"));
        assertTrue(e.getMessage().contains("must be an integer"), e.getMessage());
    }

    @Test
    void onlyNamesThatAreExactlyThisPoolsAreItsObjects() {
        String uuid = "dddddddd-2222-3333-4444-555555555555";
        org.junit.jupiter.api.Assertions.assertTrue(LightbitsUtil.isPoolObject("cs1", 7, "cs-cs1-p7-" + uuid));
        org.junit.jupiter.api.Assertions.assertTrue(LightbitsUtil.isPoolObject("cs1", 7, "cs-cs1-p7-" + uuid + "-src"));
        // environment "cs1-p7", pool 3: starts with cs1/pool 7's prefix but is not its object
        org.junit.jupiter.api.Assertions.assertFalse(LightbitsUtil.isPoolObject("cs1", 7, "cs-cs1-p7-p3-" + uuid));
        org.junit.jupiter.api.Assertions.assertFalse(LightbitsUtil.isPoolObject("cs1", 7, "cs-cs1-p7-" + uuid + "-x"));
        org.junit.jupiter.api.Assertions.assertFalse(LightbitsUtil.isPoolObject("cs1", 7, "cs-cs1-p70-" + uuid));
        org.junit.jupiter.api.Assertions.assertFalse(LightbitsUtil.isPoolObject("c.1", 7, "cs-cx1-p7-" + uuid));
        org.junit.jupiter.api.Assertions.assertFalse(LightbitsUtil.isPoolObject("cs1", 7, null));
    }

    @Test
    void booleanDetailAcceptsOnlyTrueOrFalse() {
        org.junit.jupiter.api.Assertions.assertTrue(LightbitsUtil.parseBooleanDetail("k", " TRUE "));
        org.junit.jupiter.api.Assertions.assertFalse(LightbitsUtil.parseBooleanDetail("k", "false"));
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LightbitsUtil.parseBooleanDetail("lightbitsCompression", "yes"));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("lightbitsCompression"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> LightbitsUtil.parseBooleanDetail("k", null));
    }

    @Test
    void placementDetailIsTrimmedDeduplicatedAndBounded() {
        assertEquals(java.util.List.of("fd-a", "fd-b"), LightbitsUtil.parsePlacementDetail(" fd-a, ,fd-b,fd-a "));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> LightbitsUtil.parsePlacementDetail(" , "));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LightbitsUtil.parsePlacementDetail("x".repeat(101)));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 26; i++) {
            many.append("fd").append(i).append(',');
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> LightbitsUtil.parsePlacementDetail(many.toString()));
        assertEquals(25, LightbitsUtil.parsePlacementDetail(many.substring(0, many.lastIndexOf("fd25"))).size());
    }

    private static org.apache.cloudstack.storage.datastore.client.LightbitsApiClient.IopsPolicy pol(String n, long r, long w) {
        return new org.apache.cloudstack.storage.datastore.client.LightbitsApiClient.IopsPolicy(n, r, w);
    }

    @Test
    void theLowestIopsPolicyThatGivesAtLeastTheRequestIsPicked() {
        java.util.List<org.apache.cloudstack.storage.datastore.client.LightbitsApiClient.IopsPolicy> ps = java.util.List.of(
                pol("gold", 20000, 20000), pol("silver", 5000, 5000), pol("bronze", 1000, 1000),
                pol("no-limit-policy", 0, 0), pol("lopsided", 50000, 2000));
        assertEquals("silver", LightbitsUtil.policyForIops(ps, 5000));
        assertEquals("lopsided", LightbitsUtil.policyForIops(ps, 1001)); // min(50000, 2000) = 2000 is the lowest fit
        assertEquals("bronze", LightbitsUtil.policyForIops(ps, 1));
        // the smaller direction counts: lopsided gives only 2000 writes
        assertEquals("gold", LightbitsUtil.policyForIops(ps, 6000));
        // unlimited never matches, so a request above every limit finds nothing
        assertEquals(null, LightbitsUtil.policyForIops(ps, 30000));
        assertEquals(null, LightbitsUtil.policyForIops(java.util.List.of(), 100));
    }

    @Test
    void equalIopsPoliciesArePickedByName() {
        assertEquals("a", LightbitsUtil.policyForIops(java.util.List.of(pol("b", 500, 500), pol("a", 500, 900)), 400));
    }
}
