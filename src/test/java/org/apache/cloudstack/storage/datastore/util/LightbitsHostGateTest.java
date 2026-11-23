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
                });
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

        assertTrue(grantErrors.isEmpty(), grantErrors.toString());
        assertFalse(acl.contains("nqn-host"), "the sweep ran after the grant's write");

        // a grant after the sweep is refused and writes nothing
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class,
                () -> LightbitsHostGate.grant(HOST, removing::contains, locker, () -> acl.add("nqn-host")));
        assertTrue(e.getMessage().contains("being removed"));
        assertFalse(acl.contains("nqn-host"));
    }

    @Test
    void aGrantForAnotherHostIsNotHeldUp() {
        Set<Long> removing = new HashSet<>(Set.of(HOST));
        List<String> writes = new CopyOnWriteArrayList<>();
        LightbitsHostGate.grant(HOST + 1, removing::contains, inProcess(), () -> writes.add("other"));
        assertEquals(List.of("other"), writes);
    }

    @Test
    void aGrantThatCannotGetTheLockFailsAndWritesNothing() {
        List<String> writes = new CopyOnWriteArrayList<>();
        CloudRuntimeException e = assertThrows(CloudRuntimeException.class,
                () -> LightbitsHostGate.grant(HOST, id -> false, (n, s, w) -> false, () -> writes.add("x")));
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
}
