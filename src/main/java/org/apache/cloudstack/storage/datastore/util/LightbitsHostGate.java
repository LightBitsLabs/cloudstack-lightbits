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

import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Keeps a host that is being removed out of volume ACLs. Host removal takes the host out of
 * every ACL (a sweep); a grant for that host that read a volume before the sweep and writes
 * after it would put the host back, and nothing would take it out again once the host is gone.
 *
 * <p>Both run under one lock per host that every management server sees (CloudStack's
 * database-backed GlobalLock). The sweep first marks the host as being removed (host detail
 * {@link #REMOVING_DETAIL}); a grant that gets the lock after that refuses. So a grant either
 * finishes before the sweep starts (and the sweep removes it), or sees the mark and writes
 * nothing. The mark is cleared when the host connects again, in case its removal did not go
 * through.
 */
public final class LightbitsHostGate {
    /** Host detail set while the host is being removed from CloudStack. */
    public static final String REMOVING_DETAIL = "lightbits.removing";
    static final int GRANT_LOCK_SECONDS = 120;
    static final int REMOVAL_LOCK_SECONDS = 600;

    /** Runs work under a named lock; false when the lock was not had within the time. */
    public interface Locker {
        boolean run(String name, int seconds, Runnable work);
    }

    public static final Locker GLOBAL = (name, seconds, work) -> {
        GlobalLock lock = GlobalLock.getInternLock(name);
        try {
            if (!lock.lock(seconds)) {
                return false;
            }
            try {
                work.run();
            } finally {
                lock.unlock();
            }
            return true;
        } finally {
            lock.releaseRef();
        }
    };

    /** Called with the host id; true while the host is marked as being removed. */
    public interface RemovingCheck {
        boolean isRemoving(long hostId);
    }

    private LightbitsHostGate() {
    }

    static String lockName(long hostId) {
        return "lightbits.host.acl." + hostId;
    }

    /** Run a grant's ACL write for the host, unless the host is being removed. */
    public static void grant(long hostId, RemovingCheck removing, Locker locker, Runnable aclWrite) {
        boolean had = locker.run(lockName(hostId), GRANT_LOCK_SECONDS, () -> {
            if (removing.isRemoving(hostId)) {
                throw new CloudRuntimeException("host " + hostId + " is being removed from CloudStack;"
                        + " not granting it access to a Lightbits volume");
            }
            aclWrite.run();
        });
        if (!had) {
            throw new CloudRuntimeException("could not get the ACL lock of host " + hostId + " within "
                    + GRANT_LOCK_SECONDS + " s (its removal may be running); retry the operation");
        }
    }

    /**
     * Mark the host as being removed, then sweep it out of the ACLs, under the host's lock.
     * Without the lock in time the sweep still runs (removal must not be blocked); the caller
     * logs the returned false.
     */
    public static boolean removal(long hostId, Runnable markRemoving, Locker locker, Runnable sweep) {
        Runnable both = () -> {
            markRemoving.run();
            sweep.run();
        };
        if (locker.run(lockName(hostId), REMOVAL_LOCK_SECONDS, both)) {
            return true;
        }
        both.run();
        return false;
    }
}
