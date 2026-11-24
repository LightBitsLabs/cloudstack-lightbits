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
    private final List<StoragePoolHostVO> refs = new ArrayList<>();
    private final List<Command> sent = new ArrayList<>();
    private Function<Command, Answer> agent;
    private boolean reportLockBusy;

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
            if (r == long.class || r == int.class) {
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
        inject(listener, "storagePoolDetailsDao", fake(StoragePoolDetailsDao.class,
                m -> m.getName().equals("listDetailsKeyPairs") ? a -> new HashMap<>(poolDetails) : null));
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
        listener.poolSecrets = id -> LightbitsPoolConfig.fromDetails(new HashMap<>(poolDetails), "jwt");
        listener.clients = cfg -> new LightbitsApiClient("http://localhost:" + wm.port(), "jwt", "default",
                java.net.http.HttpClient.newHttpClient(), 1);
        listener.hostGateLocker = (name, seconds, work) -> {
            if (reportLockBusy && name.startsWith("lightbits.host.report.")) {
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
    void withIpAclTheDataIpsAreRecordedAndTheVolumesRefreshed() {
        option(LightbitsPoolConfig.KEY_IP_ACL, "true");
        listener.hostConnect(HOST, POOL);
        assertEquals("10.0.0.5", detail("lightbits.dataips." + POOL));
        assertTrue(nqnCommands().get(0).getEndpoints() != null, "the agent is asked for its data IPs");
        wm.verify(getRequestedFor(urlPathEqualTo(P + "/volumes")));
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
        assertEquals(fp, detail(LightbitsTrustedHosts.CURRENT_KEY_DETAIL));
        assertEquals("0", detail(LightbitsTrustedHosts.KEYLESS_DETAIL));
        assertEquals("0", detail(LightbitsTrustedHosts.generationDetailKey(POOL)), "a first host starts at generation 0");
        wm.verify(postRequestedFor(urlEqualTo(P + "/trustedHosts")).withRequestBody(matchingJsonPath("$.hostNqn", equalTo(NQN))));
        wm.verify(putRequestedFor(urlEqualTo(P + "/trustedHostSecrets/" + TH_NAME))
                .withRequestBody(matchingJsonPath("$.hostSecret", equalTo(KEY)))
                .withRequestBody(matchingJsonPath("$.targetSecretType", equalTo("Disabled"))));
        assertFalse(nqnCommands().get(0).isTwoWay());
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
        assertEquals(LightbitsTrustedHosts.fingerprint(CTRL), detail(LightbitsTrustedHosts.CURRENT_CTRL_KEY_DETAIL));
        assertEquals("0", detail(LightbitsTrustedHosts.CTRL_KEYLESS_DETAIL));
    }

    @Test
    void anAgentWithoutAKeyClearsTheRegistrationDetails() throws Exception {
        option(LightbitsPoolConfig.KEY_INBAND_AUTH, "true");
        putDetail(LightbitsTrustedHosts.detailKey(POOL), "old");
        putDetail(LightbitsTrustedHosts.CURRENT_KEY_DETAIL, "old");
        agent = cmd -> cmd instanceof LightbitsGetHostNqnCommand
                ? new LightbitsGetHostNqnAnswer((LightbitsGetHostNqnCommand) cmd, NQN, null) : new Answer(cmd);
        listener.hostConnect(HOST, POOL);
        assertNull(detail(LightbitsTrustedHosts.detailKey(POOL)));
        assertNull(detail(LightbitsTrustedHosts.CURRENT_KEY_DETAIL));
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
        assertEquals(LightbitsTrustedHosts.fingerprint(KEY), detail(LightbitsTrustedHosts.CURRENT_KEY_DETAIL));
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
}
