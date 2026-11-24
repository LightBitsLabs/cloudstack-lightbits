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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;

import com.cloud.utils.exception.CloudRuntimeException;

class LightbitsHostGateTest {
    private static final long HOST = 7;

    /** One in-process lock per name, standing in for GlobalLock. */
    private static LightbitsHostGate.Locker inProcess() {
        java.util.Map<String, ReentrantLock> locks = new java.util.concurrent.ConcurrentHashMap<>();
        return (name, seconds, work) -> {
            ReentrantLock l = locks.computeIfAbsent(name, n -> new ReentrantLock());
            try {
                if (!l.tryLock(seconds, TimeUnit.SECONDS)) {
                    return false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                work.run();
                return true;
            } finally {
                l.unlock();
            }
        };
    }

    @Test
    void aGrantThatOverlapsTheSweepCannotLeaveTheHostInAnAcl() throws Exception {
        LightbitsHostGate.Locker locker = inProcess();
        Set<Long> removing = java.util.Collections.synchronizedSet(new HashSet<>());
        Set<String> acl = java.util.Collections.synchronizedSet(new HashSet<>());
        CountDownLatch grantInside = new CountDownLatch(1);
        CountDownLatch removalStarted = new CountDownLatch(1);
        List<Throwable> grantErrors = new CopyOnWriteArrayList<>();

        // The grant has read the volume and is about to write when the removal starts.
        Thread grant = new Thread(() -> {
            try {
                LightbitsHostGate.grant(HOST, removing::contains, locker, () -> {
                    grantInside.countDown();
                    try {
                        removalStarted.await(5, TimeUnit.SECONDS);
                        Thread.sleep(100);   // the removal is now waiting for the lock
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    acl.add("nqn-host");
                }, () -> acl.remove("nqn-host"));
            } catch (Throwable t) {
                grantErrors.add(t);
            }
        });
        grant.start();
        assertTrue(grantInside.await(5, TimeUnit.SECONDS));
        Thread removal = new Thread(() -> {
            removalStarted.countDown();
            LightbitsHostGate.removal(HOST, () -> removing.add(HOST), locker, () -> acl.remove("nqn-host"));
        });
        removal.start();
        grant.join(10_000);
        removal.join(10_000);

        // the mark lands before the grant's write finishes: the grant takes its entry out and fails
        assertEquals(1, grantErrors.size(), grantErrors.toString());
        assertTrue(grantErrors.get(0).getMessage().contains("started being removed"));
        assertFalse(acl.contains("nqn-host"), "no ACL keeps the removed host");

        // a grant after the sweep is refused and writes nothing
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class,
                () -> LightbitsHostGate.grant(HOST, removing::contains, locker, () -> acl.add("nqn-host"), () -> acl.remove("nqn-host")));
        assertTrue(e.getMessage().contains("being removed"));
        assertFalse(acl.contains("nqn-host"));
    }

    @Test
    void aGrantForAnotherHostIsNotHeldUp() {
        Set<Long> removing = new HashSet<>(Set.of(HOST));
        List<String> writes = new CopyOnWriteArrayList<>();
        LightbitsHostGate.grant(HOST + 1, removing::contains, inProcess(), () -> writes.add("other"), () -> writes.remove("other"));
        assertEquals(List.of("other"), writes);
    }

    @Test
    void aGrantThatCannotGetTheLockFailsAndWritesNothing() {
        List<String> writes = new CopyOnWriteArrayList<>();
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class,
                () -> LightbitsHostGate.grant(HOST, id -> false, (n, s, w) -> false, () -> writes.add("x"), () -> writes.remove("x")));
        assertTrue(e.getMessage().contains("lock"));
        assertTrue(writes.isEmpty());
    }

    @Test
    void theRemovalSweepsEvenWithoutTheLockAndSaysSo() {
        List<String> steps = new CopyOnWriteArrayList<>();
        boolean locked = LightbitsHostGate.removal(HOST, () -> steps.add("mark"), (n, s, w) -> false, () -> steps.add("sweep"));
        assertFalse(locked);
        assertEquals(List.of("mark", "sweep"), steps);
    }

    @Test
    void whenTheRemovalCannotGetTheLockAGrantStillWritingTakesItsEntryOutAgain() throws Exception {
        // The grant holds the lock and has passed its first check; the removal times out on
        // the lock, marks the host and sweeps without it, and only then does the grant write.
        Set<Long> removing = java.util.Collections.synchronizedSet(new HashSet<>());
        Set<String> acl = java.util.Collections.synchronizedSet(new HashSet<>());
        CountDownLatch grantChecked = new CountDownLatch(1);
        CountDownLatch swept = new CountDownLatch(1);
        List<Throwable> grantErrors = new CopyOnWriteArrayList<>();
        LightbitsHostGate.Locker grantHolds = (n, s, w) -> {
            w.run();
            return true;
        };
        Thread grant = new Thread(() -> {
            try {
                LightbitsHostGate.grant(HOST, id -> {
                    boolean r = removing.contains(id);
                    grantChecked.countDown();
                    return r;
                }, grantHolds, () -> {
                    try {
                        swept.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    acl.add("nqn-host");
                }, () -> acl.remove("nqn-host"));
            } catch (Throwable t) {
                grantErrors.add(t);
            }
        });
        grant.start();
        assertTrue(grantChecked.await(5, TimeUnit.SECONDS));
        boolean locked = LightbitsHostGate.removal(HOST, () -> removing.add(HOST), (n, s, w) -> false, () -> {
            acl.remove("nqn-host");
            swept.countDown();
        });
        grant.join(10_000);

        assertFalse(locked);
        assertFalse(acl.contains("nqn-host"), "the grant's late write was taken out again");
        assertEquals(1, grantErrors.size());
        assertTrue(grantErrors.get(0).getMessage().contains("started being removed"));
    }

    @Test
    void aGrantThatWritesAfterTheHostsDetailsAreDeletedStillTakesItsEntryOut() throws Exception {
        // As whenTheRemovalCannotGetTheLock..., but the removal finishes and CloudStack deletes
        // the host's details (the mark and the NQN) before the grant writes.
        Set<String> details = java.util.Collections.synchronizedSet(new HashSet<>(Set.of("nqn")));
        Set<String> acl = java.util.Collections.synchronizedSet(new HashSet<>());
        LightbitsHostGate.RemovingCheck check = id -> details.contains("removing") || !details.contains("nqn");
        CountDownLatch grantChecked = new CountDownLatch(1);
        CountDownLatch hostGone = new CountDownLatch(1);
        List<Throwable> grantErrors = new CopyOnWriteArrayList<>();
        Thread grant = new Thread(() -> {
            try {
                LightbitsHostGate.grant(HOST, id -> {
                    boolean r = check.isRemoving(id);
                    grantChecked.countDown();
                    return r;
                }, (n, s, w) -> {
                    w.run();
                    return true;
                }, () -> {
                    try {
                        hostGone.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    acl.add("nqn-host");
                }, () -> acl.remove("nqn-host"));
            } catch (Throwable t) {
                grantErrors.add(t);
            }
        });
        grant.start();
        assertTrue(grantChecked.await(5, TimeUnit.SECONDS));
        LightbitsHostGate.removal(HOST, () -> details.add("removing"), (n, s, w) -> false, () -> acl.remove("nqn-host"));
        details.clear();   // CloudStack deletes the host's details after the listeners
        hostGone.countDown();
        grant.join(10_000);

        assertFalse(acl.contains("nqn-host"), "the grant's write after the host was gone was taken out again");
        assertEquals(1, grantErrors.size());
    }

    @Test
    void aConnectDuringAnActiveRemovalCannotClearItsMark() {
        long start = 1_000_000_000L;
        String mark = LightbitsHostGate.markValue(start);
        assertFalse(LightbitsHostGate.markClearable(mark, start), "a connect while the removal runs keeps the mark");
        assertFalse(LightbitsHostGate.markClearable(mark, start + LightbitsHostGate.REMOVAL_LOCK_SECONDS * 1000L + 60_000),
                "still kept after the removal's longest lock wait");
        assertTrue(LightbitsHostGate.markClearable(mark, start + LightbitsHostGate.MARK_HOLD_MILLIS),
                "the mark of a removal that did not go through is cleared at a later connect");
        assertTrue(LightbitsHostGate.markClearable("true", start), "a mark from an earlier version is cleared as before");
    }

    @Test
    void aRegistrationThatFinishesAfterTheRemovalAndTheDetailDeletionSeesTheHostRemoved() throws Exception {
        long host = 4;
        java.util.Map<String, String> details = new java.util.concurrent.ConcurrentHashMap<>(java.util.Map.of("nvme.hostnqn", "nqn.h"));
        com.cloud.host.dao.HostDetailsDao dao = (com.cloud.host.dao.HostDetailsDao) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {com.cloud.host.dao.HostDetailsDao.class}, (proxy, m, args) -> {
                    if ("findDetail".equals(m.getName()) && Long.valueOf(host).equals(args[0])) {
                        String v = details.get((String) args[1]);
                        return v == null ? null : new com.cloud.host.DetailVO(host, (String) args[1], v);
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        LightbitsHostGate.RemovingCheck removing = LightbitsHostGate.removingCheck(dao, "nvme.hostnqn");
        assertFalse(removing.isRemoving(host), "a connected host is not being removed");

        java.util.Set<String> trusted = java.util.concurrent.ConcurrentHashMap.newKeySet();
        CountDownLatch removed = new CountDownLatch(1);
        Thread registration = new Thread(() -> {
            try {
                removed.await(5, TimeUnit.SECONDS);   // paused until the removal and the detail deletion are done
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            trusted.add("nqn.h");                     // the in-flight registration writes
            if (removing.isRemoving(host)) {          // and checks after the write
                trusted.remove("nqn.h");
            }
        });
        registration.start();
        details.put(LightbitsHostGate.REMOVING_DETAIL, "true");   // removal: mark,
        assertTrue(removing.isRemoving(host));
        trusted.remove("nqn.h");                                  // sweep,
        details.clear();                                          // then CloudStack deletes the details
        removed.countDown();
        registration.join(5000);
        assertTrue(trusted.isEmpty(), "the removed host does not stay trusted");
    }

    @Test
    void aGrantReadsItsHostsIpsUnderTheLocksSoItsOwnRefreshCannotLeaveItOut() throws Exception {
        // Host B is granted while its own refresh moves it from 10.0.0.1 to 10.0.0.2, and host A
        // on the volume has no known IPs (union unknown: each writer only adds its host's IPs).
        // The grant reads B's IPs inside the host and pool locks the refresh also takes, and the
        // refresh adds B's new IPs: whichever runs first, the volume ends with B's current IP.
        for (boolean grantFirst : new boolean[] {true, false}) {
            long pool = 3;
            long hostB = 2;
            LightbitsHostGate.Locker locker = inProcess();
            java.util.Map<Long, String> detail = new java.util.concurrent.ConcurrentHashMap<>(java.util.Map.of(hostB, "10.0.0.1"));
            java.util.concurrent.atomic.AtomicReference<List<String>> volumeIps = new java.util.concurrent.atomic.AtomicReference<>(
                    List.of("ALLOW_NONE"));
            CountDownLatch first = new CountDownLatch(1);
            Runnable grantWrite = () -> LightbitsHostGate.grant(hostB, id -> false, locker,
                    () -> LightbitsHostGate.underPoolIpAclLock(locker, pool, true, () -> {
                        List<String> own = List.of(detail.get(hostB));   // read under the locks
                        volumeIps.set(LightbitsAcl.withAdded(volumeIps.get(), own));
                    }), () -> { });
            Runnable refresh = () -> locker.run(LightbitsHostGate.lockName(hostB), 5,
                    () -> locker.run(LightbitsHostGate.poolIpAclLockName(pool), 5, () -> {
                        detail.put(hostB, "10.0.0.2");
                        volumeIps.set(LightbitsAcl.withAdded(volumeIps.get(), List.of("10.0.0.2")));
                    }));
            Thread a = new Thread(() -> {
                (grantFirst ? grantWrite : refresh).run();
                first.countDown();
            });
            a.start();
            assertTrue(first.await(5, TimeUnit.SECONDS));
            (grantFirst ? refresh : grantWrite).run();
            a.join(5000);
            assertTrue(volumeIps.get().contains("10.0.0.2"), "grantFirst=" + grantFirst + ": " + volumeIps.get());
        }
    }

    @Test
    void aGrantForAnotherHostCannotWriteStaleIpsAfterTheRefresh() throws Exception {
        // Host A is in the volume and moves from 10.0.0.1 to 10.0.0.2; a grant for host B resolves
        // the IPs while A's refresh runs. Every writer holds the pool's IP ACL lock while it
        // resolves and writes, so the grant either writes before the refresh (which then fixes
        // the IPs) or resolves A's new IP. The volume never ends with A's old IP.
        long pool = 3;
        long hostA = 1;
        long hostB = 2;
        LightbitsHostGate.Locker locker = inProcess();
        java.util.Map<Long, String> detail = new java.util.concurrent.ConcurrentHashMap<>(java.util.Map.of(hostA, "10.0.0.1",
                hostB, "10.0.0.9"));
        java.util.concurrent.atomic.AtomicReference<Set<String>> volumeIps = new java.util.concurrent.atomic.AtomicReference<>(
                Set.of("10.0.0.1"));
        CountDownLatch grantResolved = new CountDownLatch(1);
        CountDownLatch refreshTried = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        Thread grant = new Thread(() -> {
            try {
                LightbitsHostGate.grant(hostB, id -> false, locker,
                        () -> LightbitsHostGate.underPoolIpAclLock(locker, pool, true, () -> {
                            Set<String> resolved = Set.of(detail.get(hostA), detail.get(hostB));   // the IPs of A and B
                            grantResolved.countDown();
                            try {
                                refreshTried.await(5, TimeUnit.SECONDS);
                                Thread.sleep(100);   // the refresh is now waiting for the pool lock
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            volumeIps.set(resolved);
                        }), () -> { });
            } catch (Throwable t) {
                errors.add(t);
            }
        });
        grant.start();
        assertTrue(grantResolved.await(5, TimeUnit.SECONDS));

        // Host A reports its new IP: host lock, then pool lock, store, refresh what is stale.
        Thread refresh = new Thread(() -> locker.run(LightbitsHostGate.lockName(hostA), 5, () -> {
            refreshTried.countDown();
            assertTrue(locker.run(LightbitsHostGate.poolIpAclLockName(pool), 5, () -> {
                detail.put(hostA, "10.0.0.2");
                Set<String> want = Set.of(detail.get(hostA), detail.get(hostB));
                if (!volumeIps.get().equals(want)) {
                    volumeIps.set(want);
                }
            }));
        }));
        refresh.start();
        grant.join(5000);
        refresh.join(5000);

        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(Set.of("10.0.0.2", "10.0.0.9"), volumeIps.get());
    }

    @Test
    void withoutIpAclNoPoolLockIsTakenAndWithoutTheLockNothingIsWritten() {
        LightbitsHostGate.Locker never = (name, seconds, work) -> false;
        List<String> ran = new CopyOnWriteArrayList<>();
        LightbitsHostGate.underPoolIpAclLock(never, 3, false, () -> ran.add("write"));
        assertEquals(List.of("write"), ran);
        assertThrows(CloudRuntimeException.class, () -> LightbitsHostGate.underPoolIpAclLock(never, 3, true, () -> ran.add("x")));
        assertEquals(List.of("write"), ran);
    }
}
