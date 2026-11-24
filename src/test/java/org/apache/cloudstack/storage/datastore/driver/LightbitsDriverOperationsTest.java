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
package org.apache.cloudstack.storage.datastore.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.apache.cloudstack.engine.subsystem.api.storage.CreateCmdResult;
import com.cloud.agent.api.to.DataObjectType;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.command.CommandResult;
import org.apache.cloudstack.storage.datastore.client.LightbitsApiClient;
import org.apache.cloudstack.storage.datastore.client.VolumeExtras;
import org.apache.cloudstack.storage.datastore.client.model.Snapshot;
import org.apache.cloudstack.storage.datastore.client.model.Volume;
import org.apache.cloudstack.storage.datastore.util.LightbitsHostGate;
import org.apache.cloudstack.storage.datastore.util.LightbitsPoolConfig;
import org.apache.cloudstack.storage.datastore.util.LightbitsUtil;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.storage.VolumeVO;

/**
 * The driver's volume operations against an in-memory Lightbits cluster (a LightbitsApiClient
 * subclass, through the driver's clientFor seam) and fakes of the CloudStack objects and DAOs:
 * create with offering options, delete, grant and revoke (NQN and IP ACL), resize, snapshot
 * and revert guards. The client's HTTP side has its own tests.
 */
class LightbitsDriverOperationsTest {
    private static final long POOL = 7;
    private static final String POOL_UUID = "387e455e-bb68-498f-9ef3-04c463815e5d";
    private static final String VOL_UUID = "aaaaaaaa-1111-2222-3333-444444444444";
    private static final String NQN = "nqn.2014-08.org.nvmexpress:uuid:fb8d86b3-cd82-4f5c-b341-1275b9a5eee3";

    /** An in-memory Lightbits cluster: volumes, snapshots and policies, and every change it was asked for. */
    static class FakeCluster extends LightbitsApiClient {
        final Map<String, Volume> volumes = new LinkedHashMap<>();
        final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        final java.util.Set<String> policies = new java.util.HashSet<>();
        Map<String, Object> lastCreate;
        private int next = 1;

        FakeCluster() {
            super("http://127.0.0.1:1", "jwt", "default");
        }

        private static Volume.Acl acl(List<String> values) {
            Volume.Acl a = new Volume.Acl();
            a.setValues(new ArrayList<>(values));
            return a;
        }

        Volume add(String name, long size, List<String> hosts) {
            Volume v = new Volume();
            v.setUuid("v" + next++);
            v.setName(name);
            v.setState("Available");
            v.setSize(String.valueOf(size));
            v.setEtag("1");
            v.setAcl(acl(hosts.isEmpty() ? List.of("ALLOW_NONE") : hosts));
            volumes.put(v.getUuid(), v);
            return v;
        }

        @Override
        public Volume createVolume(String name, long sizeBytes, int replicaCount, List<String> aclNqns, boolean compression,
                                   String qosPolicyName, VolumeExtras extras) {
            calls.add("create " + name);
            lastCreate = new HashMap<>();
            lastCreate.put("size", sizeBytes);
            lastCreate.put("replicas", replicaCount);
            lastCreate.put("compression", compression);
            lastCreate.put("qos", qosPolicyName);
            lastCreate.put("labels", extras.getLabels());
            lastCreate.put("failureDomains", extras.getFailureDomains());
            lastCreate.put("unencrypted", extras.isUnencrypted());
            lastCreate.put("ipAclNone", extras.isIpAclNone());
            Volume v = add(name, sizeBytes, List.of());
            if (extras.isIpAclNone()) {
                v.setIpAcl(acl(List.of("ALLOW_NONE")));
            }
            v.setQosPolicyName(qosPolicyName);
            return v;
        }

        @Override
        public Optional<Volume> getVolumeByName(String name) {
            return volumes.values().stream().filter(v -> v.getName().equals(name) && !v.isDeletedOrDeleting()).findFirst();
        }

        @Override
        public Optional<Volume> getVolumeByNameIncludingDeleting(String name) {
            return volumes.values().stream().filter(v -> v.getName().equals(name)).findFirst();
        }

        @Override
        public boolean deleteVolume(String uuid) {
            calls.add("delete " + uuid);
            return volumes.remove(uuid) != null;
        }

        @Override
        public void waitForVolumeGone(String uuid) {
            calls.add("waitGone " + uuid);
        }

        @Override
        public Optional<Snapshot> getSnapshotByName(String name) {
            return snapshots.values().stream().filter(s -> s.getName().equals(name)).findFirst();
        }

        @Override
        public boolean deleteSnapshot(String uuid) {
            calls.add("deleteSnapshot " + uuid);
            return snapshots.remove(uuid) != null;
        }

        @Override
        public Snapshot createSnapshot(String name, String sourceVolumeUuid) {
            calls.add("snapshot " + name + " of " + sourceVolumeUuid);
            return addSnapshot(name, sourceVolumeUuid, volumes.get(sourceVolumeUuid).getSizeBytes());
        }

        Snapshot addSnapshot(String name, String source, long size) {
            Snapshot s = new Snapshot();
            s.setUuid("s" + next++);
            s.setName(name);
            s.setState("Available");
            s.setSourceVolumeUUID(source);
            s.setSize(String.valueOf(size));
            snapshots.put(s.getUuid(), s);
            return s;
        }

        @Override
        public void rollbackVolume(String volumeUuid, String snapshotUuid, String ifMatchEtag) {
            calls.add("rollback " + volumeUuid + " to " + snapshotUuid + " if " + ifMatchEtag);
        }

        @Override
        public void resizeVolume(String uuid, long newSizeBytes) {
            calls.add("resize " + uuid + " " + newSizeBytes);
            volumes.get(uuid).setSize(String.valueOf(newSizeBytes));
        }

        @Override
        public void updateVolumeAcl(String uuid, List<String> nqns, List<String> ips, String ifMatchEtag) {
            Volume v = volumes.get(uuid);
            calls.add("acl " + uuid + " " + nqns + " ips " + ips + " if " + ifMatchEtag);
            v.setAcl(acl(nqns.isEmpty() ? List.of("ALLOW_NONE") : nqns));
            if (ips != null) {
                v.setIpAcl(acl(ips.isEmpty() ? List.of("ALLOW_NONE") : ips));
            }
            v.setEtag(String.valueOf(Integer.parseInt(v.getEtag()) + 1));
        }

        /** Effective capacity the nodes report; 0 models a cluster that reports none. */
        long effectiveBytes = 30L << 40;

        @Override
        public NodeCapacity nodeCapacity() {
            return new NodeCapacity(3, effectiveBytes, 20L << 40);
        }

        @Override
        public List<TrustedHost> listTrustedHosts() {
            return new ArrayList<>();
        }

        @Override
        public List<Volume> listVolumes() {
            return new ArrayList<>(volumes.values());
        }

        @Override
        public boolean qosPolicyExists(String policyName) {
            return policies.contains(policyName);
        }

        @Override
        public List<IopsPolicy> listIopsPolicies() {
            return List.of(new IopsPolicy("p-1k", 1024, 1024), new IopsPolicy("p-5k", 5120, 5120));
        }

        @Override
        public void updateVolumeQosPolicy(String uuid, String qosPolicyName) {
            calls.add("qos " + uuid + " " + qosPolicyName);
            volumes.get(uuid).setQosPolicyName(qosPolicyName);
        }
    }

    private FakeCluster cluster;
    private LightbitsPrimaryDataStoreDriver driver;
    private final Map<String, String> poolDetails = new HashMap<>();
    private final Map<String, String> offering = new HashMap<>();
    private final Map<String, String> hostDetails = new HashMap<>();
    private VolumeVO volumeRow;

    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> type, Function<String, Function<Object[], Object>> handler) {
        return (T) Proxy.newProxyInstance(LightbitsDriverOperationsTest.class.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            Function<Object[], Object> h = handler.apply(m.getName());
            if (h != null) {
                return h.apply(a);
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
            return null;
        });
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = LightbitsPrimaryDataStoreDriver.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        cluster = new FakeCluster();
        poolDetails.putAll(LightbitsPoolConfig.fromUrl("lightbits://10.0.0.1/default?environment=cs1&replicas=3", "jwt")
                .toDetailsWithoutSecret());
        driver = new LightbitsPrimaryDataStoreDriver() {
            @Override
            LightbitsPoolConfig configForPool(long poolId) {
                return LightbitsPoolConfig.fromDetails(new HashMap<>(poolDetails), "jwt");
            }

            @Override
            LightbitsApiClient clientFor(LightbitsPoolConfig cfg) {
                return cluster;
            }
        };
        inject(driver, "diskOfferingDetailsDao", fake(org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao.class,
                n -> n.equals("getDetail") ? a -> offering.get((String) a[1]) : null));
        inject(driver, "serviceOfferingDao", fake(com.cloud.service.dao.ServiceOfferingDao.class, n -> null));
        inject(driver, "serviceOfferingDetailsDao", fake(com.cloud.service.dao.ServiceOfferingDetailsDao.class, n -> null));
        inject(driver, "accountDao", fake(com.cloud.user.dao.AccountDao.class, n -> n.equals("findByIdIncludingRemoved")
                ? a -> fake(com.cloud.user.Account.class, m -> m.equals("getUuid") ? x -> "acct-uuid" : null) : null));
        inject(driver, "templateDao", fake(com.cloud.storage.dao.VMTemplateDao.class, n -> null));
        volumeRow = newVolumeRow();
        inject(driver, "volumeDao", fake(com.cloud.storage.dao.VolumeDao.class, n -> n.equals("findById") ? a -> volumeRow : null));
        inject(driver, "hostDetailsDao", fake(com.cloud.host.dao.HostDetailsDao.class, n -> {
            switch (n) {
                case "findDetail": return a -> hostDetails.containsKey((String) a[1])
                        ? new DetailVO((Long) a[0], (String) a[1], hostDetails.get((String) a[1])) : null;
                case "findByName": return a -> hostDetails.containsKey((String) a[0])
                        ? new ArrayList<>(List.of(new DetailVO(1L, (String) a[0], hostDetails.get((String) a[0])))) : new ArrayList<>();
                default: return null;
            }
        }));
        inject(driver, "storagePoolDetailsDao", fake(org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao.class, n -> {
            switch (n) {
                case "listDetailsKeyPairs": return a -> new HashMap<>(poolDetails);
                case "addDetail": return a -> poolDetails.put((String) a[1], (String) a[2]);
                case "removeDetail": return a -> poolDetails.remove((String) a[1]);
                default: return null;
            }
        }));
        driver.hostGateLocker = (name, seconds, work) -> {
            work.run();
            return true;
        };
        hostDetails.put(LightbitsPrimaryDataStoreDriver.HOST_NQN_DETAIL, NQN);
    }

    private static VolumeVO newVolumeRow() throws Exception {
        java.lang.reflect.Constructor<VolumeVO> c = VolumeVO.class.getDeclaredConstructor();
        c.setAccessible(true);
        VolumeVO v = c.newInstance();
        Field pool = VolumeVO.class.getDeclaredField("poolId");
        pool.setAccessible(true);
        pool.set(v, POOL);
        return v;
    }

    private DataStore store() {
        return fake(DataStore.class, n -> {
            switch (n) {
                case "getId": return a -> POOL;
                case "getUuid": return a -> POOL_UUID;
                default: return null;
            }
        });
    }

    private VolumeInfo volume(long size, Object payload) {
        VolumeObjectTO to = new VolumeObjectTO();
        DataStore store = store();
        return fake(VolumeInfo.class, n -> {
            switch (n) {
                case "getType": return a -> DataObjectType.VOLUME;
                case "getUuid": return a -> VOL_UUID;
                case "getId": return a -> 11L;
                case "getSize": return a -> size;
                case "getDiskOfferingId": return a -> 5L;
                case "getMaxIops": return a -> null;
                case "getAccountId": return a -> 2L;
                case "getVolumeType": return a -> com.cloud.storage.Volume.Type.DATADISK;
                case "getTO": return a -> to;
                case "getDataStore": return a -> store;
                case "getpayload": return a -> payload;
                default: return null;
            }
        });
    }

    private SnapshotInfo snapshot(String uuid, VolumeInfo base) {
        SnapshotObjectTO to = new SnapshotObjectTO();
        return fake(SnapshotInfo.class, n -> {
            switch (n) {
                case "getUuid": return a -> uuid;
                case "getBaseVolume": return a -> base;
                case "getTO": return a -> to;
                default: return null;
            }
        });
    }

    private static Host host(long id) {
        return fake(Host.class, n -> {
            switch (n) {
                case "getId": return a -> id;
                case "getUuid": return a -> "host-" + id;
                case "getName": return a -> "host-" + id;
                default: return null;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <R> AsyncCompletionCallback<R> capture(List<R> into) {
        return (AsyncCompletionCallback<R>) fake(AsyncCompletionCallback.class, n -> n.equals("complete") ? a -> {
            into.add((R) a[0]);
            return null;
        } : null);
    }

    private String volName() {
        return LightbitsUtil.volumeName("cs1", POOL, VOL_UUID);
    }

    // ---- create ----

    @Test
    void aVolumeIsCreatedWithThePoolDefaultsAndLabels() {
        List<CreateCmdResult> done = new ArrayList<>();
        driver.createAsync(store(), volume(1L << 30, null), capture(done));
        assertNull(done.get(0).getResult(), String.valueOf(done.get(0).getResult()));
        Volume v = cluster.getVolumeByName(volName()).orElseThrow();
        assertEquals(v.getUuid(), done.get(0).getPath());
        assertEquals(3, cluster.lastCreate.get("replicas"));
        assertEquals(Map.of("cloudstack-volume", VOL_UUID, "cloudstack-account", "acct-uuid", "cloudstack-pool", POOL_UUID),
                cluster.lastCreate.get("labels"));
        assertEquals(false, cluster.lastCreate.get("ipAclNone"));
        assertNull(cluster.lastCreate.get("qos"));
    }

    @Test
    void offeringDetailsOverrideThePoolForReplicasCompressionPlacementAndQos() {
        offering.put(LightbitsPrimaryDataStoreDriver.REPLICAS_DETAIL, "1");
        offering.put(LightbitsPrimaryDataStoreDriver.COMPRESSION_DETAIL, "false");
        offering.put(LightbitsPrimaryDataStoreDriver.PLACEMENT_DETAIL, "fd-a");
        offering.put(LightbitsPrimaryDataStoreDriver.QOS_POLICY_DETAIL, "gold");
        cluster.policies.add("gold");
        poolDetails.put(LightbitsPoolConfig.KEY_IP_ACL, "true");
        List<CreateCmdResult> done = new ArrayList<>();
        driver.createAsync(store(), volume(1L << 30, null), capture(done));
        assertNull(done.get(0).getResult(), String.valueOf(done.get(0).getResult()));
        assertEquals(1, cluster.lastCreate.get("replicas"));
        assertEquals(false, cluster.lastCreate.get("compression"));
        assertEquals(List.of("fd-a"), cluster.lastCreate.get("failureDomains"));
        assertEquals("gold", cluster.lastCreate.get("qos"));
        assertEquals(true, cluster.lastCreate.get("ipAclNone"), "with ipAcl a volume starts at ALLOW_NONE");
    }

    @Test
    void anUnknownOfferingPolicyFailsTheCreateNamingTheDetail() {
        offering.put(LightbitsPrimaryDataStoreDriver.QOS_POLICY_DETAIL, "missing");
        List<CreateCmdResult> done = new ArrayList<>();
        driver.createAsync(store(), volume(1L << 30, null), capture(done));
        assertTrue(done.get(0).getResult().contains(LightbitsPrimaryDataStoreDriver.QOS_POLICY_DETAIL), done.get(0).getResult());
        assertTrue(cluster.volumes.isEmpty(), "nothing is created");
    }

    @Test
    void maxIopsPicksTheSmallestPolicyThatFitsWithQosFromIops() {
        poolDetails.put(LightbitsPoolConfig.KEY_QOS_FROM_IOPS, "true");
        VolumeInfo vol = fake(VolumeInfo.class, n -> {
            switch (n) {
                case "getType": return a -> DataObjectType.VOLUME;
                case "getUuid": return a -> VOL_UUID;
                case "getSize": return a -> 1L << 30;
                case "getDiskOfferingId": return a -> 5L;
                case "getMaxIops": return a -> 3000L;
                case "getVolumeType": return a -> com.cloud.storage.Volume.Type.DATADISK;
                case "getTO": return a -> new VolumeObjectTO();
                default: return null;
            }
        });
        List<CreateCmdResult> done = new ArrayList<>();
        driver.createAsync(store(), vol, capture(done));
        assertNull(done.get(0).getResult(), String.valueOf(done.get(0).getResult()));
        assertEquals("p-5k", cluster.lastCreate.get("qos"));
    }

    @Test
    void anUnsupportedObjectTypeFailsTheCreate() {
        List<CreateCmdResult> done = new ArrayList<>();
        driver.createAsync(store(), fake(VolumeInfo.class, n -> n.equals("getType") ? a -> DataObjectType.SNAPSHOT : null), capture(done));
        assertTrue(done.get(0).getResult().contains("supports VOLUME/TEMPLATE"));
        assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                () -> driver.createAsync(store(), fake(VolumeInfo.class, n -> n.equals("getType") ? a -> DataObjectType.SNAPSHOT : null), null));
    }

    // ---- delete ----

    @Test
    void deleteRemovesTheVolumeWaitsAndDropsItsCloneSourceSnapshot() {
        Volume v = cluster.add(volName(), 1L << 30, List.of());
        cluster.addSnapshot(volName() + "-src", "other", 1L << 30);
        List<CommandResult> done = new ArrayList<>();
        driver.deleteAsync(store(), volume(1L << 30, null), capture(done));
        assertNull(done.get(0).getResult());
        assertTrue(cluster.calls.contains("delete " + v.getUuid()));
        assertTrue(cluster.calls.contains("waitGone " + v.getUuid()));
        assertTrue(cluster.snapshots.isEmpty(), "the clone-source snapshot is deleted");

        done.clear();
        driver.deleteAsync(store(), volume(1L << 30, null), capture(done));
        assertNull(done.get(0).getResult(), "a volume already gone is a successful delete");
    }

    @Test
    void deletingASnapshotDeletesItOnTheCluster() {
        String snapUuid = "bbbbbbbb-1111-2222-3333-444444444444";
        cluster.addSnapshot(LightbitsUtil.volumeName("cs1", POOL, snapUuid), "v0", 1L << 30);
        SnapshotInfo snap = fake(SnapshotInfo.class, n -> {
            switch (n) {
                case "getType": return a -> DataObjectType.SNAPSHOT;
                case "getUuid": return a -> snapUuid;
                default: return null;
            }
        });
        List<CommandResult> done = new ArrayList<>();
        driver.deleteAsync(store(), snap, capture(done));
        assertNull(done.get(0).getResult());
        assertTrue(cluster.snapshots.isEmpty());
    }

    // ---- grant and revoke ----

    @Test
    void grantAddsTheHostNqnAndRevokeRemovesIt() {
        Volume v = cluster.add(volName(), 1L << 30, List.of());
        assertTrue(driver.grantAccess(volume(1L << 30, null), host(1), store()));
        assertEquals(List.of(NQN), v.getAcl().getValues());
        assertNull(v.getIpAcl(), "without ipAcl the IP ACL is not touched");
        assertTrue(driver.grantAccess(volume(1L << 30, null), host(1), store()), "a second grant is a no-op");
        driver.revokeAccess(volume(1L << 30, null), host(1), store());
        assertEquals(List.of("ALLOW_NONE"), v.getAcl().getValues());
    }

    @Test
    void withIpAclAGrantAlsoSetsTheHostsDataIps() {
        poolDetails.put(LightbitsPoolConfig.KEY_IP_ACL, "true");
        hostDetails.put("lightbits.dataips." + POOL, "10.0.0.5");
        Volume v = cluster.add(volName(), 1L << 30, List.of());
        driver.grantAccess(volume(1L << 30, null), host(1), store());
        assertEquals(List.of("10.0.0.5"), v.getIpAcl().getValues());
    }

    @Test
    void withIpAclAHostWithoutDataIpsIsRefused() {
        poolDetails.put(LightbitsPoolConfig.KEY_IP_ACL, "true");
        cluster.add(volName(), 1L << 30, List.of());
        com.cloud.utils.exception.CloudRuntimeException e = assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                () -> driver.grantAccess(volume(1L << 30, null), host(1), store()));
        assertTrue(e.getMessage().contains("has not reported its data IPs"), e.getMessage());
    }

    @Test
    void aHostBeingRemovedIsNotGranted() {
        hostDetails.put(LightbitsHostGate.REMOVING_DETAIL, LightbitsHostGate.markValue(System.currentTimeMillis()));
        Volume v = cluster.add(volName(), 1L << 30, List.of());
        assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                () -> driver.grantAccess(volume(1L << 30, null), host(1), store()));
        assertEquals(List.of("ALLOW_NONE"), v.getAcl().getValues());
    }

    @Test
    void aHostWithoutAnNqnCannotBeGranted() {
        hostDetails.clear();
        cluster.add(volName(), 1L << 30, List.of());
        assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                () -> driver.grantAccess(volume(1L << 30, null), host(1), store()));
    }

    // ---- resize ----

    @Test
    void resizeGrowsAndRefusesToShrink() {
        Volume v = cluster.add(volName(), 1L << 30, List.of());
        com.cloud.storage.ResizeVolumePayload grow = new com.cloud.storage.ResizeVolumePayload(2L << 30, null, null, null, false, null, null, true);
        List<CreateCmdResult> done = new ArrayList<>();
        driver.resize(volume(1L << 30, grow), capture(done));
        assertNull(done.get(0).getResult(), String.valueOf(done.get(0).getResult()));
        assertEquals(2L << 30, v.getSizeBytes());

        com.cloud.storage.ResizeVolumePayload shrink = new com.cloud.storage.ResizeVolumePayload(1L << 30, null, null, null, false, null, null, true);
        done.clear();
        driver.resize(volume(2L << 30, shrink), capture(done));
        assertTrue(done.get(0).getResult().contains("cannot shrink"), done.get(0).getResult());
    }

    @Test
    void anOfferingChangeMovesTheVolumeToTheOfferingsPolicy() {
        Volume v = cluster.add(volName(), 1L << 30, List.of());
        offering.put(LightbitsPrimaryDataStoreDriver.QOS_POLICY_DETAIL, "silver");
        List<CreateCmdResult> done = new ArrayList<>();
        driver.resize(volume(1L << 30, null), capture(done));
        assertNull(done.get(0).getResult());
        assertEquals("silver", v.getQosPolicyName());
    }

    // ---- snapshots ----

    @Test
    void aSnapshotIsTakenOfTheBaseVolume() {
        Volume v = cluster.add(volName(), 1L << 30, List.of());
        String snapUuid = "cccccccc-1111-2222-3333-444444444444";
        List<CreateCmdResult> done = new ArrayList<>();
        driver.takeSnapshot(snapshot(snapUuid, volume(1L << 30, null)), capture(done));
        assertNull(done.get(0).getResult(), String.valueOf(done.get(0).getResult()));
        assertTrue(cluster.calls.contains("snapshot " + LightbitsUtil.volumeName("cs1", POOL, snapUuid) + " of " + v.getUuid()));
    }

    @Test
    void aSnapshotOfAVolumeNoLongerOnThePoolFails() {
        volumeRow = null;
        List<CreateCmdResult> done = new ArrayList<>();
        driver.takeSnapshot(snapshot("dddddddd-1111-2222-3333-444444444444", volume(1L << 30, null)), capture(done));
        assertNotNull(done.get(0).getResult());
        assertTrue(cluster.calls.isEmpty());
    }

    @Test
    void revertRollsBackBoundToTheCheckedRevision() {
        Volume v = cluster.add(volName(), 1L << 30, List.of());
        String snapUuid = "eeeeeeee-1111-2222-3333-444444444444";
        Snapshot s = cluster.addSnapshot(LightbitsUtil.volumeName("cs1", POOL, snapUuid), v.getUuid(), 1L << 30);
        List<CommandResult> done = new ArrayList<>();
        driver.revertSnapshot(null, snapshot(snapUuid, volume(1L << 30, null)), capture(done));
        assertTrue(done.get(0).isSuccess(), String.valueOf(done.get(0).getResult()));
        assertTrue(cluster.calls.contains("rollback " + v.getUuid() + " to " + s.getUuid() + " if 1"));
    }

    @Test
    void revertIsRefusedForAGrownOrAttachedVolume() {
        Volume v = cluster.add(volName(), 2L << 30, List.of());
        String snapUuid = "ffffffff-1111-2222-3333-444444444444";
        cluster.addSnapshot(LightbitsUtil.volumeName("cs1", POOL, snapUuid), v.getUuid(), 1L << 30);
        List<CommandResult> done = new ArrayList<>();
        driver.revertSnapshot(null, snapshot(snapUuid, volume(2L << 30, null)), capture(done));
        assertTrue(done.get(0).getResult().contains("grown after the snapshot"), done.get(0).getResult());

        v.setSize(String.valueOf(1L << 30));
        v.setAcl(new Volume.Acl());
        v.getAcl().setValues(List.of(NQN));
        done.clear();
        driver.revertSnapshot(null, snapshot(snapUuid, volume(1L << 30, null)), capture(done));
        assertTrue(done.get(0).getResult().contains("still exposed"), done.get(0).getResult());
        assertFalse(cluster.calls.stream().anyMatch(c -> c.startsWith("rollback")));
    }

    // ---- simple answers ----

    @Test
    void theDriverDescribesItself() {
        assertNull(driver.getChapInfo(volume(1L << 30, null)), "NVMe/TCP: no CHAP");
        assertTrue(driver.requiresAccessForMigration(volume(1L << 30, null)));
        assertTrue(driver.volumesRequireGrantAccessWhenUsed());
        assertTrue(driver.canProvideVolumeStats());
        assertEquals(0, driver.getUsedIops(null));
        assertFalse(driver.isVmInfoNeeded());
        assertFalse(driver.isVmTagsNeeded("x"));
    }

    @Test
    void theStatisticsPollRetriesAPendingRevocation() {
        String gone = "nqn.2014-08.org.nvmexpress:uuid:00000000-0000-0000-0000-00000000dead";
        Volume v = cluster.add(volName(), 1L << 30, List.of(gone));
        org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.record(
                fake(org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao.class,
                        n -> n.equals("addDetail") ? a -> poolDetails.put((String) a[1], (String) a[2]) : null), POOL, gone);
        com.cloud.utils.Pair<Long, Long> stats = driver.getStorageStats(fake(com.cloud.storage.StoragePool.class,
                n -> n.equals("getId") ? a -> POOL : null));
        assertNotNull(stats, "the statistics are still returned");
        assertEquals(List.of("ALLOW_NONE"), v.getAcl().getValues(), "the removed host is taken out");
        assertTrue(poolDetails.keySet().stream().noneMatch(k -> k.startsWith(
                org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.DETAIL_PREFIX)), "and the record cleared");
    }

    @Test
    void theStatisticsPollRetriesARevocationEvenWhenTheClusterReportsNoCapacity() {
        String gone = "nqn.2014-08.org.nvmexpress:uuid:00000000-0000-0000-0000-00000000beef";
        Volume v = cluster.add(volName(), 1L << 30, List.of(gone));
        org.apache.cloudstack.storage.datastore.util.LightbitsRevocations.record(
                fake(org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao.class,
                        n -> n.equals("addDetail") ? a -> poolDetails.put((String) a[1], (String) a[2]) : null), POOL, gone);
        cluster.effectiveBytes = 0;
        assertNull(driver.getStorageStats(fake(com.cloud.storage.StoragePool.class, n -> n.equals("getId") ? a -> POOL : null)),
                "no statistics this cycle");
        assertEquals(List.of("ALLOW_NONE"), v.getAcl().getValues(), "the removed host is taken out all the same");
    }
}
