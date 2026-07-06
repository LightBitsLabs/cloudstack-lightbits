/*
 * Copyright 2026 Lightbits Labs Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cloudstack.storage.datastore.util;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses and validates the parameters of a Lightbits primary storage pool, and
 * maps them to/from the CloudStack {@code storage_pool_details} row.
 *
 * A pool is registered with a URL of the form:
 *   {@code lightbits://<api-host>[:443]/<project>?replicas=3&compression=true}
 * plus a JWT supplied separately (stored encrypted, never in the URL query log).
 *
 * Detail keys (persisted in storage_pool_details):
 *   apiHost, apiPort, project, replicaCount, compression, qosPolicy, jwt(encrypted)
 */
public class LightbitsPoolConfig {
    public static final String SCHEME = "lightbits";

    public static final String KEY_API_HOST = "apiHost";
    public static final String KEY_API_PORT = "apiPort";
    public static final String KEY_PROJECT = "project";
    public static final String KEY_REPLICAS = "replicaCount";
    public static final String KEY_COMPRESSION = "compression";
    public static final String KEY_QOS = "qosPolicy";
    public static final String KEY_JWT = "jwt";

    private static final int DEFAULT_PORT = 443;
    private static final int DEFAULT_REPLICAS = 3;

    private final String apiHost;
    private final int apiPort;
    private final String project;
    private final int replicaCount;
    private final boolean compression;
    private final String qosPolicy; // nullable
    private final String jwt;        // nullable in details map (encrypted); required to operate

    public LightbitsPoolConfig(String apiHost, int apiPort, String project, int replicaCount,
                               boolean compression, String qosPolicy, String jwt) {
        this.apiHost = apiHost;
        this.apiPort = apiPort;
        this.project = project;
        this.replicaCount = replicaCount;
        this.compression = compression;
        this.qosPolicy = qosPolicy;
        this.jwt = jwt;
    }

    /**
     * Parse a pool-registration URL plus an out-of-band JWT.
     * Throws IllegalArgumentException with an actionable message on any problem —
     * validation happens here so the pool row is never created half-formed.
     */
    public static LightbitsPoolConfig fromUrl(String url, String jwt) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Lightbits pool URL is required");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed Lightbits pool URL: " + e.getMessage());
        }
        if (!SCHEME.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Lightbits pool URL must use the '" + SCHEME + "://' scheme");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Lightbits pool URL must include an API host");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT;

        String project = uri.getPath() == null ? "" : uri.getPath().replaceAll("^/+", "").replaceAll("/+$", "");
        if (project.isBlank()) {
            // trial finding F1: projectName is mandatory for every volume op
            throw new IllegalArgumentException("Lightbits pool URL must include a project path segment "
                    + "(e.g. lightbits://host/my-project) — the cluster requires a project for all volume operations");
        }

        Map<String, String> q = parseQuery(uri.getRawQuery());
        int replicas = parsePositiveInt(q.getOrDefault("replicas", String.valueOf(DEFAULT_REPLICAS)), "replicas");
        if (replicas < 1 || replicas > 3) {
            throw new IllegalArgumentException("replicas must be between 1 and 3 (got " + replicas + ")");
        }
        boolean compression = Boolean.parseBoolean(q.getOrDefault("compression", "false"));
        String qos = q.get("qosPolicy");

        if (jwt == null || jwt.isBlank()) {
            throw new IllegalArgumentException("a JWT credential is required to register a Lightbits pool");
        }
        return new LightbitsPoolConfig(host, port, project, replicas, compression, qos, jwt);
    }

    /** Rebuild config from a persisted details map (JWT already decrypted by the caller). */
    public static LightbitsPoolConfig fromDetails(Map<String, String> details, String decryptedJwt) {
        if (details == null) {
            throw new IllegalArgumentException("pool details are missing");
        }
        String host = require(details, KEY_API_HOST);
        int port = parsePositiveInt(details.getOrDefault(KEY_API_PORT, String.valueOf(DEFAULT_PORT)), KEY_API_PORT);
        String project = require(details, KEY_PROJECT);
        int replicas = parsePositiveInt(details.getOrDefault(KEY_REPLICAS, String.valueOf(DEFAULT_REPLICAS)), KEY_REPLICAS);
        boolean compression = Boolean.parseBoolean(details.getOrDefault(KEY_COMPRESSION, "false"));
        String qos = details.get(KEY_QOS);
        return new LightbitsPoolConfig(host, port, project, replicas, compression, qos, decryptedJwt);
    }

    /** Details map WITHOUT the JWT — caller encrypts and stores the JWT separately. */
    public Map<String, String> toDetailsWithoutSecret() {
        Map<String, String> d = new HashMap<>();
        d.put(KEY_API_HOST, apiHost);
        d.put(KEY_API_PORT, String.valueOf(apiPort));
        d.put(KEY_PROJECT, project);
        d.put(KEY_REPLICAS, String.valueOf(replicaCount));
        d.put(KEY_COMPRESSION, String.valueOf(compression));
        if (qosPolicy != null) {
            d.put(KEY_QOS, qosPolicy);
        }
        return d;
    }

    public String apiEndpoint() {
        return "https://" + apiHost + ":" + apiPort;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return m;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                m.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return m;
    }

    private static int parsePositiveInt(String v, String field) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an integer (got '" + v + "')");
        }
    }

    private static String require(Map<String, String> details, String key) {
        String v = details.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("pool detail '" + key + "' is missing");
        }
        return v;
    }

    public String getApiHost() { return apiHost; }
    public int getApiPort() { return apiPort; }
    public String getProject() { return project; }
    public int getReplicaCount() { return replicaCount; }
    public boolean isCompression() { return compression; }
    public String getQosPolicy() { return qosPolicy; }
    public String getJwt() { return jwt; }
}
