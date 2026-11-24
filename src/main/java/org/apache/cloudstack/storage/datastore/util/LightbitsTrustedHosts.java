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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.cloudstack.storage.datastore.client.LightbitsApiClient;
import org.apache.cloudstack.storage.datastore.client.LightbitsApiClient.TrustedHost;
import org.apache.cloudstack.storage.datastore.client.LightbitsApiException;

/**
 * Trusted hosts for NVMe in-band authentication (pool option {@code inbandAuth}).
 *
 * <p>Each KVM host makes its own DH-HMAC-CHAP key and reports it when it connects; the
 * management server registers it as a trusted host in the pool's project, with that key as
 * the host secret, and keeps only a fingerprint of it (host detail
 * {@code lightbits.inband.<pool id>}). A host NQN can be a trusted host only once per cluster,
 * so a host that is already a trusted host in this project under another name is updated
 * under that name, and one that is a trusted host in another project cannot be changed with
 * this pool's project token.
 */
public final class LightbitsTrustedHosts {
    /** Host detail prefix: the fingerprint of the key registered for the pool. */
    public static final String DETAIL_PREFIX = "lightbits.inband.";
    /** Host detail: how many Lightbits paths of the host hold no key, as the agent last reported. */
    public static final String KEYLESS_DETAIL = "lightbits.keylesspaths";

    private LightbitsTrustedHosts() {
    }

    public static String detailKey(long poolId) {
        return DETAIL_PREFIX + poolId;
    }

    /** The plugin's trusted host name for a CloudStack host: {@code cs-<environment>-host-<host uuid>}. */
    public static String name(String environment, String hostUuid) {
        return namePrefix(environment) + "host-" + hostUuid;
    }

    static String namePrefix(String environment) {
        return "cs-" + environment + "-";
    }

    /** A short, one-way fingerprint of a key (SHA-256, first 16 hex digits), safe to store and log. */
    public static String fingerprint(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Outcome of {@link #register}. */
    public static final class Registration {
        /** The trusted host name the key was set on, or null when the host is a trusted host of another project. */
        public final String name;
        /** True when the host was already a trusted host of this project under another name than the plugin's. */
        public final boolean foreignName;

        Registration(String name, boolean foreignName) {
            this.name = name;
            this.foreignName = foreignName;
        }

        public boolean registered() {
            return name != null;
        }
    }

    /**
     * Make the host a trusted host of the client's project with the key as its host secret.
     * Uses the trusted host this project already has for the NQN (any name), creates one under
     * {@code name} otherwise, and sets the secret. Returns a registration with a null name when
     * the NQN is a trusted host of another project.
     */
    public static Registration register(LightbitsApiClient client, String name, String hostNqn, String key,
            Map<String, String> labels) {
        Optional<TrustedHost> existing = client.findTrustedHostByNqn(hostNqn);
        if (existing.isEmpty()) {
            try {
                client.createTrustedHost(name, hostNqn, labels);
            } catch (LightbitsApiException e) {
                // 409: created by a concurrent connect of the same host to another pool of this
                // project, or the NQN belongs to another project. An unknown outcome is settled the
                // same way, by looking again.
                if (e.getKind() != LightbitsApiException.Kind.CONFLICT && e.getKind() != LightbitsApiException.Kind.TRANSIENT) {
                    throw e;
                }
                existing = client.findTrustedHostByNqn(hostNqn);
                if (existing.isEmpty()) {
                    if (e.getKind() == LightbitsApiException.Kind.CONFLICT) {
                        return new Registration(null, false);
                    }
                    throw e;
                }
            }
        }
        String used = existing.map(h -> h.name).orElse(name);
        client.setTrustedHostSecret(used, key);
        return new Registration(used, !used.equals(name));
    }

    /**
     * Delete the trusted hosts of the client's project for the host NQN that the plugin made
     * (named with the environment's prefix). Returns the names deleted; {@code others} gets the
     * names of trusted hosts for the NQN that someone else made, which are left alone.
     */
    public static List<String> unregister(LightbitsApiClient client, String environment, String hostNqn, List<String> others) {
        List<String> deleted = new ArrayList<>();
        for (TrustedHost h : client.listTrustedHosts()) {
            if (!hostNqn.equals(h.hostNqn)) {
                continue;
            }
            if (h.name.startsWith(namePrefix(environment))) {
                if (client.deleteTrustedHost(h.name)) {
                    deleted.add(h.name);
                }
            } else if (others != null) {
                others.add(h.name);
            }
        }
        return deleted;
    }
}
