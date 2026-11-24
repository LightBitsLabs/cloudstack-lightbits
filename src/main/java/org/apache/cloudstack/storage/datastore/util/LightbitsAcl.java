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

/**
 * Changes to a volume's ACL: the list of host NQNs allowed to connect to it and, with the pool
 * option ipAcl, the list of IP addresses they may connect from.
 */
public final class LightbitsAcl {
    /**
     * The IP addresses a volume's IP ACL should hold for the hosts in its NQN list, or null to
     * leave the IP ACL as it is (the pool does not manage IP ACLs, or a host's IPs are unknown).
     */
    public interface IpResolver {
        List<String> ipsFor(List<String> nqns);
    }

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

    /** The values of a volume's IP ACL as the cluster reports them (ALLOW_ANY when it reports none). */
    public static List<String> ips(Volume v) {
        if (v.getIpAcl() == null || v.getIpAcl().getValues() == null || v.getIpAcl().getValues().isEmpty()) {
            return List.of("ALLOW_ANY");
        }
        return v.getIpAcl().getValues();
    }

    /** The IP ACL values with {@code add} added; null (no change) when it allows any address already. */
    static List<String> withAdded(List<String> current, List<String> add) {
        if (current.contains("ALLOW_ANY")) {
            return null;
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String ip : current) {
            if (!"ALLOW_NONE".equals(ip)) {
                out.add(ip);
            }
        }
        out.addAll(add);
        return new ArrayList<>(out);
    }

    private static boolean sameIps(Volume v, List<String> wanted) {
        java.util.Set<String> want = new java.util.HashSet<>(wanted.isEmpty() ? List.of("ALLOW_NONE") : wanted);
        return want.equals(new java.util.HashSet<>(ips(v)));
    }

    /** {@link #update(LightbitsApiClient, String, boolean, Predicate, IpResolver)} leaving the IP ACL as it is. */
    public static boolean update(LightbitsApiClient client, String name, boolean mustExist, Predicate<List<String>> change) {
        return update(client, name, mustExist, change, null);
    }

    /**
     * Read-modify-write of a volume's ACL, safe against concurrent updates of the same volume
     * (two hosts attaching, or a migration's grant racing a revoke): the PUT is conditional on
     * the ETag the ACL was read with, and a stale ETag (412) restarts from a fresh read. A
     * plain read-then-write would let the second writer drop the first writer's host.
     *
     * {@code change} edits the list in place and returns false when there is nothing to do.
     * With an {@code ips} resolver the IP ACL is set, in the same update, to the IPs of the
     * hosts in the resulting NQN list; it is also corrected when only it is out of line.
     * Returns true when an update was sent.
     */
    public static boolean update(LightbitsApiClient client, String name, boolean mustExist, Predicate<List<String>> change,
                                 IpResolver ips) {
        return update(client, name, mustExist, change, ips, null);
    }

    /**
     * {@link #update(LightbitsApiClient, String, boolean, Predicate, IpResolver)} for a grant:
     * when {@code ips} cannot resolve every host (one has not reported its IPs), the IP ACL is
     * not left as it is but gets {@code mustInclude}, the granted host's own addresses, added to
     * what it holds. Nothing is removed, so no host loses access; the full list is written at
     * the next update that resolves every host.
     */
    public static boolean update(LightbitsApiClient client, String name, boolean mustExist, Predicate<List<String>> change,
                                 IpResolver ips, List<String> mustInclude) {
        for (int attempt = 1; ; attempt++) {
            Optional<Volume> found = client.getVolumeByName(name);
            if (found.isEmpty()) {
                if (mustExist) {
                    throw new CloudRuntimeException("grantAccess: volume not found on cluster: " + name);
                }
                return false;
            }
            Volume v = found.get();
            List<String> acl = hosts(v);
            boolean nqnsChanged = change.test(acl);
            List<String> wantedIps = ips == null ? null : ips.ipsFor(acl);
            if (ips != null && wantedIps == null && mustInclude != null && !mustInclude.isEmpty()) {
                wantedIps = withAdded(ips(v), mustInclude);
            }
            if (wantedIps != null && sameIps(v, wantedIps)) {
                wantedIps = null;
            }
            if (!nqnsChanged && wantedIps == null) {
                return false;
            }
            try {
                client.updateVolumeAcl(v.getUuid(), acl, wantedIps, v.getEtag());
                return true;
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
        return pruneHost(client, environment, poolId, hostNqn, null);
    }

    /** {@link #pruneHost(LightbitsApiClient, String, long, String)}, setting the IP ACLs with {@code ips}. */
    public static List<String> pruneHost(LightbitsApiClient client, String environment, long poolId, String hostNqn,
                                         IpResolver ips) {
        return pruneHost(client, environment, poolId, hostNqn, ips, () -> { });
    }

    /**
     * {@link #pruneHost(LightbitsApiClient, String, long, String, IpResolver)}, running
     * {@code beforeEach} before each volume it updates (the removal renews its mark there, so
     * the mark stays fresh however long the sweep takes).
     */
    public static List<String> pruneHost(LightbitsApiClient client, String environment, long poolId, String hostNqn,
                                         IpResolver ips, Runnable beforeEach) {
        List<String> pruned = new ArrayList<>();
        beforeEach.run();   // also before the listing
        for (Volume v : client.listVolumes()) {
            if (v.getName() == null || !LightbitsUtil.isPoolObject(environment, poolId, v.getName()) || !hosts(v).contains(hostNqn)) {
                continue;
            }
            beforeEach.run();
            try {
                update(client, v.getName(), false, acl -> acl.remove(hostNqn), ips);
                pruned.add(v.getName());
            } catch (RuntimeException e) {
                LOG.warn("Lightbits: could not remove host {} from the ACL of volume {}: {}. Remove it on the cluster"
                        + " if the host is not coming back.", hostNqn, v.getName(), e.getMessage());
            }
        }
        return pruned;
    }

    /**
     * Bring the IP ACL of every volume of one pool whose NQN list holds the host in line with
     * {@code ips} (after the host's data IPs changed, or were first reported). A volume that
     * cannot be updated is logged and skipped. Returns the names updated.
     */
    public static List<String> refreshIps(LightbitsApiClient client, String environment, long poolId, String hostNqn,
                                          IpResolver ips) {
        return refreshIps(client, environment, poolId, hostNqn, ips, null);
    }

    /**
     * {@link #refreshIps(LightbitsApiClient, String, long, String, IpResolver)}; where another
     * host's IPs are unknown, {@code hostIps} (the refreshing host's addresses) are added to the
     * IP ACL, removing none, as a grant does.
     */
    public static List<String> refreshIps(LightbitsApiClient client, String environment, long poolId, String hostNqn,
                                          IpResolver ips, List<String> hostIps) {
        List<String> updated = new ArrayList<>();
        for (Volume v : client.listVolumes()) {
            if (v.getName() == null || !LightbitsUtil.isPoolObject(environment, poolId, v.getName()) || !hosts(v).contains(hostNqn)) {
                continue;
            }
            try {
                if (update(client, v.getName(), false, acl -> false, ips, hostIps)) {
                    updated.add(v.getName());
                }
            } catch (RuntimeException e) {
                LOG.warn("Lightbits: could not update the IP ACL of volume {} for host {}: {}", v.getName(), hostNqn, e.getMessage());
            }
        }
        return updated;
    }
}
