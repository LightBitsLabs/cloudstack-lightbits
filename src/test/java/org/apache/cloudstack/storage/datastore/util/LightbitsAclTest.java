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
        wm.stubFor(get(urlPathEqualTo(VOLUMES)).atPriority(5).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":["
                + vol("u-mine", mine, "1", GONE) + "," + vol("u-shared", shared, "1", STAYS, GONE) + ","
                + vol("u-clean", clean, "1", STAYS) + "," + vol("u-other", otherPool, "1", GONE) + "]}")));
        stubVolume("u-mine", mine, GONE);
        stubVolume("u-shared", shared, STAYS, GONE);

        assertEquals(List.of(mine, shared), LightbitsAcl.pruneHost(client, "cs-cs1-p2-", GONE));

        wm.verify(1, putRequestedFor(urlEqualTo(VOLUMES + "/u-mine")).withHeader("If-Match", equalTo("1"))
                .withRequestBody(matchingJsonPath("$.acl.values[0]", equalTo("ALLOW_NONE"))));
        wm.verify(1, putRequestedFor(urlEqualTo(VOLUMES + "/u-shared")).withHeader("If-Match", equalTo("1"))
                .withRequestBody(matchingJsonPath("$.acl.values[0]", equalTo(STAYS)))
                .withRequestBody(matchingJsonPath("$.acl.values.length()", equalTo("1"))));
        wm.verify(0, putRequestedFor(urlEqualTo(VOLUMES + "/u-clean")));
        wm.verify(0, putRequestedFor(urlEqualTo(VOLUMES + "/u-other")));
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

        assertEquals(List.of(fine), LightbitsAcl.pruneHost(client, "cs-cs1-p2-", GONE));
    }
}
