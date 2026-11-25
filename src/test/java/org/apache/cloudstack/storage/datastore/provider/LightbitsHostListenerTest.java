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
package org.apache.cloudstack.storage.datastore.provider;

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
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.storage.datastore.client.LightbitsApiClient;
import org.apache.cloudstack.storage.datastore.command.LightbitsGetHostNqnAnswer;
import org.apache.cloudstack.storage.datastore.command.LightbitsGetHostNqnCommand;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.util.LightbitsHostGate;
import org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig;
import org.apache.cloudstack.storage.datastore.util.LightbitsTrustedHosts;
import org.apache.cloudstack.storage.datastore.util.LightbitsUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.ModifyStoragePoolCommand;
import com.cloud.host.DetailVO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * The host listener against in-memory fakes of the CloudStack DAOs and agent manager and a
 * WireMock Lightbits API: host connect (NQN, data IPs, in-band registration one-way and
 * two-way, rotation, refusals), readiness, host disconnect and host removal.
 */
class LightbitsHostListenerTest {
    private static final long POOL = 7;
    private static final long HOST = 1;
    private static final String HOST_UUID = "10d919c7-6f40-4db9-8d13-7b5ccba0f08c";
    private static final String NQN = "nqn.2014-08.org.nvmexpress:uuid:fb8d86b3-cd82-4f5c-b341-1275b9a5eee3";
    private static final String KEY = "DHHC-1:00:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh+KfiaR:";
    private static final String CTRL = "DHHC-1:00:AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=:";
    private static final String P = "/api/v2/projects/default";
    private static final String TH_NAME = LightbitsTrustedHosts.name("cs1", HOST_UUID);

    private WireMockServer wm;
    private LightbitsHostListener listener;
    private final Map<Long, DetailVO> details = new LinkedHashMap<>();
    private long nextDetailId = 1;
    private final Map<String, String> poolDetails = new HashMap<>();
    /** Details of other pools, by pool id; a pool not in here shares {@link #poolDetails}. */
    private final Map<Long, Map<String, String>> otherPoolDetails = new HashMap<>();
    private final List<StoragePoolHostVO> refs = new ArrayList<>();
    private final List<Command> sent = new ArrayList<>();
    private Function<Command, Answer> agent;
    private boolean reportLockBusy;
    private boolean nqnLockBusy;
    private final List<String> lockNames = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Function<java.lang.reflect.Method, Function<Object[], Object>> handler) {
        return (T) Proxy.newProxyInstance(LightbitsHostListenerTest.class.getClassLoader(), new Class<?>[] {type}, (proxy, m, args) -> {
            Function<Object[], Object> h = handler.apply(m);
            if (h != null) {
                return h.apply(args);
            }
            Class<?> r = m.getReturnType();
            if (r == boolean.class) {
                return false;
            }
            if (r == long.class) {
                return 0L;
            }
            if (r == int.class) {
                return 0;
            }
            if (List.class.isAssignableFrom(r)) {
                return new ArrayList<>();
            }
            return null;
        });
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private DetailVO detailRow(long hostId, String name) {
        return details.values().stream().filter(d -> d.getHostId() == hostId && d.getName().equals(name)).findFirst().orElse(null);
    }

    String detail(String name) {
        DetailVO d = detailRow(HOST, name);
        return d == null ? null : d.getValue();
    }

    private void putDetail(String name, String value) throws Exception {
        DetailVO d = new DetailVO(HOST, name, value);
        Field id = DetailVO.class.getDeclaredField("id");
        id.setAccessible(true);
        id.setLong(d, nextDetailId);
        details.put(nextDetailId++, d);
    }

    @BeforeEach
    void setUp() throws Exception {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        wm.stubFor(get(urlPathEqualTo(P + "/volumes")).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[]}")));
        wm.stubFor(get(urlPathEqualTo(P + "/trustedHosts")).willReturn(aResponse().withStatus(200)
                .withBody("{\"hosts\":[],\"nextOffset\":\"\"}")));
        wm.stubFor(post(urlEqualTo(P + "/trustedHosts")).willReturn(aResponse().withStatus(200).withBody("{}")));
        wm.stubFor(put(urlEqualTo(P + "/trustedHostSecrets/" + TH_NAME)).willReturn(aResponse().withStatus(200).withBody("{}")));
        wm.stubFor(delete(urlEqualTo(P + "/trustedHosts/" + TH_NAME)).willReturn(aResponse().withStatus(200).withBody("{}")));

        poolDetails.putAll(LightbitsPoolConfig.fromUrl("lightbits://10.0.0.1/default?environment=cs1", "jwt").toDetailsWithoutSecret());
        agent = cmd -> cmd instanceof LightbitsGetHostNqnCommand ? nqnAnswer((LightbitsGetHostNqnCommand) cmd, NQN) : new Answer(cmd);

        listener = new LightbitsHostListener();
        inject(listener, "agentMgr", fake(AgentManager.class, m -> m.getName().equals("easySend") ? a -> {
            sent.add((Command) a[1]);
            return agent.apply((Command) a[1]);
        } : null));
        StoragePool pool = fake(StoragePool.class, m -> {
            switch (m.getName()) {
                case "getId": return a -> POOL;
                case "getUuid": return a -> "387e455e-bb68-498f-9ef3-04c463815e5d";
                case "getPoolType": return a -> StoragePoolType.IscsiLUN;
                case "getHostAddress": return a -> "10.0.0.1";
                case "getPath": return a -> "/default";
                case "getPort": return a -> 443;
                default: return null;
            }
        });
        Object store = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataStore.class, StoragePool.class},
                (proxy, m, a) -> m.getDeclaringClass() == StoragePool.class || m.getName().startsWith("get")
                        ? safeInvoke(m, pool, a) : null);
        inject(listener, "dataStoreMgr", fake(DataStoreManager.class, m -> m.getName().equals("getDataStore") ? a -> store : null));
        inject(listener, "hostDao", fake(HostDao.class, m -> m.getName().equals("findById") ? a -> {
            HostVO h = new HostVO(HOST_UUID);   // the constructor takes the guid; the uuid is set below
            h.setUuid(HOST_UUID);
            h.setName("host-one");
            return h;
        } : null));
        inject(listener, "hostDetailsDao", fake(HostDetailsDao.class, m -> {
            switch (m.getName()) {
                case "findDetail": return a -> detailRow((Long) a[0], (String) a[1]);
                case "persist": return a -> {
                    DetailVO d = (DetailVO) a[0];
                    try {
                        Field id = DetailVO.class.getDeclaredField("id");
                        id.setAccessible(true);
                        id.setLong(d, nextDetailId);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                    details.put(nextDetailId++, d);
                    return d;
                };
                case "update": return a -> {
                    details.put((Long) a[0], (DetailVO) a[1]);
                    return true;
                };
                case "remove": return a -> details.remove((Long) a[0]) != null;
                case "findByName": return a -> new ArrayList<>(details.values().stream()
                        .filter(d -> d.getName().equals(a[0])).collect(java.util.stream.Collectors.toList()));
                default: return null;
            }
        }));
        inject(listener, "storagePoolDetailsDao", fake(StoragePoolDetailsDao.class, m -> {
            switch (m.getName()) {
                case "listDetailsKeyPairs": return a -> new HashMap<>(detailsOf((Long) a[0]));
                case "addDetail": return a -> detailsOf((Long) a[0]).put((String) a[1], (String) a[2]);
                case "removeDetail": return a -> detailsOf((Long) a[0]).remove((String) a[1]);
                default: return null;
            }
        }));
        inject(listener, "storagePoolHostDao", fake(StoragePoolHostDao.class, m -> {
            switch (m.getName()) {
                case "findByPoolHost": return a -> refs.stream().filter(r -> r.getPoolId() == (Long) a[0] && r.getHostId() == (Long) a[1])
                        .findFirst().orElse(null);
                case "persist": return a -> {
                    refs.add((StoragePoolHostVO) a[0]);
                    return a[0];
                };
                case "listByPoolId": return a -> new ArrayList<>(refs.stream().filter(r -> r.getPoolId() == (Long) a[0])
                        .collect(java.util.stream.Collectors.toList()));
                case "listByHostId": return a -> new ArrayList<>(refs.stream().filter(r -> r.getHostId() == (Long) a[0])
                        .collect(java.util.stream.Collectors.toList()));
                case "deleteStoragePoolHostDetails": return a -> {
                    refs.removeIf(r -> r.getHostId() == (Long) a[0] && r.getPoolId() == (Long) a[1]);
                    return null;
                };
                default: return null;
            }
        }));
        StoragePoolVO vo = new StoragePoolVO();
        vo.setId(POOL);
        vo.setStorageProviderName(LightbitsUtil.PROVIDER_NAME);
        inject(listener, "primaryDataStoreDao", fake(PrimaryDataStoreDao.class, m -> {
            switch (m.getName()) {
                case "findById": return a -> vo;
                case "findPoolsByProvider": return a -> new ArrayList<>(List.of(vo));
                default: return null;
            }
        }));
        listener.poolSecrets = id -> LightbitsPoolConfig.fromDetails(new HashMap<>(detailsOf(id)), "jwt");
        listener.clients = cfg -> new LightbitsApiClient("http://localhost:" + wm.port(), "jwt", "default",
                java.net.http.HttpClient.newHttpClient(), 1);
        listener.hostGateLocker = (name, seconds, work) -> {
            lockNames.add(name);
            if (reportLockBusy && name.startsWith("lightbits.host.report.")) {
                return false;
            }
            if (nqnLockBusy && name.startsWith("lightbits.nqn.")) {
                return false;
            }
            work.run();
            return true;
        };
    }

    private static Object safeInvoke(java.lang.reflect.Method m, Object target, Object[] a) throws Exception {
        try {
            return StoragePool.class.getMethod(m.getName(), m.getParameterTypes()).invoke(target, a);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private LightbitsGetHostNqnAnswer nqnAnswer(LightbitsGetHostNqnCommand cmd, String nqn) {
        LightbitsGetHostNqnAnswer a = new LightbitsGetHostNqnAnswer(cmd, nqn, cmd.getEndpoints() == null ? null : List.of("10.0.0.5"));
        if (cmd.isInbandAuth()) {
            if (cmd.isRotateKeys()) {
                a.withRotation(true, null);
            }
            a.withInbandAuth(KEY, 0, null);
            if (cmd.isTwoWay()) {
                a.withCtrlKey(CTRL, 0);
            }
        }
        return a;
    }

    private Map<String, String> detailsOf(long poolId) {
        return otherPoolDetails.getOrDefault(poolId, poolDetails);
    }

    private void option(String key, String value) {
        poolDetails.put(key, value);
    }

    private List<LightbitsGetHostNqnCommand> nqnCommands() {
        List<LightbitsGetHostNqnCommand> out = new ArrayList<>();
        for (Command c : sent) {
            if (c instanceof LightbitsGetHostNqnCommand) {
                out.add((LightbitsGetHostNqnCommand) c);
            }
        }
        return out;
    }

    // ---- host connect: NQN, association, agent registration ----

    @Test
    void aFirstConnectRegistersThePoolAndRecordsTheHostNqnOnce() {
        assertTrue(listener.hostConnect(HOST, POOL));
        assertTrue(sent.get(0) instanceof ModifyStoragePoolCommand, "the pool is registered on the agent first");
        assertEquals(NQN, detail(org.apache.cloudstack.storage.datastore.driver.LightbitsPrimaryDataStoreDriver.HOST_NQN_DETAIL));
        assertEquals(1, refs.size(), "the host-pool association is recorded");

        sent.clear();
        assertTrue(listener.hostConnect(HOST, POOL));
        assertTrue(nqnCommands().isEmpty(), "without ipAcl or inbandAuth a known NQN is not asked for again");
        assertEquals(1, refs.size());
    }

    @Test
    void aFailedAgentRegistrationDoesNotFailTheHostConnect() {
        agent = cmd -> cmd instanceof ModifyStoragePoolCommand ? new Answer(cmd, false, "boom") : nqnAnswer((LightbitsGetHostNqnCommand) cmd, NQN);
        assertTrue(listener.hostConnect(HOST, POOL), "one bad pool must not send the whole host to Alert");
        assertEquals(NQN, detail("nvme.hostnqn"));
        agent = cmd -> null;
        details.clear();
        assertTrue(listener.hostConnect(HOST, POOL));
        assertNull(detail("nvme.hostnqn"), "no answer: nothing recorded, retried at the next connect");
    }

    @Test
    void hostDisconnectedDropsTheAssociation() {
        listener.hostConnect(HOST, POOL);
        assertEquals(1, refs.size());
        assertTrue(listener.hostDisconnected(HOST, POOL));
        assertTrue(refs.isEmpty());
        assertTrue(listener.hostDisconnected(HOST, POOL), "a second disconnect is harmless");
        assertTrue(listener.hostAdded(HOST));
    }

    // ---- IP ACL ----

    @Test
    void withIpAclTheDataIpsAreRecordedAndTheVolumesRefreshed() throws Exception {
        option(LightbitsPoolConfig.KEY_IP_ACL, "true");
        putDetail("nvme.hostnqn", NQN);
        String name = LightbitsUtil.volumeName("cs1", POOL, "aaaaaaaa-1111-2222-3333-444444444444");
        String vol = "{\"UUID\":\"u1\",\"name\":\"" + name + "\",\"state\":\"Available\",\"ETag\":\"1\","
                + "\"acl\":{\"values\":[\"" + NQN + "\"]},\"IPAcl\":{\"values\":[\"10.0.0.99\"]}}";
        String updated = vol.replace("10.0.0.99", "10.0.0.5").replace("\"ETag\":\"1\"", "\"ETag\":\"2\"");
        wm.stubFor(get(urlPathEqualTo(P + "/volumes")).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + vol + "]}")));
        wm.stubFor(get(urlPathEqualTo(P + "/volumes")).withQueryParam("name", equalTo(name)).atPriority(1).inScenario("acl")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + vol + "]}")));
        wm.stubFor(put(urlEqualTo(P + "/volumes/u1")).inScenario("acl").willSetStateTo("put")
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        // after the update the cluster reports it (the client waits for that)
        wm.stubFor(get(urlEqualTo(P + "/volumes/u1")).inScenario("acl").whenScenarioStateIs("put")
                .willReturn(aResponse().withStatus(200).withBody(updated)));
        listener.hostConnect(HOST, POOL);
        assertEquals("10.0.0.5", detail("lightbits.dataips." + POOL));
        assertTrue(nqnCommands().get(0).getEndpoints() != null, "the agent is asked for its data IPs");
        wm.verify(putRequestedFor(urlEqualTo(P + "/volumes/u1"))
                .withRequestBody(matchingJsonPath("$.IPAcl.values[0]", equalTo("10.0.0.5")))
                .withRequestBody(matchingJsonPath("$.acl.values[0]", equalTo(NQN))));
    }

    @Test
    void withTheReportLockBusyOnlyTheNqnIsRecorded() {
        option(LightbitsPoolConfig.KEY_IP_ACL, "true");
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        reportLockBusy = true;
        listener.hostConnect(HOST, POOL);
        assertEquals(NQN, detail("nvme.hostnqn"));
        assertNull(detail("lightbits.dataips." + POOL), "no data IPs without the report lock");
        assertNull(detail(LightbitsTrustedHosts.detailKey(POOL)), "no registration without the report lock");
        wm.verify(0, putRequestedFor(urlEqualTo(P + "/trustedHostSecrets/" + TH_NAME)));
    }

    // ---- in-band authentication ----

    @Test
    void oneWayRegistersTheHostKeyAndRecordsItsFingerprint() {
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        listener.hostConnect(HOST, POOL);
        String fp = LightbitsTrustedHosts.fingerprint(KEY);
        assertEquals(fp, detail(LightbitsTrustedHosts.detailKey(POOL)));
        assertEquals(fp, detail(LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, POOL)));
        assertEquals("0", detail(LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.KEYLESS_DETAIL, POOL)));
        assertEquals("0", detail(LightbitsTrustedHosts.generationDetailKey(POOL)), "a first host starts at generation 0");
        wm.verify(postRequestedFor(urlEqualTo(P + "/trustedHosts")).withRequestBody(matchingJsonPath("$.hostNqn", equalTo(NQN))));
        wm.verify(putRequestedFor(urlEqualTo(P + "/trustedHostSecrets/" + TH_NAME))
                .withRequestBody(matchingJsonPath("$.hostSecret", equalTo(KEY)))
                .withRequestBody(matchingJsonPath("$.targetSecretType", equalTo("Disabled"))));
        assertFalse(nqnCommands().get(0).isTwoWay());
    }

    @Test
    void aHostWhoseKeysMayHaveBeenCopiedIsRecordedUntilItRotates() {
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        agent = cmd -> cmd instanceof LightbitsGetHostNqnCommand
                ? nqnAnswer((LightbitsGetHostNqnCommand) cmd, NQN).withKeysPossiblyExposed(true) : new Answer(cmd);
        listener.hostConnect(HOST, POOL);
        assertEquals("true", detail(LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.KEYS_EXPOSED_DETAIL, POOL)));
        agent = cmd -> cmd instanceof LightbitsGetHostNqnCommand ? nqnAnswer((LightbitsGetHostNqnCommand) cmd, NQN) : new Answer(cmd);
        listener.hostConnect(HOST, POOL);
        String exposed = LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.KEYS_EXPOSED_DETAIL, POOL);
        assertEquals("true", detail(exposed), "kept while the agent sends no report");
        agent = cmd -> cmd instanceof LightbitsGetHostNqnCommand
                ? nqnAnswer((LightbitsGetHostNqnCommand) cmd, NQN).withKeysPossiblyExposed(false) : new Answer(cmd);
        listener.hostConnect(HOST, POOL);
        assertNull(detail(exposed), "cleared once the agent reports the keys are not exposed");
    }

    @Test
    void twoWayRegistersTheControllerKeyAsTheTargetSecret() {
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        option(LightbitsPoolConfig.KEY_INBAND_AUTH_TWO_WAY, "true");
        listener.hostConnect(HOST, POOL);
        assertTrue(nqnCommands().get(0).isTwoWay());
        wm.verify(putRequestedFor(urlEqualTo(P + "/trustedHostSecrets/" + TH_NAME))
                .withRequestBody(matchingJsonPath("$.targetSecretType", equalTo("Enabled")))
                .withRequestBody(matchingJsonPath("$.targetSecret", equalTo(CTRL))));
        assertEquals(LightbitsTrustedHosts.fingerprint(CTRL), detail(LightbitsTrustedHosts.ctrlDetailKey(POOL)));
        assertEquals(LightbitsTrustedHosts.fingerprint(CTRL), detail(LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_CTRL_KEY_DETAIL, POOL)));
        assertEquals("0", detail(LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CTRL_KEYLESS_DETAIL, POOL)));
    }

    @Test
    void anAgentWithoutAKeyClearsTheRegistrationDetails() throws Exception {
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        putDetail(LightbitsTrustedHosts.detailKey(POOL), "old");
        putDetail(LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, POOL), "old");
        agent = cmd -> cmd instanceof LightbitsGetHostNqnCommand
                ? new LightbitsGetHostNqnAnswer((LightbitsGetHostNqnCommand) cmd, NQN, null) : new Answer(cmd);
        listener.hostConnect(HOST, POOL);
        assertNull(detail(LightbitsTrustedHosts.detailKey(POOL)));
        assertNull(detail(LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, POOL)));
        wm.verify(0, putRequestedFor(urlEqualTo(P + "/trustedHostSecrets/" + TH_NAME)));
    }

    @Test
    void aChangedHostNqnIsNotRegistered() throws Exception {
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        putDetail("nvme.hostnqn", "nqn.2014-08.org.nvmexpress:uuid:00000000-0000-0000-0000-000000000001");
        listener.hostConnect(HOST, POOL);
        wm.verify(0, postRequestedFor(urlEqualTo(P + "/trustedHosts")));
        wm.verify(0, putRequestedFor(urlEqualTo(P + "/trustedHostSecrets/" + TH_NAME)));
        assertNull(detail(LightbitsTrustedHosts.detailKey(POOL)));
    }

    @Test
    void aRegistrationOfAHostBeingRemovedIsUndone() throws Exception {
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        wm.stubFor(get(urlPathEqualTo(P + "/trustedHosts")).willReturn(aResponse().withStatus(200)
                .withBody("{\"hosts\":[{\"name\":\"" + TH_NAME + "\",\"hostNqn\":\"" + NQN + "\",\"authRequired\":\"true\"}],\"nextOffset\":\"\"}")));
        putDetail("nvme.hostnqn", NQN);
        putDetail(LightbitsHostGate.REMOVING_DETAIL, LightbitsHostGate.markValue(System.currentTimeMillis()));
        listener.hostConnect(HOST, POOL);
        wm.verify(deleteRequestedFor(urlEqualTo(P + "/trustedHosts/" + TH_NAME)));
        assertNull(detail(LightbitsTrustedHosts.detailKey(POOL)));
    }

    @Test
    void aFailedRegistrationIsRetriedLaterAndLeavesNoRegistration() {
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        wm.stubFor(post(urlEqualTo(P + "/trustedHosts")).willReturn(aResponse().withStatus(400).withBody("{\"message\":\"bad\"}")));
        listener.hostConnect(HOST, POOL);
        assertNull(detail(LightbitsTrustedHosts.detailKey(POOL)));
        assertEquals(LightbitsTrustedHosts.fingerprint(KEY), detail(LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, POOL)));
        // the next connect registers it
        wm.stubFor(post(urlEqualTo(P + "/trustedHosts")).willReturn(aResponse().withStatus(200).withBody("{}")));
        listener.hostConnect(HOST, POOL);
        wm.verify(putRequestedFor(urlEqualTo(P + "/trustedHostSecrets/" + TH_NAME)));
        assertEquals(LightbitsTrustedHosts.fingerprint(KEY), detail(LightbitsTrustedHosts.detailKey(POOL)));
    }

    // ---- rotation ----

    @Test
    void aRaisedGenerationRotatesAnIdleHostAndRecordsIt() throws Exception {
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        option(LightbitsPoolConfig.KEY_INBAND_KEY_GENERATION, "1");
        putDetail(LightbitsTrustedHosts.generationDetailKey(POOL), "0");
        listener.hostConnect(HOST, POOL);
        assertTrue(nqnCommands().get(0).isRotateKeys());
        assertTrue(nqnCommands().get(0).isTwoWay(), "a rotation also asks for the controller key");
        assertEquals("1", detail(LightbitsTrustedHosts.generationDetailKey(POOL)));

        sent.clear();
        listener.hostConnect(HOST, POOL);
        assertFalse(nqnCommands().get(0).isRotateKeys(), "current: not asked again");
    }

    @Test
    void aRefusedRotationKeepsTheGenerationAndIsAskedAgain() throws Exception {
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        option(LightbitsPoolConfig.KEY_INBAND_KEY_GENERATION, "2");
        putDetail(LightbitsTrustedHosts.generationDetailKey(POOL), "1");
        agent = cmd -> {
            if (!(cmd instanceof LightbitsGetHostNqnCommand)) {
                return new Answer(cmd);
            }
            LightbitsGetHostNqnAnswer a = nqnAnswer((LightbitsGetHostNqnCommand) cmd, NQN);
            return a.withRotation(false, "key rotation refused: 3 paths still carry volumes");
        };
        listener.hostConnect(HOST, POOL);
        assertEquals("1", detail(LightbitsTrustedHosts.generationDetailKey(POOL)));
        sent.clear();
        listener.hostConnect(HOST, POOL);
        assertTrue(nqnCommands().get(0).isRotateKeys(), "asked again at the next connect");
    }

    @Test
    void aHostWithKeysOfUnknownAgeRotatesOnceTheGenerationWasRaised() {
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        option(LightbitsPoolConfig.KEY_INBAND_KEY_GENERATION, "3");
        listener.hostConnect(HOST, POOL);
        assertTrue(nqnCommands().get(0).isRotateKeys());
        assertEquals("3", detail(LightbitsTrustedHosts.generationDetailKey(POOL)));
    }

    // ---- host removal ----

    @Test
    void removingAHostPrunesItsAclEntriesAndDeletesItsTrustedHost() throws Exception {
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        putDetail("nvme.hostnqn", NQN);
        String name = LightbitsUtil.volumeName("cs1", POOL, "aaaaaaaa-1111-2222-3333-444444444444");
        String vol = "{\"UUID\":\"u1\",\"name\":\"" + name + "\",\"state\":\"Available\",\"ETag\":\"1\",\"acl\":{\"values\":[\"" + NQN + "\"]}}";
        wm.stubFor(get(urlPathEqualTo(P + "/volumes")).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + vol + "]}")));
        wm.stubFor(put(urlEqualTo(P + "/volumes/u1")).willReturn(aResponse().withStatus(200).withBody("{}")));
        wm.stubFor(get(urlPathEqualTo(P + "/trustedHosts")).willReturn(aResponse().withStatus(200)
                .withBody("{\"hosts\":[{\"name\":\"" + TH_NAME + "\",\"hostNqn\":\"" + NQN + "\",\"authRequired\":\"true\"}],\"nextOffset\":\"\"}")));
        assertTrue(listener.hostAboutToBeRemoved(HOST));
        wm.verify(putRequestedFor(urlEqualTo(P + "/volumes/u1")).withRequestBody(matchingJsonPath("$.acl.values[0]", equalTo("ALLOW_NONE"))));
        wm.verify(deleteRequestedFor(urlEqualTo(P + "/trustedHosts/" + TH_NAME)));
        assertTrue(detail(LightbitsHostGate.REMOVING_DETAIL) != null, "the host is marked as being removed");
        assertTrue(listener.hostRemoved(HOST, 1));
    }

    @Test
    void removingAHostWithoutAnNqnDoesNothing() {
        assertTrue(listener.hostAboutToBeRemoved(HOST));
        wm.verify(0, getRequestedFor(urlPathEqualTo(P + "/volumes")));
        assertNull(detail(LightbitsHostGate.REMOVING_DETAIL));
    }

    @Test
    void aRevocationTheClusterCouldNotTakeStaysPendingAndIsRetried() throws Exception {
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        putDetail("nvme.hostnqn", NQN);
        String name = LightbitsUtil.volumeName("cs1", POOL, "aaaaaaaa-1111-2222-3333-444444444444");
        String vol = "{\"UUID\":\"u1\",\"name\":\"" + name + "\",\"state\":\"Available\",\"ETag\":\"1\",\"acl\":{\"values\":[\"" + NQN + "\"]}}";
        // the cluster API is down during the removal
        wm.stubFor(get(urlPathEqualTo(P + "/volumes")).willReturn(aResponse().withStatus(503).withBody("{}")));
        wm.stubFor(get(urlPathEqualTo(P + "/trustedHosts")).willReturn(aResponse().withStatus(503).withBody("{}")));
        assertTrue(listener.hostAboutToBeRemoved(HOST), "the removal itself is not held up");
        assertEquals(List.of(NQN), org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.pending(
                fake(StoragePoolDetailsDao.class, m -> m.getName().equals("listDetailsKeyPairs") ? a -> poolDetails : null), POOL));

        // CloudStack deletes the removed host's details; the API is back; another host connects
        details.clear();
        wm.stubFor(get(urlPathEqualTo(P + "/volumes")).willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + vol + "]}")));
        wm.stubFor(get(urlPathEqualTo(P + "/volumes")).withQueryParam("name", equalTo(name)).atPriority(1).inScenario("rv")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + vol + "]}")));
        wm.stubFor(put(urlEqualTo(P + "/volumes/u1")).inScenario("rv").willSetStateTo("put")
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        String pruned = vol.replace("\"values\":[\"" + NQN + "\"]", "\"values\":[\"ALLOW_NONE\"]").replace("\"ETag\":\"1\"", "\"ETag\":\"2\"");
        wm.stubFor(get(urlEqualTo(P + "/volumes/u1")).inScenario("rv").whenScenarioStateIs("put")
                .willReturn(aResponse().withStatus(200).withBody(pruned)));
        wm.stubFor(get(urlPathEqualTo(P + "/volumes")).inScenario("rv").whenScenarioStateIs("put")
                .willReturn(aResponse().withStatus(200).withBody("{\"volumes\":[" + pruned + "]}")));
        wm.stubFor(get(urlPathEqualTo(P + "/trustedHosts")).willReturn(aResponse().withStatus(200)
                .withBody("{\"hosts\":[],\"nextOffset\":\"\"}")));
        agent = cmd -> cmd instanceof LightbitsGetHostNqnCommand
                ? nqnAnswer((LightbitsGetHostNqnCommand) cmd, "nqn.2014-08.org.nvmexpress:uuid:00000000-0000-0000-0000-000000000008")
                : new Answer(cmd);   // another host, with its own NQN
        listener.hostConnect(8, POOL);
        wm.verify(putRequestedFor(urlEqualTo(P + "/volumes/u1")).withRequestBody(matchingJsonPath("$.acl.values[0]", equalTo("ALLOW_NONE"))));
        assertTrue(poolDetails.keySet().stream().noneMatch(k -> k.startsWith(
                org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.DETAIL_PREFIX)), "cleared once the cluster confirms");
    }

    @Test
    void aPendingRevocationOfAnNqnAddedBackIsDropped() throws Exception {
        poolDetails.put("lightbits.revoke.x", NQN);
        putDetail("nvme.hostnqn", NQN);   // the machine was added back to CloudStack
        org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.retry(
                fake(StoragePoolDetailsDao.class, m -> {
                    switch (m.getName()) {
                        case "listDetailsKeyPairs": return a -> new HashMap<>(poolDetails);
                        case "removeDetail": return a -> poolDetails.values().remove(NQN);
                        default: return null;
                    }
                }),
                fake(HostDetailsDao.class, m -> {
                    switch (m.getName()) {
                        case "findByName": return a -> new ArrayList<>(details.values());
                        case "findDetail": return a -> detailRow((Long) a[0], (String) a[1]);
                        default: return null;
                    }
                }), "nvme.hostnqn", POOL, LightbitsPoolConfig.fromDetails(new HashMap<>(poolDetails), "jwt"),
                new LightbitsApiClient("http://localhost:" + wm.port(), "jwt", "default", java.net.http.HttpClient.newHttpClient(), 1),
                listener.hostGateLocker);
        assertFalse(poolDetails.containsValue(NQN), "dropped, not revoked");
        assertTrue(lockNames.contains(org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.nqnLockName(NQN)),
                "checked and decided under the NQN's lock");
        wm.verify(0, putRequestedFor(urlPathEqualTo(P + "/volumes/u1")));
    }

    @Test
    void theClustersSubsystemNqnScopesTheHostKeysAndIsKept() {
        String sub = "nqn.2016-01.com.lightbitslabs:uuid:9c17511f-50e4-4620-9d0f-dc139863abcf";
        wm.stubFor(get(urlEqualTo("/api/v2/clusterinfo")).willReturn(aResponse().withStatus(200)
                .withBody("{\"UUID\":\"c1\",\"subsystemNQN\":\"" + sub + "\"}")));
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        listener.hostConnect(HOST, POOL);
        ModifyStoragePoolCommand modify = (ModifyStoragePoolCommand) sent.get(0);
        assertEquals(sub, modify.getDetails().get(com.cloud.hypervisor.kvm.storage.LightbitsStorageAdaptor.DETAIL_INBAND_KEY_SCOPE),
                "the agent gets the scope with the pool");
        assertEquals(sub, nqnCommands().get(0).getKeyScope(), "and with the key request");
        assertEquals(sub, poolDetails.get("lightbits.subsystemnqn"), "kept as a pool detail");
        wm.resetRequests();
        listener.hostConnect(HOST, POOL);
        wm.verify(0, getRequestedFor(urlEqualTo("/api/v2/clusterinfo")));
    }

    @Test
    void aRetryWaitsWhenTheNqnIsBeingRecordedAndAHostRecordsItsNqnUnderTheSameLock() throws Exception {
        poolDetails.put("lightbits.revoke.x", NQN);
        nqnLockBusy = true;
        org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.retry(
                fake(StoragePoolDetailsDao.class, m -> m.getName().equals("listDetailsKeyPairs") ? a -> new HashMap<>(poolDetails) : null),
                fake(HostDetailsDao.class, m -> m.getName().equals("findByName") ? a -> new ArrayList<>(details.values()) : null),
                "nvme.hostnqn", POOL, LightbitsPoolConfig.fromDetails(new HashMap<>(poolDetails), "jwt"),
                new LightbitsApiClient("http://localhost:" + wm.port(), "jwt", "default", java.net.http.HttpClient.newHttpClient(), 1),
                listener.hostGateLocker);
        assertTrue(poolDetails.containsValue(NQN), "kept for a later retry");
        wm.verify(0, getRequestedFor(urlPathEqualTo(P + "/volumes")));

        nqnLockBusy = false;
        lockNames.clear();
        listener.hostConnect(HOST, POOL);
        assertEquals(NQN, detail("nvme.hostnqn"));
        assertTrue(lockNames.contains(org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.nqnLockName(NQN)),
                "a host records its NQN under the NQN's lock");
    }

    @Test
    void theKeyScopeIsReadOnceForTheWholeConnect() {
        String sub = "nqn.2016-01.com.lightbitslabs:uuid:9c17511f-50e4-4620-9d0f-dc139863abcf";
        wm.stubFor(get(urlEqualTo("/api/v2/clusterinfo")).inScenario("ci").whenScenarioStateIs(
                com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED).willSetStateTo("up")
                .willReturn(aResponse().withStatus(400).withBody("{}")));
        wm.stubFor(get(urlEqualTo("/api/v2/clusterinfo")).inScenario("ci").whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(200).withBody("{\"UUID\":\"c1\",\"subsystemNQN\":\"" + sub + "\"}")));
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        listener.hostConnect(HOST, POOL);
        ModifyStoragePoolCommand modify = (ModifyStoragePoolCommand) sent.get(0);
        assertNull(modify.getDetails().get(com.cloud.hypervisor.kvm.storage.LightbitsStorageAdaptor.DETAIL_INBAND_KEY_SCOPE));
        assertNull(nqnCommands().get(0).getKeyScope(), "the key request uses the scope the pool registration got");
        wm.verify(1, getRequestedFor(urlEqualTo("/api/v2/clusterinfo")));
    }

    @Test
    void newKeysAreRegisteredWithTheOtherPoolsOfTheSameKeys() throws Exception {
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        long other = POOL + 1;
        refs.add(new StoragePoolHostVO(other, HOST, ""));
        putDetail(LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, other), "fingerprint-of-old-key");
        listener.hostConnect(HOST, POOL);
        assertEquals(LightbitsTrustedHosts.fingerprint(KEY),
                detail(LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, other)),
                "the other pool's record follows the host's new key");
    }

    @Test
    void aHostWhoseNqnLockStaysBusyRecordsNothingForItThisConnect() {
        nqnLockBusy = true;
        listener.hostConnect(HOST, POOL);
        assertNull(detail("nvme.hostnqn"), "deferred to the next connect");
        nqnLockBusy = false;
        listener.hostConnect(HOST, POOL);
        assertEquals(NQN, detail("nvme.hostnqn"));
    }

    @Test
    void aRemovalRecordsItsPendingRevocationUnderTheNqnLock() throws Exception {
        putDetail("nvme.hostnqn", NQN);
        wm.stubFor(get(urlPathEqualTo(P + "/volumes")).willReturn(aResponse().withStatus(503).withBody("{}")));
        lockNames.clear();
        assertTrue(listener.hostAboutToBeRemoved(HOST));
        assertTrue(lockNames.contains(org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.nqnLockName(NQN)));
        assertTrue(poolDetails.entrySet().stream().anyMatch(e -> e.getKey().startsWith(
                org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.DETAIL_PREFIX) && e.getValue().equals(NQN)), "recorded");
    }

    @Test
    void aPoolOfTheSameClusterThisHostKeysHostWideMovesToTheClustersKeys() throws Exception {
        String sub = "nqn.2016-01.com.lightbitslabs:uuid:9c17511f-50e4-4620-9d0f-dc139863abcf";
        poolDetails.put("lightbits.subsystemnqn", sub);
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        agent = cmd -> cmd instanceof LightbitsGetHostNqnCommand
                ? nqnAnswer((LightbitsGetHostNqnCommand) cmd, NQN).withKeyScope(((LightbitsGetHostNqnCommand) cmd).getKeyScope())
                : new Answer(cmd);
        long other = POOL + 1;
        refs.add(new StoragePoolHostVO(other, HOST, ""));
        String otherKey = LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, other);
        // this host connected the other pool before its scope was known: its keys there are host-wide
        putDetail(LightbitsTrustedHosts.perPool("lightbits.keyscope", other), "host-wide");
        putDetail(otherKey, "fingerprint-of-old-key");
        listener.hostConnect(HOST, POOL);
        assertEquals(sub, detail(LightbitsTrustedHosts.perPool("lightbits.keyscope", POOL)), "the scope this connect used is recorded");
        // the other pool shares the cluster's trusted host: it is given to the agent again with the scope
        assertEquals(2, sent.stream().filter(c -> c instanceof ModifyStoragePoolCommand
                && sub.equals(((ModifyStoragePoolCommand) c).getDetails().get(
                        com.cloud.hypervisor.kvm.storage.LightbitsStorageAdaptor.DETAIL_INBAND_KEY_SCOPE))).count(),
                "the other pool is registered on the agent again, with the scope");
        assertEquals(sub, detail(LightbitsTrustedHosts.perPool("lightbits.keyscope", other)), "and the host records it");
        assertEquals(LightbitsTrustedHosts.fingerprint(KEY), detail(otherKey), "and its records follow the cluster's key");
    }

    private static final String SUB = "nqn.2016-01.com.lightbitslabs:uuid:9c17511f-50e4-4620-9d0f-dc139863abcf";

    /** A scoped rotation of POOL with another in-band pool of the host, with its own details, that the host keys host-wide. */
    private long hostWidePoolOfTheSameCluster(boolean keptScope) throws Exception {
        poolDetails.put("lightbits.subsystemnqn", SUB);
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        option(LightbitsPoolConfig.KEY_INBAND_KEY_GENERATION, "2");
        putDetail(LightbitsTrustedHosts.generationDetailKey(POOL), "1");
        long other = POOL + 1;
        Map<String, String> od = new HashMap<>(LightbitsPoolConfig.fromUrl("lightbits://10.0.0.1/default?environment=cs1", "jwt")
                .toDetailsWithoutSecret());
        od.put(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        od.put(LightbitsPoolConfig.KEY_INBAND_KEY_GENERATION, "5");
        if (keptScope) {
            od.put("lightbits.subsystemnqn", SUB);
        }
        otherPoolDetails.put(other, od);
        refs.add(new StoragePoolHostVO(other, HOST, ""));
        putDetail(LightbitsTrustedHosts.perPool("lightbits.keyscope", other), "host-wide");
        putDetail(LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, other), "fingerprint-of-old-key");
        return other;
    }

    private void scopedAgent(boolean modifyOk) {
        agent = cmd -> cmd instanceof LightbitsGetHostNqnCommand
                ? nqnAnswer((LightbitsGetHostNqnCommand) cmd, NQN).withKeyScope(((LightbitsGetHostNqnCommand) cmd).getKeyScope())
                : modifyOk ? new Answer(cmd) : new Answer(cmd, false, "boom");
    }

    @Test
    void aRotationMovesAHostWidePoolOfTheSameClusterToTheRotatedKeys() throws Exception {
        long other = hostWidePoolOfTheSameCluster(true);
        scopedAgent(true);
        listener.hostConnect(HOST, POOL);
        assertTrue(nqnCommands().get(0).isRotateKeys());
        assertEquals(SUB, detail(LightbitsTrustedHosts.perPool("lightbits.keyscope", other)), "adopted");
        assertEquals("5", detail(LightbitsTrustedHosts.generationDetailKey(other)), "its generation is recorded with the new keys");
        assertEquals(LightbitsTrustedHosts.fingerprint(KEY),
                detail(LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, other)), "registered with the new keys");
    }

    @Test
    void aHostWidePoolWhoseScopeIsNotKeptYetIsReadFromItsCluster() throws Exception {
        long other = hostWidePoolOfTheSameCluster(false);
        wm.stubFor(get(urlEqualTo("/api/v2/clusterinfo"))
                .willReturn(aResponse().withStatus(200).withBody("{\"UUID\":\"c1\",\"subsystemNQN\":\"" + SUB + "\"}")));
        scopedAgent(true);
        listener.hostConnect(HOST, POOL);
        assertEquals(SUB, otherPoolDetails.get(other).get("lightbits.subsystemnqn"), "read and kept");
        assertEquals(SUB, detail(LightbitsTrustedHosts.perPool("lightbits.keyscope", other)), "adopted");
        assertEquals("5", detail(LightbitsTrustedHosts.generationDetailKey(other)));
    }

    @Test
    void aPoolTheAgentCouldNotBeGivenWithTheScopeGetsNoRotatedKeys() throws Exception {
        long other = hostWidePoolOfTheSameCluster(true);
        scopedAgent(false);
        listener.hostConnect(HOST, POOL);
        assertTrue(nqnCommands().get(0).isRotateKeys());
        assertEquals("host-wide", detail(LightbitsTrustedHosts.perPool("lightbits.keyscope", other)), "still host-wide");
        assertNull(detail(LightbitsTrustedHosts.generationDetailKey(other)), "no generation: it rotates at its next connect");
        assertEquals("fingerprint-of-old-key",
                detail(LightbitsTrustedHosts.perPool(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, other)), "not registered with the new keys");
    }
}
