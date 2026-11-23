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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.apache.cloudstack.storage.datastore.client.LightbitsApiClient;
import org.apache.cloudstack.storage.datastore.client.LightbitsApiException;
import org.apache.cloudstack.storage.datastore.client.LightbitsApiException.Kind;
import org.apache.cloudstack.storage.datastore.client.model.Volume;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.exception.CloudRuntimeException;

/** Changes to a volume's ACL: the list of host NQNs allowed to connect to it. */
public final class LightbitsAcl {
    private static final Logger LOG = LogManager.getLogger(LightbitsAcl.class);
    private static final int UPDATE_ATTEMPTS = 8;

    private LightbitsAcl() {
    }

    /** The host NQNs in a volume's ACL (the ALLOW_NONE marker of an empty ACL is not a host). */
    public static List<String> hosts(Volume v) {
        List<String> acl = new ArrayList<>();
        if (v.getAcl() != null && v.getAcl().getValues() != null) {
            for (String val : v.getAcl().getValues()) {
                if (!"ALLOW_NONE".equals(val)) {
                    acl.add(val);
                }
            }
        }
        return acl;
    }

    /**
     * Read-modify-write of a volume's ACL, safe against concurrent updates of the same volume
     * (two hosts attaching, or a migration's grant racing a revoke): the PUT is conditional on
     * the ETag the ACL was read with, and a stale ETag (412) restarts from a fresh read. A
     * plain read-then-write would let the second writer drop the first writer's host.
     *
     * {@code change} edits the list in place and returns false when there is nothing to do.
     */
    public static void update(LightbitsApiClient client, String name, boolean mustExist, Predicate<List<String>> change) {
        for (int attempt = 1; ; attempt++) {
            Optional<Volume> found = client.getVolumeByName(name);
            if (found.isEmpty()) {
                if (mustExist) {
                    throw new CloudRuntimeException("grantAccess: volume not found on cluster: " + name);
                }
                return;
            }
            Volume v = found.get();
            List<String> acl = hosts(v);
            if (!change.test(acl)) {
                return;
            }
            try {
                client.updateVolumeAcl(v.getUuid(), acl, v.getEtag());
                return;
            } catch (LightbitsApiException e) {
                // 412: the volume changed since it was read. Transient: the update was sent once
                // and not confirmed. Either way start over from a fresh read; if the change is
                // already there the predicate finds nothing to do.
                boolean again = e.getKind() == Kind.PRECONDITION || e.getKind() == Kind.TRANSIENT;
                if (!again || attempt >= UPDATE_ATTEMPTS) {
                    throw e;
                }
                LOG.debug("Lightbits: ACL update of {} not applied ({}), re-reading (attempt {})", name, e.getKind(), attempt);
            }
        }
    }

    /**
     * Remove a host from the ACL of every volume of one pool (names exactly as
     * {@link LightbitsUtil#isPoolObject} defines them, not every name with the pool's prefix). Used when the host leaves CloudStack: an entry left behind would let that
     * machine keep reading and writing the volume after CloudStack has stopped tracking it.
     * A volume that cannot be updated is logged and skipped. Returns the names updated.
     */
    public static List<String> pruneHost(LightbitsApiClient client, String environment, long poolId, String hostNqn) {
        List<String> pruned = new ArrayList<>();
        for (Volume v : client.listVolumes()) {
            if (v.getName() == null || !LightbitsUtil.isPoolObject(environment, poolId, v.getName()) || !hosts(v).contains(hostNqn)) {
                continue;
            }
            try {
                update(client, v.getName(), false, acl -> acl.remove(hostNqn));
                pruned.add(v.getName());
            } catch (RuntimeException e) {
                LOG.warn("Lightbits: could not remove host {} from the ACL of volume {}: {}. Remove it on the cluster"
                        + " if the host is not coming back.", hostNqn, v.getName(), e.getMessage());
            }
        }
        return pruned;
    }
}
