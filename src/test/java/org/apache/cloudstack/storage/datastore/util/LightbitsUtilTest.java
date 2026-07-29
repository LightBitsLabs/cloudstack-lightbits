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
}
