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
package org.apache.cloudstack.storage.datastore.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LightbitsHostIpsTest {

    @Test
    void theIpsOfSeveralHostsAreJoinedWithoutRepeats() {
        Map<String, List<String>> ips = Map.of("a", List.of("10.0.0.1", "10.0.1.1"), "b", List.of("10.0.0.2", "10.0.0.1"));
        assertEquals(List.of("10.0.0.1", "10.0.1.1", "10.0.0.2"), LightbitsHostIps.union(List.of("a", "b"), ips::get));
    }

    @Test
    void noHostsMeansNoIps() {
        assertEquals(List.of(), LightbitsHostIps.union(List.of(), n -> List.of("x")));
    }

    @Test
    void aHostWithoutKnownIpsLeavesTheAnswerOpen() {
        assertNull(LightbitsHostIps.union(List.of("a", "b"), n -> "a".equals(n) ? List.of("10.0.0.1") : List.of()));
        assertNull(LightbitsHostIps.union(List.of("a"), n -> null));
    }

    @Test
    void theDetailValueIsParsedTrimmedAndWithoutRepeats() {
        assertEquals(List.of("10.0.0.1", "10.0.0.2"), LightbitsHostIps.parse(" 10.0.0.1,10.0.0.2, 10.0.0.1,"));
        assertEquals(List.of(), LightbitsHostIps.parse(null));
        assertEquals("lightbits.dataips.7", LightbitsHostIps.detailKey(7));
    }

    @Test
    void aPartialReportAddsAddressesAndACompleteOneReplacesThem() {
        assertEquals(List.of("10.0.0.1", "10.0.0.2", "10.0.0.3"), LightbitsHostIps.merge("10.0.0.1,10.0.0.2", List.of("10.0.0.3"), true));
        assertEquals(List.of("10.0.0.3"), LightbitsHostIps.merge("10.0.0.1,10.0.0.2", List.of("10.0.0.3"), false));
        assertEquals(List.of("10.0.0.3"), LightbitsHostIps.merge(null, List.of("10.0.0.3"), true));
    }

    /** Host details as rows of (host id, name, value), behind the two DAO calls the resolver makes. */
    private static com.cloud.host.dao.HostDetailsDao dao(Object[]... rows) {
        return (com.cloud.host.dao.HostDetailsDao) java.lang.reflect.Proxy.newProxyInstance(LightbitsHostIpsTest.class.getClassLoader(),
                new Class<?>[] {com.cloud.host.dao.HostDetailsDao.class}, (proxy, m, args) -> {
                    java.util.List<com.cloud.host.DetailVO> out = new java.util.ArrayList<>();
                    for (Object[] r : rows) {
                        if ("findByName".equals(m.getName()) && r[1].equals(args[0])
                                || "findDetail".equals(m.getName()) && r[0].equals(args[0]) && r[1].equals(args[1])) {
                            out.add(new com.cloud.host.DetailVO((Long) r[0], (String) r[1], (String) r[2]));
                        }
                    }
                    if ("findByName".equals(m.getName())) {
                        return out;
                    }
                    if ("findDetail".equals(m.getName())) {
                        return out.isEmpty() ? null : out.get(0);
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test
    void everyHostSharingAnNqnMustHaveReportedItsIps() {
        String nqnKey = "nvme.hostnqn";
        com.cloud.host.dao.HostDetailsDao both = dao(new Object[] {1L, nqnKey, "nqn.a"}, new Object[] {2L, nqnKey, "nqn.a"},
                new Object[] {1L, "lightbits.dataips.7", "10.0.0.1"}, new Object[] {2L, "lightbits.dataips.7", "10.0.0.2"});
        assertEquals(List.of("10.0.0.1", "10.0.0.2"), LightbitsHostIps.resolver(both, nqnKey, 7).ipsFor(List.of("nqn.a")));

        com.cloud.host.dao.HostDetailsDao oneMissing = dao(new Object[] {1L, nqnKey, "nqn.a"}, new Object[] {2L, nqnKey, "nqn.a"},
                new Object[] {1L, "lightbits.dataips.7", "10.0.0.1"});
        assertNull(LightbitsHostIps.resolver(oneMissing, nqnKey, 7).ipsFor(List.of("nqn.a")),
                "a cloned host with the same NQN and no IPs must not lose access");
    }
}
