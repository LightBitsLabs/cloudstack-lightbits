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
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.storage.datastore.client.LightbitsApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

class LightbitsTrustedHostsTest {
    private static final String HOSTS = "/api/v2/projects/default/trustedHosts";
    private static final String SECRETS = "/api/v2/projects/default/trustedHostSecrets/";
    private static final String NQN = "nqn.2014-08.org.nvmexpress:uuid:60c10b61-c740-478c-a800-402344d475c9";
    private static final String KEY = "DHHC-1:00:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh+KfiaR:";
    private static final String HOST_UUID = "5d2f7e0a-9c1b-4e3d-8a6f-0b1c2d3e4f50";
    private static final String NAME = LightbitsTrustedHosts.name("cs1", HOST_UUID);

    private WireMockServer wm;
    private LightbitsApiClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        client = new LightbitsApiClient("http://localhost:" + wm.port(), "test-jwt", "default", java.net.http.HttpClient.newHttpClient(), 1);
        wm.stubFor(put(urlEqualTo(SECRETS + NAME)).willReturn(aResponse().withStatus(200).withBody("{}")));
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private static String hosts(String... nameNqn) {
        StringBuilder b = new StringBuilder("{\"hosts\":[");
        for (int i = 0; i + 1 < nameNqn.length; i += 2) {
            b.append(i == 0 ? "" : ",").append("{\"name\":\"").append(nameNqn[i]).append("\",\"hostNqn\":\"").append(nameNqn[i + 1])
                    .append("\",\"authRequired\":\"true\"}");
        }
        return b.append("],\"nextOffset\":\"\"}").toString();
    }

    @Test
    void aNewHostIsCreatedThenGetsItsKeyAsTheHostSecretOnly() {
        wm.stubFor(get(urlEqualTo(HOSTS)).willReturn(aResponse().withStatus(200).withBody(hosts())));
        wm.stubFor(post(urlEqualTo(HOSTS)).willReturn(aResponse().withStatus(200).withBody("{}")));

        LightbitsTrustedHosts.Registration r = LightbitsTrustedHosts.register(client, NAME, NQN, KEY, Map.of("cloudstack-host", "h-uuid"));
        assertEquals("cs-cs1-host-" + HOST_UUID, r.name);
        assertTrue(r.authRequired);
        assertFalse(r.foreignName);
        wm.verify(postRequestedFor(urlEqualTo(HOSTS))
                .withRequestBody(matchingJsonPath("$.name", equalTo(NAME)))
                .withRequestBody(matchingJsonPath("$.hostNqn", equalTo(NQN)))
                .withRequestBody(matchingJsonPath("$.projectName", equalTo("default")))
                .withRequestBody(matchingJsonPath("$.authRequired", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.labels[0].key", equalTo("cloudstack-host"))));
        wm.verify(putRequestedFor(urlEqualTo(SECRETS + NAME))
                .withRequestBody(matchingJsonPath("$.hostSecret", equalTo(KEY)))
                .withRequestBody(matchingJsonPath("$.targetSecretType", equalTo("Disabled"))));
    }

    @Test
    void aHostAlreadyTrustedInTheProjectUnderAnotherNameIsUpdatedThere() {
        wm.stubFor(get(urlEqualTo(HOSTS)).willReturn(aResponse().withStatus(200).withBody(hosts("by-hand", NQN))));
        wm.stubFor(put(urlEqualTo(SECRETS + "by-hand")).willReturn(aResponse().withStatus(200).withBody("{}")));

        LightbitsTrustedHosts.Registration r = LightbitsTrustedHosts.register(client, NAME, NQN, KEY, Map.of());
        assertEquals("by-hand", r.name);
        assertTrue(r.foreignName);
        wm.verify(0, postRequestedFor(urlEqualTo(HOSTS)));
        wm.verify(putRequestedFor(urlEqualTo(SECRETS + "by-hand")).withRequestBody(matchingJsonPath("$.hostSecret", equalTo(KEY))));
    }

    @Test
    void aHostTrustedInAnotherProjectIsNotTouched() {
        wm.stubFor(get(urlEqualTo(HOSTS)).willReturn(aResponse().withStatus(200).withBody(hosts())));
        wm.stubFor(post(urlEqualTo(HOSTS)).willReturn(aResponse().withStatus(409).withBody(
                "{\"code\":6,\"message\":\"host NQN " + NQN + " already used by host f038de70\"}")));

        LightbitsTrustedHosts.Registration r = LightbitsTrustedHosts.register(client, NAME, NQN, KEY, Map.of());
        assertFalse(r.registered());
        assertNull(r.name);
        wm.verify(0, putRequestedFor(urlEqualTo(SECRETS + NAME)));
    }

    @Test
    void aConcurrentRegistrationOfTheSameHostIsFoundAfterThe409() {
        // another pool of the project registered the host between our list and our create
        wm.stubFor(get(urlEqualTo(HOSTS)).inScenario("race").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody(hosts())));
        wm.stubFor(post(urlEqualTo(HOSTS)).inScenario("race")
                .willReturn(aResponse().withStatus(409).withBody("{\"code\":6}")).willSetStateTo("created"));
        wm.stubFor(get(urlEqualTo(HOSTS)).inScenario("race").whenScenarioStateIs("created")
                .willReturn(aResponse().withStatus(200).withBody(hosts(NAME, NQN))));

        LightbitsTrustedHosts.Registration r = LightbitsTrustedHosts.register(client, NAME, NQN, KEY, Map.of());
        assertEquals(NAME, r.name);
        assertFalse(r.foreignName);
        wm.verify(putRequestedFor(urlEqualTo(SECRETS + NAME)));
    }

    @Test
    void theListFollowsNextOffset() {
        wm.stubFor(get(urlEqualTo(HOSTS)).willReturn(aResponse().withStatus(200).withBody(
                "{\"hosts\":[{\"name\":\"a\",\"hostNqn\":\"nqn.a\"}],\"nextOffset\":\"o1\"}")));
        wm.stubFor(get(urlEqualTo(HOSTS + "?offset=o1")).willReturn(aResponse().withStatus(200).withBody(
                "{\"hosts\":[{\"name\":\"b\",\"hostNqn\":\"" + NQN + "\"}],\"nextOffset\":\"\"}")));
        assertEquals(2, client.listTrustedHosts().size());
        assertEquals("b", client.findTrustedHostByNqn(NQN).get().name);
        wm.verify(2, getRequestedFor(urlEqualTo(HOSTS + "?offset=o1")));
    }

    @Test
    void removalDeletesOnlyThePluginsOwnTrustedHosts() {
        wm.stubFor(get(urlEqualTo(HOSTS)).willReturn(aResponse().withStatus(200).withBody(
                hosts(NAME, NQN, "by-hand", NQN, "cs-cs1-host-other", "nqn.other", "cs-cs2-host-" + HOST_UUID, NQN,
                "cs-cs1-b-host-" + HOST_UUID, NQN))));
        wm.stubFor(delete(urlEqualTo(HOSTS + "/" + NAME)).willReturn(aResponse().withStatus(200).withBody("{}")));

        List<String> others = new ArrayList<>();
        assertEquals(List.of(NAME), LightbitsTrustedHosts.unregister(client, "cs1", NQN, others));
        assertEquals(List.of("by-hand", "cs-cs2-host-" + HOST_UUID, "cs-cs1-b-host-" + HOST_UUID), others,
                "another environment's (also one whose name starts with this one's) and hand-made ones are left");
        wm.verify(1, deleteRequestedFor(urlEqualTo(HOSTS + "/" + NAME)));
        wm.verify(0, deleteRequestedFor(urlEqualTo(HOSTS + "/by-hand")));
        wm.verify(0, deleteRequestedFor(urlEqualTo(HOSTS + "/cs-cs1-b-host-" + HOST_UUID)));
    }

    @Test
    void ownershipNeedsTheExactEnvironmentAndAHostPart() {
        assertTrue(LightbitsTrustedHosts.ownedBy("cs-a-host-" + HOST_UUID, "a"));
        assertTrue(LightbitsTrustedHosts.ownedBy("cs-a-host-42", "a"), "the host id fallback of name()");
        assertFalse(LightbitsTrustedHosts.ownedBy("cs-a-b-host-" + HOST_UUID, "a"));
        assertTrue(LightbitsTrustedHosts.ownedBy("cs-a-b-host-" + HOST_UUID, "a-b"));
        assertFalse(LightbitsTrustedHosts.ownedBy("cs-a-host-host-" + HOST_UUID, "a"));
        assertFalse(LightbitsTrustedHosts.ownedBy("cs-a-hand-made", "a"));
        assertFalse(LightbitsTrustedHosts.ownedBy(null, "a"));
    }

    @Test
    void aReusedTrustedHostWithoutARecognizedAuthRequiredIsUnknownNotTrue() {
        for (String field : new String[] {"", ",\"authRequired\":\"maybe\""}) {
            wm.resetAll();
            wm.stubFor(get(urlEqualTo(HOSTS)).willReturn(aResponse().withStatus(200).withBody(
                    "{\"hosts\":[{\"name\":\"staged\",\"hostNqn\":\"" + NQN + "\"" + field + "}],\"nextOffset\":\"\"}")));
            wm.stubFor(put(urlEqualTo(SECRETS + "staged")).willReturn(aResponse().withStatus(200).withBody("{}")));

            LightbitsTrustedHosts.Registration r = LightbitsTrustedHosts.register(client, NAME, NQN, KEY, Map.of());
            assertTrue(r.registered(), field);
            assertNull(r.authRequired, "absent or unrecognized authRequired is unknown: " + field);
        }
    }

    @Test
    void aReusedTrustedHostWithAuthRequiredFalseIsReportedAndLeftAsItIs() {
        wm.stubFor(get(urlEqualTo(HOSTS)).willReturn(aResponse().withStatus(200).withBody(
                "{\"hosts\":[{\"name\":\"staged\",\"hostNqn\":\"" + NQN + "\",\"authRequired\":\"false\"}],\"nextOffset\":\"\"}")));
        wm.stubFor(put(urlEqualTo(SECRETS + "staged")).willReturn(aResponse().withStatus(200).withBody("{}")));

        LightbitsTrustedHosts.Registration r = LightbitsTrustedHosts.register(client, NAME, NQN, KEY, Map.of());
        assertTrue(r.registered());
        assertFalse(r.authRequired);
        wm.verify(putRequestedFor(urlEqualTo(SECRETS + "staged")).withRequestBody(matchingJsonPath("$.hostSecret", equalTo(KEY))));
        wm.verify(0, putRequestedFor(urlEqualTo(HOSTS + "/staged")));
        wm.verify(0, postRequestedFor(urlEqualTo(HOSTS)));
    }

    @Test
    void aTrustedHostAlreadyGoneIsNoError() {
        wm.stubFor(delete(urlEqualTo(HOSTS + "/gone")).willReturn(aResponse().withStatus(404).withBody("{\"code\":5}")));
        assertFalse(client.deleteTrustedHost("gone"));
    }

    @Test
    void theFingerprintIsShortStableAndDoesNotContainTheKey() {
        String fp = LightbitsTrustedHosts.fingerprint(KEY);
        assertTrue(fp.matches("[0-9a-f]{16}"), fp);
        assertEquals(fp, LightbitsTrustedHosts.fingerprint(KEY));
        assertFalse(KEY.contains(fp));
        assertFalse(fp.equals(LightbitsTrustedHosts.fingerprint(KEY.replace("AAEC", "AAED"))));
    }

    @Test
    void aRegistrationCountsOnlyWhenItHoldsTheHostsCurrentKey() {
        String k1 = LightbitsTrustedHosts.fingerprint("DHHC-1:00:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh+KfiaR:");
        String k2 = LightbitsTrustedHosts.fingerprint("DHHC-1:00:AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=:");
        assertNotEquals(k1, k2);
        assertTrue(LightbitsTrustedHosts.holdsCurrentKey(k1, k1));
        assertFalse(LightbitsTrustedHosts.holdsCurrentKey(k1, k2), "a registration of an earlier key does not count");
        assertFalse(LightbitsTrustedHosts.holdsCurrentKey(k1, null), "no current key known: not ready");
        assertFalse(LightbitsTrustedHosts.holdsCurrentKey(null, k1));
        assertFalse(LightbitsTrustedHosts.holdsCurrentKey(null, null));
        assertFalse(LightbitsTrustedHosts.CURRENT_KEY_DETAIL.startsWith(LightbitsTrustedHosts.DETAIL_PREFIX),
                "the host-wide detail is not read as a pool's registration");
    }
}
