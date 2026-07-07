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

    public ClusterInfo getClusterInfo() {
        return parse(execute("GET", "/api/v2/cluster", null), ClusterInfo.class);
    }

    // ---------------- volumes ----------------

    /**
     * Idempotent create: name collisions (409) resolve to the existing volume,
     * so a retried CloudStack job converges instead of failing.
     */
    public Volume createVolume(String name, long sizeBytes, int replicaCount, List<String> aclNqns) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        body.put("size", String.valueOf(sizeBytes));
        body.put("replicaCount", replicaCount);
        body.put("projectName", project);
        ObjectNode acl = body.putObject("acl");
        ArrayNode values = acl.putArray("values");
        (aclNqns == null || aclNqns.isEmpty() ? List.of("ALLOW_NONE") : aclNqns).forEach(values::add);
        try {
            return parse(execute("POST", "/api/v2/volumes", body.toString()), Volume.class);
        } catch (LightbitsApiException e) {
            if (e.getKind() == Kind.CONFLICT) {
                return getVolumeByName(name).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    public Optional<Volume> getVolumeByName(String name) {
        Map<String, String> q = new HashMap<>();
        q.put("name", name);
        q.put("project_name", project);
        String resp = execute("GET", "/api/v2/volumes" + query(q), null);
        return firstLiveVolume(resp);
    }

    public Optional<Volume> getVolume(String uuid) {
        try {
            String resp = execute("GET", "/api/v2/volumes/" + uuid + query(Map.of("project_name", project)), null);
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
        String resp = execute("GET", "/api/v2/volumes" + query(Map.of("project_name", project)), null);
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

    public Optional<Snapshot> getSnapshotByName(String name) {
        Map<String, String> q = new HashMap<>();
        q.put("name", name);
        String resp = execute("GET", projectColl("snapshots") + query(q), null);
        try {
            JsonNode root = mapper.readTree(resp);
            for (JsonNode n : root.path("snapshots")) {
                Snapshot s = mapper.treeToValue(n, Snapshot.class);
                if (!s.isDeletedOrDeleting()) {
                    return Optional.of(s);
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new LightbitsApiException(Kind.UNKNOWN, "malformed snapshot list response", e);
        }
    }

    /** Create a volume cloned from a snapshot (template-from-snapshot / volume-from-snapshot). */
    public Volume createVolumeFromSnapshot(String name, String snapshotUuid, long sizeBytes, int replicaCount, List<String> aclNqns) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        // LightOS requires an explicit size even when cloning; it must be >= the snapshot size.
        body.put("size", String.valueOf(sizeBytes));
        body.put("projectName", project);
        body.put("replicaCount", replicaCount);
        body.put("sourceSnapshotUUID", snapshotUuid);
        ArrayNode values = body.putObject("acl").putArray("values");
        (aclNqns == null || aclNqns.isEmpty() ? List.of("ALLOW_NONE") : aclNqns).forEach(values::add);
        try {
            return parse(execute("POST", "/api/v2/volumes", body.toString()), Volume.class);
        } catch (LightbitsApiException e) {
            if (e.getKind() == Kind.CONFLICT) {
                return getVolumeByName(name).orElseThrow(() -> e);
            }
            throw e;
        }
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

    private Optional<Volume> firstLiveVolume(String listResponse) {
        try {
            JsonNode root = mapper.readTree(listResponse);
            for (JsonNode n : root.path("volumes")) {
                Volume v = mapper.treeToValue(n, Volume.class);
                if (!v.isDeletedOrDeleting()) {
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
