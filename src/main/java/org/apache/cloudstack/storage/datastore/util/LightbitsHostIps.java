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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.host.DetailVO;
import com.cloud.host.dao.HostDetailsDao;

/**
 * The data IPs of KVM hosts, per pool: the source address each host uses to reach the pool's
 * cluster, as its agent reports it on connect. Kept as host detail {@code lightbits.dataips.<pool id>}
 * (comma separated), since a host can reach two clusters over different networks.
 */
public final class LightbitsHostIps {
    private static final Logger LOG = LogManager.getLogger(LightbitsHostIps.class);
    public static final String DETAIL_PREFIX = "lightbits.dataips.";

    private LightbitsHostIps() {
    }

    public static String detailKey(long poolId) {
        return DETAIL_PREFIX + poolId;
    }

    public static List<String> parse(String value) {
        List<String> out = new ArrayList<>();
        if (value != null) {
            for (String ip : value.split(",")) {
                if (!ip.isBlank() && !out.contains(ip.trim())) {
                    out.add(ip.trim());
                }
            }
        }
        return out;
    }

    /** A host's data IPs for a pool; empty when it has not reported them. */
    public static List<String> of(HostDetailsDao dao, long hostId, long poolId) {
        DetailVO d = dao.findDetail(hostId, detailKey(poolId));
        return d == null ? List.of() : parse(d.getValue());
    }

    /**
     * The IPs for a list of host NQNs: the union of each host's IPs, in order. Null when any
     * NQN has no known IPs (not a CloudStack host, or one that has not reported them): the
     * caller then leaves the volume's IP ACL as it is rather than lock a host out.
     */
    static List<String> union(List<String> nqns, Function<String, List<String>> ipsOfNqn) {
        Set<String> out = new LinkedHashSet<>();
        for (String nqn : nqns) {
            List<String> ips = ipsOfNqn.apply(nqn);
            if (ips == null || ips.isEmpty()) {
                return null;
            }
            out.addAll(ips);
        }
        return new ArrayList<>(out);
    }

    /**
     * The data IPs to store from a host's report: the reported ones, or for a partial report
     * (the agent could not look up every endpoint) the stored ones with the reported ones added,
     * so an address that was not looked up this time is not dropped.
     */
    public static List<String> merge(String stored, List<String> reported, boolean partial) {
        Set<String> out = new LinkedHashSet<>();
        if (partial && stored != null) {
            out.addAll(parse(stored));
        }
        out.addAll(reported);
        return new ArrayList<>(out);
    }

    /**
     * The resolver for a pool's volume IP ACLs, from the host details. {@code nqnDetail} is
     * the host detail holding a host's NQN.
     */
    public static LightbitsAcl.IpResolver resolver(HostDetailsDao dao, String nqnDetail, long poolId) {
        return nqns -> {
            Map<String, List<Long>> hostsByNqn = new HashMap<>();
            for (DetailVO d : dao.findByName(nqnDetail)) {
                if (d.getValue() != null) {
                    hostsByNqn.computeIfAbsent(d.getValue().trim(), k -> new ArrayList<>()).add(d.getHostId());
                }
            }
            List<String> ips = union(nqns, nqn -> {
                List<String> found = new ArrayList<>();
                for (Long hostId : hostsByNqn.getOrDefault(nqn, List.of())) {
                    List<String> own = of(dao, hostId, poolId);
                    if (own.isEmpty()) {
                        // several hosts with one NQN (a cloned host): all of them must be known
                        return null;
                    }
                    found.addAll(own);
                }
                return found;
            });
            if (ips == null) {
                LOG.warn("Lightbits: not every host in {} has reported its data IPs for pool {}; leaving the volume's"
                        + " IP ACL as it is (reconnect those hosts with an agent of this plugin version)", nqns, poolId);
            }
            return ips;
        };
    }
}
