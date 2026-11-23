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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.storage.datastore.client.LightbitsApiClient;

/**
 * Parses and validates the parameters of a Lightbits primary storage pool, and
 * maps them to/from the CloudStack {@code storage_pool_details} row.
 *
 * A pool is registered with a URL of the form:
 *   {@code lightbits://<api-host>[:443]/<project>?replicas=3&compression=true&environment=<env>}
 * plus a JWT supplied separately (stored encrypted, never in the URL query log).
 *
 * The JWT is PROJECT-SCOPED (project-admin on {@code <project>}): it can operate on
 * {@code /api/v2/projects/<project>/...} and can read {@code /api/v2/nodes}, but is denied
 * the cluster-wide {@code GET /api/v2/cluster} (HTTP 403). Pool capacity is therefore NOT
 * declared here — it is computed at runtime by aggregating raw physical capacity across
 * cluster nodes ({@code /api/v2/nodes}) and dividing by the pool's replica count. See
 * LightbitsApiClient.nodeCapacity().
 *
 * Detail keys (persisted in storage_pool_details):
 *   apiHost, apiPort, apiEndpoints, tlsFingerprint, project, replicaCount, compression,
 *   qosPolicy, environment, jwt(encrypted)
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
    public static final String KEY_ENVIRONMENT = "environment";
    /** Further API endpoints of the same cluster, comma-separated host[:port], tried on failover. */
    public static final String KEY_API_ENDPOINTS = "apiEndpoints";
    /** SHA-256 fingerprint(s) of the cluster's API certificate, comma-separated (two during a rotation). */
    public static final String KEY_TLS_FINGERPRINT = "tlsFingerprint";
    private static final int MAX_FINGERPRINTS = 3;
    /**
     * NVMe discovery endpoints the KVM hosts connect through, comma-separated host[:port]
     * (port 8009 by default). Only needed when the hosts reach the cluster's data network on
     * other addresses than the management server reaches its API; by default the API hosts.
     */
    public static final String KEY_NVME_DISCOVERY = "nvmeDiscovery";
    /** Pick the QoS policy from the offering's max IOPS when the offering names none (opt-in). */
    public static final String KEY_QOS_FROM_IOPS = "qosFromIops";
    public static final String KEY_IP_ACL = "ipAcl";
    public static final int DISCOVERY_PORT = 8009;
    private static final int MAX_DETAIL_LENGTH = 255;

    private static final int DEFAULT_PORT = 443;
    private static final int DEFAULT_REPLICAS = 3;

    private final String apiHost;
    private final int apiPort;
    private final String project;
    private final int replicaCount;
    private final boolean compression;
    private final String qosPolicy;  // nullable
    private final String jwt;         // nullable in details map (encrypted); required to operate
    private final String environment; // identifies the owning CloudStack when clusters are shared
    private final List<String> extraEndpoints; // host:port, never null
    private final List<String> fingerprints;   // normalized SHA-256, never null; empty = CA validation
    private List<String> nvmeDiscovery = List.of(); // host:port, empty = the API hosts
    private boolean qosFromIops;
    private boolean ipAcl;

    public LightbitsPoolConfig(String apiHost, int apiPort, String project, int replicaCount,
                               boolean compression, String qosPolicy, String jwt, String environment) {
        this(apiHost, apiPort, project, replicaCount, compression, qosPolicy, jwt, environment, null, null);
    }

    public LightbitsPoolConfig(String apiHost, int apiPort, String project, int replicaCount,
                               boolean compression, String qosPolicy, String jwt, String environment,
                               List<String> extraEndpoints, List<String> fingerprints) {
        this.apiHost = apiHost;
        this.apiPort = apiPort;
        this.project = project;
        this.replicaCount = replicaCount;
        this.compression = compression;
        this.qosPolicy = qosPolicy;
        this.jwt = jwt;
        this.environment = environment;
        this.extraEndpoints = extraEndpoints == null ? List.of() : List.copyOf(extraEndpoints);
        this.fingerprints = fingerprints == null ? List.of() : List.copyOf(fingerprints);
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
        if (host.startsWith("[")) {
            // The KVM hosts take their NVMe discovery endpoints from the API hosts, and neither
            // that list nor the endpoints lists carry IPv6 literals: a pool registered with one
            // could be created and then never attach a volume.
            throw new IllegalArgumentException("Lightbits pool URL: an IPv6 address (" + host + ") is not supported as"
                    + " the API host; use an IPv4 address or a DNS name");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT;

        String project = uri.getPath() == null ? "" : uri.getPath().replaceAll("^/+", "").replaceAll("/+$", "");
        if (project.isBlank()) {
            // trial finding F1: projectName is mandatory for every volume op
            throw new IllegalArgumentException("Lightbits pool URL must include a project path segment "
                    + "(e.g. lightbits://host/my-project): the cluster requires a project for all volume operations");
        }

        Map<String, String> q = parseQuery(uri.getRawQuery());
        int replicas = parsePositiveInt(q.getOrDefault("replicas", String.valueOf(DEFAULT_REPLICAS)), "replicas");
        if (replicas < 1 || replicas > 3) {
            throw new IllegalArgumentException("replicas must be between 1 and 3 (got " + replicas + ")");
        }
        boolean compression = Boolean.parseBoolean(q.getOrDefault("compression", "false"));
        String qos = q.get("qosPolicy");
        // environment identifies the owning CloudStack when multiple share a cluster;
        // it becomes part of every volume name for collision-avoidance + identification.
        String environment = q.get("environment");
        if (environment == null || environment.isBlank()) {
            throw new IllegalArgumentException("Lightbits pool URL must include '?environment=<name>' "
                    + "to identify this CloudStack when a cluster is shared (e.g. ...?environment=prod-cs1)");
        }
        if (!environment.matches("[A-Za-z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("environment must be 1-40 chars of [A-Za-z0-9_-] (got '" + environment + "')");
        }

        if (jwt == null || jwt.isBlank()) {
            throw new IllegalArgumentException("a JWT credential is required to register a Lightbits pool");
        }
        // Capacity is NOT taken from the URL: it is computed at runtime by aggregating raw
        // physical capacity across cluster nodes (GET /api/v2/nodes, readable by a
        // project-scoped token) and dividing by the pool's replica count. See
        // LightbitsApiClient.nodeCapacity() and the lifecycle/driver stats path.
        LightbitsPoolConfig cfg = new LightbitsPoolConfig(host, port, project, replicas, compression, qos, jwt, environment,
                parseEndpoints(decode(q.get("endpoints")), port), parseFingerprints(decode(q.get("fingerprint"))));
        cfg.nvmeDiscovery = List.copyOf(parseEndpoints(decode(q.get("discovery")), DISCOVERY_PORT));
        cfg.qosFromIops = parseStrictBoolean(KEY_QOS_FROM_IOPS, q.get(KEY_QOS_FROM_IOPS));
        cfg.ipAcl = parseStrictBoolean(KEY_IP_ACL, q.get(KEY_IP_ACL));
        return cfg;
    }

    /**
     * Further API endpoints of the same cluster: comma-separated {@code host[:port]}; an entry
     * without a port uses the pool's API port. Returned as {@code host:port}.
     */
    public static List<String> parseEndpoints(String raw, int defaultPort) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String e : raw.split(",")) {
            String entry = e.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (!entry.matches("[A-Za-z0-9]([A-Za-z0-9.-]{0,252})(:[0-9]{1,5})?")) {
                throw new IllegalArgumentException("endpoints must be a comma-separated list of host or host:port (got '"
                        + entry + "')");
            }
            String withPort = entry.contains(":") ? entry : entry + ":" + defaultPort;
            requireUsableAuthority(withPort);
            if (!out.contains(withPort)) {
                out.add(withPort);
            }
        }
        // the list is stored as one pool detail, and CloudStack's detail value column holds 255 characters
        int stored = String.join(",", out).length();
        if (stored > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("endpoints is too long to store: " + stored + " characters as host:port list, the limit is "
                    + MAX_DETAIL_LENGTH + " (use IP addresses or shorter names)");
        }
        return out;
    }

    /** The check the HTTP client applies when it builds a request: a parsable host and a valid port. */
    private static void requireUsableAuthority(String hostPort) {
        try {
            java.net.URI uri = new java.net.URI("https://" + hostPort).parseServerAuthority();
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535) {
                throw new java.net.URISyntaxException(hostPort, "no usable host and port");
            }
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("endpoints entry '" + hostPort + "' is not a usable host or host:port ("
                    + e.getReason() + ")");
        }
    }

    /** SHA-256 certificate fingerprints, comma-separated, colons optional. Returned normalized. */
    public static List<String> parseFingerprints(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String f : raw.split(",")) {
            if (f.isBlank()) {
                continue;
            }
            String n = LightbitsApiClient.normalizeFingerprint(f);
            if (!out.contains(n)) {
                out.add(n);
            }
        }
        if (out.size() > MAX_FINGERPRINTS) {
            throw new IllegalArgumentException("at most " + MAX_FINGERPRINTS + " certificate fingerprints can be pinned"
                    + " (the current one and the next one are enough for a rotation)");
        }
        return out;
    }

    private static String decode(String v) {
        return v == null ? null : java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
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
        String environment = require(details, KEY_ENVIRONMENT);
        LightbitsPoolConfig cfg = new LightbitsPoolConfig(host, port, project, replicas, compression, qos, decryptedJwt,
                environment, parseEndpoints(details.get(KEY_API_ENDPOINTS), port),
                parseFingerprints(details.get(KEY_TLS_FINGERPRINT)));
        cfg.nvmeDiscovery = List.copyOf(parseEndpoints(details.get(KEY_NVME_DISCOVERY), DISCOVERY_PORT));
        cfg.qosFromIops = parseStrictBoolean(KEY_QOS_FROM_IOPS, details.get(KEY_QOS_FROM_IOPS));
        cfg.ipAcl = parseStrictBoolean(KEY_IP_ACL, details.get(KEY_IP_ACL));
        return cfg;
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
        d.put(KEY_ENVIRONMENT, environment);
        if (!extraEndpoints.isEmpty()) {
            d.put(KEY_API_ENDPOINTS, String.join(",", extraEndpoints));
        }
        if (!fingerprints.isEmpty()) {
            d.put(KEY_TLS_FINGERPRINT, String.join(",", fingerprints));
        }
        if (ipAcl) {
            d.put(KEY_IP_ACL, "true");
        }
        if (qosFromIops) {
            d.put(KEY_QOS_FROM_IOPS, "true");
        }
        if (!nvmeDiscovery.isEmpty()) {
            d.put(KEY_NVME_DISCOVERY, String.join(",", nvmeDiscovery));
        }
        return d;
    }

    /**
     * NVMe discovery endpoints for the KVM hosts, as host:port: the explicit list if one was
     * given, otherwise every API host of the pool on the discovery port (on a Lightbits node the
     * API and the discovery service listen on the same address).
     */
    public List<String> discoveryEndpoints() {
        if (!nvmeDiscovery.isEmpty()) {
            return nvmeDiscovery;
        }
        List<String> out = new ArrayList<>();
        out.add(apiHost + ":" + DISCOVERY_PORT);
        for (String e : extraEndpoints) {
            String hostPort = e.substring(0, e.lastIndexOf(':')) + ":" + DISCOVERY_PORT;
            if (!out.contains(hostPort)) {
                out.add(hostPort);
            }
        }
        return out;
    }

    public String apiEndpoint() {
        return "https://" + apiHost + ":" + apiPort;
    }

    /** Every API endpoint of the cluster, the pool URL's host first. */
    public List<String> apiEndpoints() {
        List<String> out = new ArrayList<>();
        out.add(apiEndpoint());
        for (String e : extraEndpoints) {
            String url = "https://" + e;
            if (!out.contains(url)) {
                out.add(url);
            }
        }
        return out;
    }

    /** The API client for this pool: pinned to the pool's fingerprint(s), or CA-validated when none is set. */
    public LightbitsApiClient newClient() {
        return fingerprints.isEmpty()
                ? new LightbitsApiClient(apiEndpoints(), jwt, project)
                : LightbitsApiClient.pinned(apiEndpoints(), jwt, project, fingerprints);
    }

    /**
     * CloudStack storage_pool_details.value is varchar(255), but a LightOS JWT (~570 chars,
     * longer once encrypted) does not fit. Store long values chunked across rows:
     * {@code base}={count}, {@code base.0}, {@code base.1}, ... each <= CHUNK chars.
     */
    public static final int CHUNK = 240;

    public static void putChunked(Map<String, String> details, String base, String value) {
        int n = (value.length() + CHUNK - 1) / CHUNK;
        details.put(base, String.valueOf(n));
        for (int i = 0; i < n; i++) {
            details.put(base + "." + i, value.substring(i * CHUNK, Math.min((i + 1) * CHUNK, value.length())));
        }
    }

    public static String readChunked(Map<String, String> details, String base) {
        String countStr = details.get(base);
        if (countStr == null) {
            return null;
        }
        int n = Integer.parseInt(countStr);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(details.getOrDefault(base + "." + i, ""));
        }
        return sb.toString();
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
    public boolean isQosFromIops() { return qosFromIops; }
    /** Volume IP ACLs follow the data IPs of the hosts in the volume's NQN ACL. */
    public boolean isIpAcl() { return ipAcl; }

    /** Absent means false; anything but true or false is refused rather than read as false. */
    /**
     * Check the pool options among details given to updateStoragePool, so a bad value is
     * refused there instead of breaking every later read of the pool's settings.
     */
    public static void validateUpdatedDetails(Map<String, String> details) {
        if (details == null) {
            return;
        }
        for (String key : List.of(KEY_QOS_FROM_IOPS, KEY_IP_ACL)) {
            if (details.containsKey(key)) {
                parseStrictBoolean(key, details.get(key));
            }
        }
    }

    private static boolean parseStrictBoolean(String key, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim();
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(v);
        }
        throw new IllegalArgumentException(key + " must be true or false (got '" + value + "')");
    }
    public String getJwt() { return jwt; }
    public String getEnvironment() { return environment; }
    public List<String> getExtraEndpoints() { return extraEndpoints; }
    public List<String> getFingerprints() { return fingerprints; }
}
