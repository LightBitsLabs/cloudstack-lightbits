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
package org.apache.cloudstack.storage.datastore.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.cloudstack.storage.datastore.client.LightbitsApiException.Kind;
import org.apache.cloudstack.storage.datastore.client.model.ClusterInfo;
import org.apache.cloudstack.storage.datastore.client.model.Snapshot;
import org.apache.cloudstack.storage.datastore.client.model.Volume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Thin HTTPS/JSON client for the Lightbits DurosAPI v2 (pinned: LightOS 3.19.x).
 *
 * Design constraints from the validation trial (see internal repo, findings F1-F6):
 *  - projectName is mandatory on volume creation (F1)
 *  - mutations use project-scoped routes /api/v2/projects/{p}/... (F2)
 *  - list results include Deleted/Deleting volumes and must be filtered (F3)
 *  - idempotency: create tolerates "already exists" (returns existing volume),
 *    delete tolerates "already gone" (returns false)
 *  - transient failures (5xx, timeouts) retry with backoff
 *
 * Credential scope: the JWT may be PROJECT-scoped (project-admin on {@code project}).
 * Such a token can operate on {@code /api/v2/projects/{p}/...} and can read
 * {@code /api/v2/nodes}, but is denied the cluster-wide {@code GET /api/v2/cluster}
 * (HTTP 403). Callers that must work under a project-scoped token should use
 * {@link #verifyProjectAccess()} for reachability rather than {@link #getClusterInfo()}.
 */
public class LightbitsApiClient {
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String base;
    private final String jwt;
    private final String project;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final long retryBackoffMillis;

    public LightbitsApiClient(String endpoint, String jwt, String project) {
        this(endpoint, jwt, project, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), 1000);
    }

    public LightbitsApiClient(String endpoint, String jwt, String project, HttpClient http, long retryBackoffMillis) {
        this.base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.jwt = jwt;
        this.project = project;
        this.http = http;
        this.retryBackoffMillis = retryBackoffMillis;
    }

    /**
     * DEV/LAB ONLY: trusts any certificate CHAIN (self-signed clusters). Note that
     * hostname verification still applies — the JDK HttpClient cannot disable it per
     * client, so the cluster certificate's SAN must match the endpoint address even
     * in dev. Production pools must use a fully validated chain (default constructor).
     */
    public static LightbitsApiClient insecureForDevelopment(String endpoint, String jwt, String project) {
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[] {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            HttpClient http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(10)).build();
            return new LightbitsApiClient(endpoint, jwt, project, http, 1000);
        } catch (Exception e) {
            throw new LightbitsApiException(Kind.UNKNOWN, "failed to build insecure client", e);
        }
    }

    public String getProject() {
        return project;
    }

    // ---------------- cluster ----------------

    /**
     * Cluster-wide info. Requires a cluster-scoped credential; a PROJECT-scoped JWT is
     * denied this route (HTTP 403). For reachability under a project-scoped token use
     * {@link #verifyProjectAccess()} instead.
     */
    public ClusterInfo getClusterInfo() {
        return parse(execute("GET", "/api/v2/cluster", null), ClusterInfo.class);
    }

    /** Number of nodes in the cluster — a hard upper bound on a volume's replica count. */
    public int countNodes() {
        return nodeCapacity().nodeCount;
    }

    // ---------------- QoS policies ----------------

    /**
     * Check whether a QoS policy of the given NAME is visible to this client's project, via
     * {@code GET /api/v2/policies?name=<name>&projectName=<project>} (readable by a
     * project-scoped token). Used to validate a configured policy name at pool registration;
     * volumes attach QoS by name (see createVolume), matching lbcli and the CSI driver.
     */
    public boolean qosPolicyExists(String policyName) {
        if (policyName == null || policyName.isBlank()) {
            return false;
        }
        Map<String, String> q = new HashMap<>();
        q.put("name", policyName);
        q.put("projectName", project);
        String resp = execute("GET", "/api/v2/policies" + query(q), null);
        try {
            JsonNode root = mapper.readTree(resp);
            for (JsonNode p : root.path("policies")) {
                // exact name match client-side; server-side filtering isn't fully trusted
                // (same caution as volume/snapshot name filters).
                if (policyName.equals(p.path("name").asText(null))) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new LightbitsApiException(Kind.UNKNOWN, "malformed policies list response", e);
        }
    }

    /**
     * Raw physical capacity aggregated across all cluster nodes, read from
     * {@code GET /api/v2/nodes} (which a project-scoped token is permitted to read, unlike
     * {@code GET /api/v2/cluster}). Values are RAW physical bytes; usable capacity for a
     * given pool is {@code effective / replicaCount} (each logical byte costs replicaCount
     * physical bytes).
     *
     * NOTE: this is CLUSTER-wide capacity — LightOS exposes no per-project space quota on
     * the project object, so it cannot be scoped to a single project. Callers advertising
     * this as a pool's capacity in a multi-tenant (project-per-tenant) setup should be aware
     * that every pool on the cluster will report the same underlying capacity.
     */
    public NodeCapacity nodeCapacity() {
        try {
            JsonNode root = mapper.readTree(execute("GET", "/api/v2/nodes", null));
            JsonNode nodes = root.isArray() ? root : root.path("nodes");
            long effective = 0L;
            long free = 0L;
            int count = 0;
            if (nodes.isArray()) {
                for (JsonNode n : nodes) {
                    count++;
                    JsonNode st = n.path("statistics");
                    effective += parseLongField(st, "effectivePhysicalStorage");
                    free += parseLongField(st, "freePhysicalStorage");
                }
            }
            return new NodeCapacity(count, effective, free);
        } catch (IOException e) {
            throw new LightbitsApiException(Kind.UNKNOWN, "malformed nodes list response", e);
        }
    }

    private static long parseLongField(JsonNode obj, String field) {
        JsonNode v = obj.get(field);
        if (v == null || v.isNull()) {
            return 0L;
        }
        // LightOS returns these as JSON strings ("326315802624"); tolerate numbers too.
        try {
            return v.isTextual() ? Long.parseLong(v.asText()) : v.asLong();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Aggregated raw physical capacity across cluster nodes (see {@link #nodeCapacity()}). */
    public static final class NodeCapacity {
        public final int nodeCount;
        public final long effectivePhysicalBytes;
        public final long freePhysicalBytes;

        public NodeCapacity(int nodeCount, long effectivePhysicalBytes, long freePhysicalBytes) {
            this.nodeCount = nodeCount;
            this.effectivePhysicalBytes = effectivePhysicalBytes;
            this.freePhysicalBytes = freePhysicalBytes;
        }
    }

    // ---------------- volumes ----------------

    /**
     * Project-scoped reachability + authorization check, safe for a project-scoped JWT.
     *
     * A project-scoped token is denied the cluster-wide {@code GET /api/v2/cluster}
     * (HTTP 403) but is allowed to list its own project's volumes. A successful call
     * proves, in one request: the endpoint is reachable, the credential is valid, and
     * the configured project exists and is accessible to this token. Returns the
     * project's UUID (best-effort, for logging); throws {@link LightbitsApiException}
     * on any failure so the caller can turn it into an actionable registration error.
     */
    public String verifyProjectAccess() {
        // (1) list this project's volumes via the PROJECT-SCOPED route — the exact route CSI
        // uses. A project-scoped token (e.g. role "<project>:admin") is authorized here but is
        // DENIED the flat /api/v2/volumes?project_name= route (HTTP 403), so this must use the
        // project-scoped collection.
        execute("GET", projectColl("volumes"), null);
        // (2) best-effort: read the project object for its UUID (also project-scoped, HTTP 200).
        try {
            JsonNode root = mapper.readTree(execute("GET", "/api/v2/projects/" + urlenc(project), null));
            JsonNode uuid = root.get("UUID");
            return uuid == null ? null : uuid.asText();
        } catch (Exception e) {
            return null; // reachability already proven by (1); UUID is optional
        }
    }

    /**
     * Idempotent create: name collisions (409) resolve to the existing volume,
     * so a retried CloudStack job converges instead of failing.
     */
    public Volume createVolume(String name, long sizeBytes, int replicaCount, List<String> aclNqns, int sectorSize, boolean compression, String qosPolicyName) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        body.put("size", String.valueOf(sizeBytes));
        body.put("replicaCount", replicaCount);
        body.put("projectName", project);
        body.put("compression", String.valueOf(compression)); // String "true"/"false"
        body.put("sectorSize", 512);
        if (qosPolicyName != null && !qosPolicyName.isBlank()) {
            // Attach QoS by policy NAME (matches lbcli --qos-policy-name and the CSI driver).
            body.put("qosPolicyName", qosPolicyName);
        }
        ObjectNode acl = body.putObject("acl");
        ArrayNode values = acl.putArray("values");
        (aclNqns == null || aclNqns.isEmpty() ? List.of("ALLOW_NONE") : aclNqns).forEach(values::add);
        try {
            // Project-scoped route: a project-scoped token is denied the flat /api/v2/volumes.
            return parse(execute("POST", projectColl("volumes"), body.toString()), Volume.class);
        } catch (LightbitsApiException e) {
            if (e.getKind() == Kind.CONFLICT) {
                return getVolumeByName(name).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    public Optional<Volume> getVolumeByName(String name) {
        // Project-scoped list route (flat /api/v2/volumes?project_name= is denied to a
        // project-scoped token). Server-side name filtering isn't trusted anyway — firstLiveVolume
        // does an exact client-side match — so no query param is needed.
        String resp = execute("GET", projectColl("volumes"), null);
        return firstLiveVolume(resp, name);
    }

    public Optional<Volume> getVolume(String uuid) {
        try {
            String resp = execute("GET", projectPath("volumes", uuid), null);
            Volume v = parse(resp, Volume.class);
            return v.isDeletedOrDeleting() ? Optional.empty() : Optional.of(v);
        } catch (LightbitsApiException e) {
            if (e.getKind() == Kind.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public List<Volume> listVolumes() {
        // Project-scoped list route; the flat /api/v2/volumes?project_name= is denied to a
        // project-scoped token (HTTP 403).
        String resp = execute("GET", projectColl("volumes"), null);
        List<Volume> live = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(resp);
            for (JsonNode n : root.path("volumes")) {
                Volume v = mapper.treeToValue(n, Volume.class);
                if (!v.isDeletedOrDeleting()) {
                    live.add(v);
                }
            }
        } catch (IOException e) {
            throw new LightbitsApiException(Kind.UNKNOWN, "malformed volume list response", e);
        }
        return live;
    }


    /** Mutations must use the project-scoped route (trial finding F2). */
    public void resizeVolume(String uuid, long newSizeBytes) {
        ObjectNode body = mapper.createObjectNode();
        body.put("size", String.valueOf(newSizeBytes));
        execute("PUT", projectPath("volumes", uuid), body.toString());
    }

    public void updateVolumeAcl(String uuid, List<String> nqns) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode values = body.putObject("acl").putArray("values");
        (nqns == null || nqns.isEmpty() ? List.of("ALLOW_NONE") : nqns).forEach(values::add);
        execute("PUT", projectPath("volumes", uuid), body.toString());
    }

    /** Idempotent delete via the project-scoped route. Returns false if already gone. */
    public boolean deleteVolume(String uuid) {
        try {
            execute("DELETE", projectPath("volumes", uuid), null);
            return true;
        } catch (LightbitsApiException e) {
            if (e.getKind() == Kind.NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    // ---------------- snapshots ----------------

    public Snapshot createSnapshot(String name, String sourceVolumeUuid) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        body.put("sourceVolumeUUID", sourceVolumeUuid);
        body.put("projectName", project);
        try {
            // Snapshots live under the project-scoped collection; the non-scoped
            // /api/v2/snapshots route does not exist on LightOS (404).
            return parse(execute("POST", projectColl("snapshots"), body.toString()), Snapshot.class);
        } catch (LightbitsApiException e) {
            if (e.getKind() == Kind.CONFLICT) {
                return getSnapshotByName(name).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    /** All live (not deleted/deleting) snapshots in this client's project. */
    public List<Snapshot> listSnapshots() {
        String resp = execute("GET", projectColl("snapshots"), null);
        List<Snapshot> live = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(resp);
            for (JsonNode n : root.path("snapshots")) {
                Snapshot s = mapper.treeToValue(n, Snapshot.class);
                if (!s.isDeletedOrDeleting()) {
                    live.add(s);
                }
            }
        } catch (IOException e) {
            throw new LightbitsApiException(Kind.UNKNOWN, "malformed snapshot list response", e);
        }
        return live;
    }

    public Optional<Snapshot> getSnapshotByName(String name) {
        Map<String, String> q = new HashMap<>();
        // The snapshots list filter is case-sensitively mapped to the proto field 'Name'
        // (capital N) — lowercase 'name' is SILENTLY IGNORED and the full list comes back,
        // unlike the volumes endpoint which takes lowercase 'name'/'project_name'.
        q.put("Name", name);
        String resp = execute("GET", projectColl("snapshots") + query(q), null);
        try {
            JsonNode root = mapper.readTree(resp);
            for (JsonNode n : root.path("snapshots")) {
                Snapshot s = mapper.treeToValue(n, Snapshot.class);
                // exact-match client-side regardless — unknown params being silently ignored
                // means server-side filtering can never be fully trusted here
                if (!s.isDeletedOrDeleting() && name.equals(s.getName())) {
                    return Optional.of(s);
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new LightbitsApiException(Kind.UNKNOWN, "malformed snapshot list response", e);
        }
    }

    /** Create a volume cloned from a snapshot (template-from-snapshot / volume-from-snapshot). */
    public Volume createVolumeFromSnapshot(String name, String snapshotUuid, long sizeBytes, int replicaCount, List<String> aclNqns, int sectorSize, boolean compression, String qosPolicyName) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        // LightOS requires an explicit size even when cloning; it must be >= the snapshot size.
        body.put("size", String.valueOf(sizeBytes));
        body.put("projectName", project);
        body.put("replicaCount", replicaCount);
        body.put("sourceSnapshotUUID", snapshotUuid);
        body.put("compression", String.valueOf(compression)); // "true"/"false"
        body.put("sectorSize", 512);
        if (qosPolicyName != null && !qosPolicyName.isBlank()) {
            body.put("qosPolicyName", qosPolicyName);
        }
        ArrayNode values = body.putObject("acl").putArray("values");
        (aclNqns == null || aclNqns.isEmpty() ? List.of("ALLOW_NONE") : aclNqns).forEach(values::add);
        try {
            // Project-scoped route (flat /api/v2/volumes is denied to a project-scoped token).
            return parse(execute("POST", projectColl("volumes"), body.toString()), Volume.class);
        } catch (LightbitsApiException e) {
            if (e.getKind() == Kind.CONFLICT) {
                return getVolumeByName(name).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    /**
     * Roll a volume back to one of its snapshots, in place (PUT .../volumes/{uuid}/rollback).
     * Destructive on the volume's current content; the caller ensures the volume is not in use.
     */
    public void rollbackVolume(String volumeUuid, String snapshotUuid) {
        ObjectNode body = mapper.createObjectNode();
        body.put("srcSnapshotUUID", snapshotUuid);
        body.put("projectName", project);
        execute("PUT", projectPath("volumes", volumeUuid) + "/rollback", body.toString());
    }

    public boolean deleteSnapshot(String uuid) {
        try {
            execute("DELETE", projectPath("snapshots", uuid), null);
            return true;
        } catch (LightbitsApiException e) {
            if (e.getKind() == Kind.NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    // ---------------- internals ----------------

    private String projectPath(String kind, String uuid) {
        return projectColl(kind) + "/" + uuid;
    }

    private String projectColl(String kind) {
        return "/api/v2/projects/" + urlenc(project) + "/" + kind;
    }

    private Optional<Volume> firstLiveVolume(String listResponse, String expectedName) {
        try {
            JsonNode root = mapper.readTree(listResponse);
            for (JsonNode n : root.path("volumes")) {
                Volume v = mapper.treeToValue(n, Volume.class);
                // exact-name check client-side: the snapshots list ignores its 'name' query
                // param (see getSnapshotByName), so don't trust server-side filtering here either
                if (!v.isDeletedOrDeleting() && expectedName.equals(v.getName())) {
                    return Optional.of(v);
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new LightbitsApiException(Kind.UNKNOWN, "malformed volume list response", e);
        }
    }

    private <T> T parse(String body, Class<T> type) {
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw new LightbitsApiException(Kind.UNKNOWN, "malformed API response for " + type.getSimpleName(), e);
        }
    }

    private String execute(String method, String path, String jsonBody) {
        LightbitsApiException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Content-Type", "application/json");
                HttpRequest.BodyPublisher pub = jsonBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
                HttpResponse<String> resp = http.send(rb.method(method, pub).build(),
                        HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    return resp.body();
                }
                Kind kind = LightbitsApiException.kindOf(status, resp.body());
                LightbitsApiException e = new LightbitsApiException(kind, status,
                        method + " " + path + " -> HTTP " + status + ": " + snippet(resp.body()));
                if (kind != Kind.TRANSIENT) {
                    throw e;
                }
                last = e;
            } catch (LightbitsApiException e) {
                throw e;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                last = new LightbitsApiException(Kind.TRANSIENT, method + " " + path + " failed: " + e.getMessage(), e);
            }
            if (attempt < MAX_ATTEMPTS) {
                sleep(retryBackoffMillis * attempt);
            }
        }
        throw last;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private static String urlenc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String query(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(sb.length() == 0 ? "?" : "&").append(e.getKey()).append("=").append(urlenc(e.getValue()));
        }
        return sb.toString();
    }
}
