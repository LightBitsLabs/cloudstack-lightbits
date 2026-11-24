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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.storage.datastore.client.LightbitsApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

class LightbitsAclTest {
    private static final String VOLUMES = "/api/v2/projects/default/volumes";
    private static final String GONE = "nqn.2014-08.org.nvmexpress:uuid:host-gone";
    private static final String STAYS = "nqn.2014-08.org.nvmexpress:uuid:host-stays";

    private WireMockServer wm;
    private LightbitsApiClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        client = new LightbitsApiClient("http://localhost:" + wm.port(), "jwt", "default", java.net.http.HttpClient.newHttpClient(), 1);
        client.setSettleWait(2000, 1);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private static String vol(String uuid, String name, String etag, String... acl) {
        return "{\"UUID\":\"" + uuid + "\",\"name\":\"" + name + "\",\"state\":\"Available\",\"ETag\":\"" + etag
                + "\",\"acl\":{\"values\":[\"" + String.join("\",\"", acl) + "\"]}}";
    }

    /** One volume: listed by name and read by UUID with ETag 1 until its PUT, ETag 2 afterwards. */
    private void stubVolume(String uuid, String name, String... acl) {
        wm.stubFor(get(urlPathEqualTo(VOLUMES)).withQueryParam("name", equalTo(name)).atPriority(1).inScenario(uuid)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + vol(uuid, name, "1", acl) + "]}")));
        wm.stubFor(put(urlEqualTo(VOLUMES + "/" + uuid)).inScenario(uuid).willSetStateTo("put")
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + uuid)).inScenario(uuid).whenScenarioStateIs("put")
                .willReturn(aResponse().withStatus(200).withBody(vol(uuid, name, "2", "ALLOW_NONE"))));
    }

    @Test
    void aRemovedHostIsPrunedFromThisPoolsVolumesOnly() {
        String mine = "cs-cs1-p2-aaaaaaaa-2222-3333-4444-555555555555";
        String shared = "cs-cs1-p2-bbbbbbbb-2222-3333-4444-555555555555";
        String clean = "cs-cs1-p2-cccccccc-2222-3333-4444-555555555555";
        String otherPool = "cs-cs1-p20-dddddddd-2222-3333-4444-555555555555";
        String otherEnv = "cs-cs1-p2-p5-eeeeeeee-2222-3333-4444-555555555555";   // environment "cs1-p2", pool 5
        wm.stubFor(get(urlPathEqualTo(VOLUMES)).atPriority(5).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":["
                + vol("u-mine", mine, "1", GONE) + "," + vol("u-shared", shared, "1", STAYS, GONE) + ","
                + vol("u-clean", clean, "1", STAYS) + "," + vol("u-other", otherPool, "1", GONE) + ","
                + vol("u-env", otherEnv, "1", GONE) + "]}")));
        stubVolume("u-mine", mine, GONE);
        stubVolume("u-shared", shared, STAYS, GONE);

        assertEquals(List.of(mine, shared), LightbitsAcl.pruneHost(client, "cs1", 2, GONE));

        wm.verify(1, putRequestedFor(urlEqualTo(VOLUMES + "/u-mine")).withHeader("If-Match", equalTo("1"))
                .withRequestBody(matchingJsonPath("$.acl.values[0]", equalTo("ALLOW_NONE"))));
        wm.verify(1, putRequestedFor(urlEqualTo(VOLUMES + "/u-shared")).withHeader("If-Match", equalTo("1"))
                .withRequestBody(matchingJsonPath("$.acl.values[0]", equalTo(STAYS)))
                .withRequestBody(matchingJsonPath("$.acl.values.length()", equalTo("1"))));
        wm.verify(0, putRequestedFor(urlEqualTo(VOLUMES + "/u-clean")));
        wm.verify(0, putRequestedFor(urlEqualTo(VOLUMES + "/u-other")));
        wm.verify(0, putRequestedFor(urlEqualTo(VOLUMES + "/u-env")));
    }

    @Test
    void aSlowSweepRenewsBeforeEveryVolumeSoTheMarkNeverExpiresMidway() {
        // Each volume update takes 20 min of a fake clock; the mark lasts 30 min from its last
        // renewal. Renewing before each volume keeps it counting for the whole sweep.
        String second = "cs-cs1-p2-bbbbbbbb-2222-3333-4444-555555555555";
        String third = "cs-cs1-p2-cccccccc-2222-3333-4444-555555555555";
        wm.stubFor(get(urlPathEqualTo(VOLUMES)).atPriority(5).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":["
                + volIps("u1", NAME, "1", List.of(GONE), List.of("ALLOW_ANY")) + ","
                + volIps("u2", second, "1", List.of(GONE), List.of("ALLOW_ANY")) + ","
                + volIps("u3", third, "1", List.of(GONE), List.of("ALLOW_ANY")) + "]}")));
        stubOne("u1", NAME, List.of(GONE), List.of("ALLOW_ANY"));
        stubOne("u2", second, List.of(GONE), List.of("ALLOW_ANY"));
        stubOne("u3", third, List.of(GONE), List.of("ALLOW_ANY"));
        long[] now = {0};
        long[] renewed = {0};
        List<Boolean> markCountedAtEachUpdate = new ArrayList<>();
        List<String> pruned = LightbitsAcl.pruneHost(client, "cs1", 2, GONE, null, () -> {
            markCountedAtEachUpdate.add(!LightbitsHostGate.markExpired(LightbitsHostGate.markValue(renewed[0]), now[0]));
            renewed[0] = now[0];
            now[0] += 20 * 60 * 1000L;   // this volume's update takes 20 min
        });
        assertEquals(3, pruned.size());
        assertEquals(List.of(true, true, true, true), markCountedAtEachUpdate, "the mark counted before the listing and every volume");
        org.junit.jupiter.api.Assertions.assertTrue(now[0] > LightbitsHostGate.MARK_HOLD_MILLIS, "the sweep outlasted one mark period");
    }

    @Test
    void aVolumeThatCannotBeUpdatedDoesNotStopTheOthers() {
        String broken = "cs-cs1-p2-aaaaaaaa-2222-3333-4444-555555555555";
        String fine = "cs-cs1-p2-bbbbbbbb-2222-3333-4444-555555555555";
        wm.stubFor(get(urlPathEqualTo(VOLUMES)).atPriority(5).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":["
                + vol("u-broken", broken, "1", GONE) + "," + vol("u-fine", fine, "1", GONE) + "]}")));
        wm.stubFor(get(urlPathEqualTo(VOLUMES)).withQueryParam("name", equalTo(broken)).atPriority(1)
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + vol("u-broken", broken, "1", GONE) + "]}")));
        wm.stubFor(put(urlEqualTo(VOLUMES + "/u-broken")).willReturn(aResponse().withStatus(403).withBody("denied")));
        stubVolume("u-fine", fine, GONE);

        assertEquals(List.of(fine), LightbitsAcl.pruneHost(client, "cs1", 2, GONE));
    }

    private static String volIps(String uuid, String name, String etag, List<String> acl, List<String> ips) {
        return "{\"UUID\":\"" + uuid + "\",\"name\":\"" + name + "\",\"state\":\"Available\",\"ETag\":\"" + etag
                + "\",\"acl\":{\"values\":[\"" + String.join("\",\"", acl) + "\"]},\"IPAcl\":{\"values\":[\""
                + String.join("\",\"", ips) + "\"]}}";
    }

    private void stubOne(String uuid, String name, List<String> acl, List<String> ips) {
        wm.stubFor(get(urlPathEqualTo(VOLUMES)).withQueryParam("name", equalTo(name)).atPriority(1).inScenario(uuid)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + volIps(uuid, name, "1", acl, ips) + "]}")));
        wm.stubFor(put(urlEqualTo(VOLUMES + "/" + uuid)).inScenario(uuid).willSetStateTo("put")
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        wm.stubFor(get(urlEqualTo(VOLUMES + "/" + uuid)).inScenario(uuid).whenScenarioStateIs("put")
                .willReturn(aResponse().withStatus(200).withBody(volIps(uuid, name, "2", acl, ips))));
    }

    private static final String NAME = "cs-cs1-p2-aaaaaaaa-2222-3333-4444-555555555555";

    @Test
    void aGrantSetsTheIpAclToTheIpsOfTheHostsInTheSameUpdate() {
        stubOne("u1", NAME, List.of(STAYS), List.of("10.0.0.2"));
        java.util.Map<String, List<String>> ipsOf = java.util.Map.of(STAYS, List.of("10.0.0.2"), GONE, List.of("10.0.0.9"));
        boolean sent = LightbitsAcl.update(client, NAME, true, acl -> acl.add(GONE),
                nqns -> LightbitsHostIps.union(nqns, ipsOf::get));
        org.junit.jupiter.api.Assertions.assertTrue(sent);
        wm.verify(1, putRequestedFor(urlEqualTo(VOLUMES + "/u1")).withHeader("If-Match", equalTo("1"))
                .withRequestBody(matchingJsonPath("$.acl.values.length()", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.IPAcl.values[0]", equalTo("10.0.0.2")))
                .withRequestBody(matchingJsonPath("$.IPAcl.values[1]", equalTo("10.0.0.9"))));
    }

    @Test
    void theLastHostLeavingSetsTheIpAclToAllowNone() {
        stubOne("u1", NAME, List.of(GONE), List.of("10.0.0.9"));
        LightbitsAcl.update(client, NAME, false, acl -> acl.remove(GONE), nqns -> LightbitsHostIps.union(nqns, n -> List.of("10.0.0.9")));
        wm.verify(1, putRequestedFor(urlEqualTo(VOLUMES + "/u1"))
                .withRequestBody(matchingJsonPath("$.acl.values[0]", equalTo("ALLOW_NONE")))
                .withRequestBody(matchingJsonPath("$.IPAcl.values[0]", equalTo("ALLOW_NONE"))));
    }

    @Test
    void anIpAclOutOfLineIsCorrectedEvenWhenTheNqnsAreAlreadyRight() {
        stubOne("u1", NAME, List.of(STAYS), List.of("ALLOW_ANY"));
        boolean sent = LightbitsAcl.update(client, NAME, true, acl -> false, nqns -> List.of("10.0.0.2"));
        org.junit.jupiter.api.Assertions.assertTrue(sent);
        wm.verify(1, putRequestedFor(urlEqualTo(VOLUMES + "/u1"))
                .withRequestBody(matchingJsonPath("$.acl.values[0]", equalTo(STAYS)))
                .withRequestBody(matchingJsonPath("$.IPAcl.values[0]", equalTo("10.0.0.2"))));
    }

    @Test
    void nothingIsSentWhenBothListsAreRight() {
        stubOne("u1", NAME, List.of(STAYS), List.of("10.0.0.2"));
        org.junit.jupiter.api.Assertions.assertFalse(LightbitsAcl.update(client, NAME, true, acl -> false, nqns -> List.of("10.0.0.2")));
        wm.verify(0, putRequestedFor(urlEqualTo(VOLUMES + "/u1")));
    }

    @Test
    void withoutAResolverOrWithUnknownIpsTheIpAclIsLeftAlone() {
        stubOne("u1", NAME, List.of(STAYS), List.of("10.0.0.2"));
        LightbitsAcl.update(client, NAME, true, acl -> acl.add(GONE));
        wm.verify(1, putRequestedFor(urlEqualTo(VOLUMES + "/u1")).withRequestBody(
                com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*IPAcl.*")));
        wm.resetRequests();
        wm.resetScenarios();
        // a host in the list has not reported its IPs: union is null, the IP ACL is not touched
        LightbitsAcl.update(client, NAME, true, acl -> acl.add(GONE),
                nqns -> LightbitsHostIps.union(nqns, n -> STAYS.equals(n) ? List.of("10.0.0.2") : List.of()));
        wm.verify(1, putRequestedFor(urlEqualTo(VOLUMES + "/u1")).withRequestBody(
                com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*IPAcl.*")));
    }

    @Test
    void aGrantWithAnotherHostsIpsUnknownStillAddsTheGrantedHostsIps() {
        LightbitsAcl.IpResolver unknown = nqns -> LightbitsHostIps.union(nqns, n -> STAYS.equals(n) ? List.of() : List.of("10.0.0.9"));
        // the granted host's NQN is already in the list (a host sharing it): only its IPs are missing
        stubOne("u1", NAME, List.of(STAYS, GONE), List.of("10.0.0.2"));
        org.junit.jupiter.api.Assertions.assertTrue(LightbitsAcl.update(client, NAME, true,
                acl -> acl.contains(GONE) ? false : acl.add(GONE), unknown, List.of("10.0.0.9")));
        wm.verify(1, putRequestedFor(urlEqualTo(VOLUMES + "/u1"))
                .withRequestBody(matchingJsonPath("$.IPAcl.values.length()", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.IPAcl.values[0]", equalTo("10.0.0.2")))
                .withRequestBody(matchingJsonPath("$.IPAcl.values[1]", equalTo("10.0.0.9"))));

        wm.resetAll();
        stubOne("u1", NAME, List.of(STAYS), List.of("ALLOW_NONE"));
        LightbitsAcl.update(client, NAME, true, acl -> acl.add(GONE), unknown, List.of("10.0.0.9"));
        wm.verify(1, putRequestedFor(urlEqualTo(VOLUMES + "/u1"))
                .withRequestBody(matchingJsonPath("$.IPAcl.values.length()", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.IPAcl.values[0]", equalTo("10.0.0.9"))));

        wm.resetAll();
        stubOne("u1", NAME, List.of(STAYS, GONE), List.of("10.0.0.2", "10.0.0.9"));
        org.junit.jupiter.api.Assertions.assertFalse(LightbitsAcl.update(client, NAME, true, acl -> false, unknown,
                List.of("10.0.0.9")), "already there: nothing sent");
        wm.resetAll();
        stubOne("u1", NAME, List.of(STAYS), List.of("ALLOW_ANY"));
        LightbitsAcl.update(client, NAME, true, acl -> acl.add(GONE), unknown, List.of("10.0.0.9"));
        wm.verify(1, putRequestedFor(urlEqualTo(VOLUMES + "/u1")).withRequestBody(
                com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*IPAcl.*")));
    }

    @Test
    void aRefreshWithAnotherHostsIpsUnknownAddsTheRefreshingHostsNewIps() {
        // GONE moved to 10.0.0.9; STAYS has not reported: the union is unknown, so the refresh
        // adds GONE's new address (keeping the old one) instead of leaving GONE out.
        wm.stubFor(get(urlPathEqualTo(VOLUMES)).atPriority(5).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":["
                + volIps("u1", NAME, "1", List.of(STAYS, GONE), List.of("10.0.0.1")) + "]}")));
        stubOne("u1", NAME, List.of(STAYS, GONE), List.of("10.0.0.1"));
        LightbitsAcl.IpResolver unknown = nqns -> LightbitsHostIps.union(nqns, n -> GONE.equals(n) ? List.of("10.0.0.9") : List.of());
        assertEquals(List.of(NAME), LightbitsAcl.refreshIps(client, "cs1", 2, GONE, unknown, List.of("10.0.0.9")));
        wm.verify(1, putRequestedFor(urlEqualTo(VOLUMES + "/u1"))
                .withRequestBody(matchingJsonPath("$.IPAcl.values.length()", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.IPAcl.values[1]", equalTo("10.0.0.9"))));
    }

    @Test
    void refreshIpsTouchesOnlyThisPoolsVolumesOfTheHost() {
        String other = "cs-cs1-p2-bbbbbbbb-2222-3333-4444-555555555555";
        String otherPool = "cs-cs1-p20-dddddddd-2222-3333-4444-555555555555";
        wm.stubFor(get(urlPathEqualTo(VOLUMES)).atPriority(5).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":["
                + volIps("u1", NAME, "1", List.of(GONE), List.of("10.0.0.1")) + ","
                + volIps("u2", other, "1", List.of(STAYS), List.of("10.0.0.2")) + ","
                + volIps("u3", otherPool, "1", List.of(GONE), List.of("10.0.0.1")) + "]}")));
        stubOne("u1", NAME, List.of(GONE), List.of("10.0.0.1"));
        assertEquals(List.of(NAME), LightbitsAcl.refreshIps(client, "cs1", 2, GONE, nqns -> List.of("10.0.0.9")));
        wm.verify(1, putRequestedFor(urlEqualTo(VOLUMES + "/u1")).withRequestBody(matchingJsonPath("$.IPAcl.values[0]", equalTo("10.0.0.9"))));
        wm.verify(0, putRequestedFor(urlEqualTo(VOLUMES + "/u2")));
        wm.verify(0, putRequestedFor(urlEqualTo(VOLUMES + "/u3")));
    }
}
