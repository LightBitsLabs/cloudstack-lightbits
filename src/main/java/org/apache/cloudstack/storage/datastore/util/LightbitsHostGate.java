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
 * <p>The removal first marks the host as being removed (host detail {@link #REMOVING_DETAIL}),
 * then sweeps. A grant refuses when it sees the mark before its write, and takes its own entry
 * out again when it sees the mark after its write. Because the mark is written before the
 * sweep, every grant write is covered: one that lands before the sweep reads the volume is
 * swept, one that lands after it is seen by the grant's own second check. Both also run
 * under one lock per host that every management server sees (CloudStack's database-backed
 * GlobalLock), so in the normal case no grant runs during the sweep at all; when the removal
 * cannot get the lock in time it still sweeps, and the second check covers the grant that
 * holds the lock. The removal can finish while such a grant still runs, and CloudStack then
 * deletes the host's details, the mark with them: the caller's {@link RemovingCheck} must
 * also report a host whose details are gone (no NQN detail) as being removed, so the grant's
 * second check still sees it. The mark is cleared when the host connects again, in case its
 * removal did not go through.
 */
public final class LightbitsHostGate {
    /** Host detail set while the host is being removed from CloudStack. */
    public static final String REMOVING_DETAIL = "lightbits.removing";
    static final int GRANT_LOCK_SECONDS = 120;
    public static final int REMOVAL_LOCK_SECONDS = 600;

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

    /** Called with the host id; true while the host is marked as being removed or already gone. */
    public interface RemovingCheck {
        boolean isRemoving(long hostId);
    }

    /**
     * Whether a host is being removed, from its details: the removal mark is set, or the host's
     * NQN detail is gone (CloudStack deletes a removed host's details, the mark included, once
     * the removal listeners have run, so the mark alone stops answering after the removal).
     */
    public static RemovingCheck removingCheck(com.cloud.host.dao.HostDetailsDao dao, String nqnDetail) {
        return hostId -> dao.findDetail(hostId, REMOVING_DETAIL) != null || dao.findDetail(hostId, nqnDetail) == null;
    }

    private LightbitsHostGate() {
    }

    public static String lockName(long hostId) {
        return "lightbits.host.acl." + hostId;
    }

    static final int POOL_IP_ACL_LOCK_SECONDS = 120;

    /**
     * The lock every IP ACL writer of a pool with ipAcl holds while it resolves hosts' data IPs
     * and writes them: grants, revokes, the host removal sweep and the refresh after a host
     * reports new data IPs. Without it a writer could resolve a host's old IPs, the refresh
     * could find the volume already right and write nothing, and the writer could then put the
     * old IPs back with a still-valid ETag. Lock order: a host's ACL lock (when taken) first,
     * then this one; no writer holds two pool locks at once.
     */
    public static String poolIpAclLockName(long poolId) {
        return "lightbits.pool.ipacl." + poolId;
    }

    /**
     * Run an IP ACL write of a pool under its {@link #poolIpAclLockName} lock, or directly when
     * the pool has no ipAcl. Fails without the lock in time, having written nothing.
     */
    public static void underPoolIpAclLock(Locker locker, long poolId, boolean ipAcl, Runnable work) {
        if (!ipAcl) {
            work.run();
            return;
        }
        if (!locker.run(poolIpAclLockName(poolId), POOL_IP_ACL_LOCK_SECONDS, work)) {
            throw new CloudRuntimeException("could not get the IP ACL lock of Lightbits pool " + poolId + " within "
                    + POOL_IP_ACL_LOCK_SECONDS + " s (a host's data IPs are being recorded); retry the operation");
        }
    }

    /**
     * Run a grant's ACL write for the host, unless the host is being removed. If the host is
     * marked once the write is done, {@code aclUndo} takes the entry out again and the grant fails.
     */
    public static void grant(long hostId, RemovingCheck removing, Locker locker, Runnable aclWrite, Runnable aclUndo) {
        boolean had = locker.run(lockName(hostId), GRANT_LOCK_SECONDS, () -> {
            if (removing.isRemoving(hostId)) {
                throw new CloudRuntimeException("host " + hostId + " is being removed from CloudStack;"
                        + " not granting it access to a Lightbits volume");
            }
            aclWrite.run();
            if (removing.isRemoving(hostId)) {
                aclUndo.run();
                throw new CloudRuntimeException("host " + hostId + " started being removed from CloudStack during"
                        + " the grant; took its access to the Lightbits volume out again");
            }
        });
        if (!had) {
            throw new CloudRuntimeException("could not get the ACL lock of host " + hostId + " within "
                    + GRANT_LOCK_SECONDS + " s (its removal may be running); retry the operation");
        }
    }

    /**
     * Mark the host as being removed (always first, outside the lock), then sweep it out of
     * the ACLs under the host's lock. Without the lock in time the sweep still runs (removal
     * must not be blocked; a grant still writing undoes itself, see {@link #grant}); the
     * caller logs the returned false.
     */
    public static boolean removal(long hostId, Runnable markRemoving, Locker locker, Runnable sweep) {
        markRemoving.run();
        if (locker.run(lockName(hostId), REMOVAL_LOCK_SECONDS, sweep)) {
            return true;
        }
        sweep.run();
        return false;
    }
}
