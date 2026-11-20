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
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.apache.cloudstack.storage.datastore.client.LightbitsApiException.Kind;
import org.apache.cloudstack.storage.datastore.client.model.Snapshot;
import org.apache.cloudstack.storage.datastore.client.model.Volume;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
 * Credential scope: the JWT is PROJECT-scoped by design (project-admin on {@code project}),
 * never the cluster-wide system JWT. Such a token can operate on
 * {@code /api/v2/projects/{p}/...} and can read {@code /api/v2/nodes}, but is denied
 * cluster-wide routes like {@code GET /api/v2/cluster} (HTTP 403) — so this client exposes
 * no cluster-scoped calls. Reachability checks use {@link #verifyProjectAccess()}; capacity
 * comes from {@link #nodeCapacity()}.
 */
public class LightbitsApiClient {
    private static final Logger LOG = LogManager.getLogger(LightbitsApiClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Endpoints that recently refused a connection or did not answer in time, with the time until which they
     * are tried last. Shared by all clients: the plugin builds a client per operation, and
     * without this every operation would pay the connect timeout of a dead node again.
     */
    private static final Map<String, Long> DOWN_UNTIL = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DOWN_MILLIS = 60_000;

    /** For tests: the list is shared by every client of the JVM, and test servers reuse ports. */
    static void forgetDownEndpoints() {
        DOWN_UNTIL.clear();
    }

    static boolean isMarkedDown(String base) {
        Long until = DOWN_UNTIL.get(base);
        return until != null && until > System.currentTimeMillis();
    }

    /** API endpoints of ONE cluster, e.g. https://10.0.0.1:443. Every node serves the same state. */
    private final List<String> bases;
    /** Index of the endpoint that answered last; calls start there instead of re-probing a dead node. */
    private volatile int preferred;
    private final String jwt;
    private final String project;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final long retryBackoffMillis;
    /** How long to wait for an object to settle (Creating/Updating/Rollback -> Available). */
    private long settleTimeoutMillis = 120_000;
    private long settlePollMillis = 250;

    /** Client validating the cluster certificate against the JVM trust store (CA and host name). */
    public LightbitsApiClient(String endpoint, String jwt, String project) {
        this(List.of(endpoint), jwt, project);
    }

    public LightbitsApiClient(List<String> endpoints, String jwt, String project) {
        this(endpoints, jwt, project, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), 1000);
    }

    public LightbitsApiClient(String endpoint, String jwt, String project, HttpClient http, long retryBackoffMillis) {
        this(List.of(endpoint), jwt, project, http, retryBackoffMillis);
    }

    public LightbitsApiClient(List<String> endpoints, String jwt, String project, HttpClient http, long retryBackoffMillis) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one Lightbits API endpoint is required");
        }
        List<String> b = new ArrayList<>();
        for (String endpoint : endpoints) {
            b.add(endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        }
        this.bases = List.copyOf(b);
        this.jwt = jwt;
        this.project = project;
        this.http = http;
        this.retryBackoffMillis = retryBackoffMillis;
    }

    /**
     * Client that accepts exactly the certificate(s) whose SHA-256 fingerprint is pinned, and
     * nothing else. This is the way to verify a Lightbits cluster: its API certificate is issued
     * to the name {@code api.service} by a CA internal to the cluster, so CA and host name
     * validation of an endpoint addressed by IP cannot pass. Every node presents the same
     * certificate, so one fingerprint covers all endpoints; a second fingerprint is accepted so a
     * certificate rotation can be staged (add the new one before, remove the old one after).
     */
    public static LightbitsApiClient pinned(List<String> endpoints, String jwt, String project, List<String> sha256Fingerprints) {
        return pinned(endpoints, jwt, project, sha256Fingerprints, 1000);
    }

    static LightbitsApiClient pinned(List<String> endpoints, String jwt, String project, List<String> sha256Fingerprints,
                                     long retryBackoffMillis) {
        if (sha256Fingerprints == null || sha256Fingerprints.isEmpty()) {
            throw new IllegalArgumentException("at least one certificate fingerprint is required");
        }
        List<String> pins = new ArrayList<>();
        for (String f : sha256Fingerprints) {
            pins.add(normalizeFingerprint(f));
        }
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[] {new PinnedTrustManager(pins)}, new SecureRandom());
            HttpClient http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(10)).build();
            return new LightbitsApiClient(endpoints, jwt, project, http, retryBackoffMillis);
        } catch (java.security.GeneralSecurityException e) {
            throw new LightbitsApiException(Kind.UNKNOWN, "failed to build the TLS context", e);
        }
    }

    /**
     * Canonical form of a SHA-256 certificate fingerprint: 64 upper-case hex digits. Accepts the
     * {@code openssl x509 -fingerprint -sha256} form with colons.
     */
    public static String normalizeFingerprint(String fingerprint) {
        String f = fingerprint == null ? "" : fingerprint.replace(":", "").replace(" ", "").trim().toUpperCase();
        if (!f.matches("[0-9A-F]{64}")) {
            throw new IllegalArgumentException("a certificate fingerprint must be a SHA-256 fingerprint of 64 hex digits, "
                    + "with or without colons (read it with: openssl x509 -fingerprint -sha256 -noout)");
        }
        return f;
    }

    /**
     * Trusts a server certificate only by its SHA-256 fingerprint. Extending
     * {@link X509ExtendedTrustManager} makes this the complete decision: the JDK applies its own
     * host name check only around a plain X509TrustManager, and a pinned certificate identifies
     * the cluster more strictly than a name would.
     */
    static final class PinnedTrustManager extends X509ExtendedTrustManager {
        private final List<String> pins;

        PinnedTrustManager(List<String> pins) {
            this.pins = List.copyOf(pins);
        }

        private void verify(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("the Lightbits API presented no certificate");
            }
            String actual;
            try {
                StringBuilder sb = new StringBuilder();
                for (byte b : MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())) {
                    sb.append(String.format("%02X", b));
                }
                actual = sb.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new CertificateException(e);
            }
            if (!pins.contains(actual)) {
                throw new CertificateException("the Lightbits API certificate (SHA-256 fingerprint " + actual
                        + ") does not match the fingerprint pinned on this storage pool. If the cluster certificate"
                        + " was replaced, update the pool's fingerprint; otherwise this is not the expected cluster.");
            }
        }

        @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException { verify(c); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { verify(c); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { verify(c); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { throw new CertificateException("not a server"); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { throw new CertificateException("not a server"); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { throw new CertificateException("not a server"); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    public String getProject() {
        return project;
    }

    /** Tune the settle wait (tests use short values). */
    public void setSettleWait(long timeoutMillis, long pollMillis) {
        this.settleTimeoutMillis = timeoutMillis;
        this.settlePollMillis = pollMillis;
    }

    // ---------------- nodes ----------------

    /** Number of ACTIVE nodes in the cluster — a hard upper bound on a volume's replica count. */
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
     *
     * Only nodes in state {@code Active} are counted: Activating/Deactivating/Inactive nodes
     * cannot serve I/O, so counting them would overstate both the replica upper bound and
     * the advertised capacity.
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
                    String state = n.path("state").asText("");
                    if (!"Active".equalsIgnoreCase(state)) {
                        LOG.warn("Lightbits: node {} in state '{}' excluded from capacity/replica accounting",
                                n.path("UUID").asText("?"), state);
                        continue;
                    }
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
            LOG.warn("Lightbits: unparseable node statistic {}='{}'; treating as 0", field, v.asText());
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
    public Volume createVolume(String name, long sizeBytes, int replicaCount, List<String> aclNqns, boolean compression, String qosPolicyName) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        body.put("size", String.valueOf(sizeBytes));
        body.put("replicaCount", replicaCount);
        body.put("projectName", project);
        body.put("compression", String.valueOf(compression)); // String "true"/"false"
        // fixed at 512e by design: VM boot disks need 512-byte logical blocks (LightOS default is 4K)
        body.put("sectorSize", 512);
        if (qosPolicyName != null && !qosPolicyName.isBlank()) {
            // Attach QoS by policy NAME (matches lbcli --qos-policy-name and the CSI driver).
            body.put("qosPolicyName", qosPolicyName);
        }
        ObjectNode acl = body.putObject("acl");
        ArrayNode values = acl.putArray("values");
        (aclNqns == null || aclNqns.isEmpty() ? List.of("ALLOW_NONE") : aclNqns).forEach(values::add);
        Volume created;
        boolean createdHere = true;
        try {
            // Project-scoped route: a project-scoped token is denied the flat /api/v2/volumes.
            created = parse(execute("POST", projectColl("volumes"), body.toString()), Volume.class);
        } catch (LightbitsApiException e) {
            if (e.getKind() == Kind.CONFLICT || outcomeUnknown(e)) {
                // already there, or the POST failed after it was sent: the name is ours alone,
                // so if the volume exists the create took effect and is settled like any other
                created = getVolumeByName(name).orElseThrow(() -> e);
                createdHere = false;
            } else {
                throw e;
            }
        }
        return settleNewVolume(created, createdHere);
    }

    /**
     * The create POST answers while the volume is still {@code Creating}. Report success only
     * once it is {@code Available}. If it lands in {@code Failed} or never settles, fail, and
     * remove the half-made volume, but only when THIS call created it: a volume found through a
     * 409 existed before this call and a failed lookup of it is no reason to delete it. The
     * removal is waited for, so a caller can delete the clone's source snapshot right after.
     */
    private Volume settleNewVolume(Volume created, boolean createdHere) {
        try {
            return waitForVolume(created.getUuid(), null);
        } catch (RuntimeException e) {
            if (createdHere) {
                try {
                    deleteVolume(created.getUuid());
                    waitForVolumeGone(created.getUuid());
                } catch (RuntimeException cleanup) {
                    LOG.warn("Lightbits: could not remove volume {} ({}) after its create failed: {}",
                            created.getName(), created.getUuid(), cleanup.getMessage());
                }
            }
            throw e;
        }
    }

    /**
     * Poll a volume until it is {@code Available}. When {@code changedFromEtag} is given the
     * volume must also carry a DIFFERENT ETag: an update or rollback PUT returns before the
     * change is applied and the volume was already Available before the request, so
     * "Available" alone proves nothing.
     */
    Volume waitForVolume(String uuid, String changedFromEtag) {
        long deadline = System.nanoTime() + settleTimeoutMillis * 1_000_000L;
        String lastState = "?";
        while (true) {
            Volume v;
            try {
                v = parse(pollGet(projectPath("volumes", uuid), deadline), Volume.class);
            } catch (LightbitsApiException e) {
                if (e.getKind() == Kind.NOT_FOUND) {
                    throw new LightbitsApiException(Kind.NOT_FOUND, 404,
                            "volume " + uuid + " disappeared from the cluster while waiting for it to become Available");
                }
                if (e.getKind() == Kind.TRANSIENT && System.nanoTime() < deadline) {
                    sleep(settlePollMillis);
                    continue;
                }
                throw e;
            }
            lastState = v.getState();
            if (v.isFailed()) {
                throw new LightbitsApiException(Kind.UNKNOWN, -1,
                        "volume " + v.getName() + " (" + uuid + ") is in state Failed on the cluster");
            }
            if (v.isDeletedOrDeleting()) {
                throw new LightbitsApiException(Kind.NOT_FOUND, 404,
                        "volume " + v.getName() + " (" + uuid + ") is being deleted on the cluster");
            }
            // a poll that carries no revision is not evidence of a change
            boolean changed = changedFromEtag == null
                    || (v.getEtag() != null && !v.getEtag().isEmpty() && !changedFromEtag.equals(v.getEtag()));
            if (v.isAvailable() && changed) {
                return v;
            }
            if (System.nanoTime() >= deadline) {
                throw new LightbitsApiException(Kind.TRANSIENT, -1, "volume " + v.getName() + " (" + uuid
                        + ") did not become Available within " + (settleTimeoutMillis / 1000) + "s (last state "
                        + lastState + (changedFromEtag == null ? "" : ", change not applied") + ")");
            }
            sleep(settlePollMillis);
        }
    }

    /** Poll a snapshot until it is {@code Available}; a clone from a Creating snapshot is refused. */
    Snapshot waitForSnapshot(String uuid) {
        long deadline = System.nanoTime() + settleTimeoutMillis * 1_000_000L;
        while (true) {
            Snapshot sn;
            try {
                sn = parse(pollGet(projectPath("snapshots", uuid), deadline), Snapshot.class);
            } catch (LightbitsApiException e) {
                if (e.getKind() == Kind.TRANSIENT && System.nanoTime() < deadline) {
                    sleep(settlePollMillis);
                    continue;
                }
                throw e;
            }
            if (sn.isFailed()) {
                throw new LightbitsApiException(Kind.UNKNOWN, -1,
                        "snapshot " + sn.getName() + " (" + uuid + ") is in state Failed on the cluster");
            }
            if (sn.isDeletedOrDeleting()) {
                throw new LightbitsApiException(Kind.NOT_FOUND, 404,
                        "snapshot " + sn.getName() + " (" + uuid + ") is being deleted on the cluster");
            }
            if (sn.isAvailable()) {
                return sn;
            }
            if (System.nanoTime() >= deadline) {
                throw new LightbitsApiException(Kind.TRANSIENT, -1, "snapshot " + sn.getName() + " (" + uuid
                        + ") did not become Available within " + (settleTimeoutMillis / 1000) + "s (last state "
                        + sn.getState() + ")");
            }
            sleep(settlePollMillis);
        }
    }

    /** Poll until a deleted volume is really gone (404 or Deleted), so dependants can be removed. */
    public void waitForVolumeGone(String uuid) {
        long deadline = System.nanoTime() + settleTimeoutMillis * 1_000_000L;
        String last = "unknown";
        while (true) {
            try {
                Volume v = parse(pollGet(projectPath("volumes", uuid), deadline), Volume.class);
                if ("Deleted".equals(v.getState())) {
                    return;
                }
                last = "state " + v.getState();
            } catch (LightbitsApiException e) {
                if (e.getKind() == Kind.NOT_FOUND) {
                    return;
                }
                if (e.getKind() != Kind.TRANSIENT) {
                    throw e;
                }
                last = e.getMessage();
            }
            if (System.nanoTime() >= deadline) {
                throw new LightbitsApiException(Kind.TRANSIENT, -1, "volume " + uuid + " was not removed within "
                        + (settleTimeoutMillis / 1000) + "s (last: " + last + ")");
            }
            sleep(settlePollMillis);
        }
    }

    /**
     * One GET of a settle poll: a single attempt whose request timeout is capped by what is left
     * of the settle deadline, so the wait as a whole ends at the deadline instead of adding
     * three 30-second attempts and their backoff to it. A transient failure is the caller's to
     * ride out until the deadline.
     */
    private String pollGet(String path, long deadlineNanos) {
        long leftMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        long timeoutMillis = Math.max(MIN_POLL_TIMEOUT_MILLIS, Math.min(REQUEST_TIMEOUT.toMillis(), leftMillis));
        return execute("GET", path, null, null, Duration.ofMillis(timeoutMillis), 1, deadlineNanos);
    }

    public Optional<Volume> getVolumeByName(String name) {
        // Project-scoped list route (flat /api/v2/volumes?project_name= is denied to a
        // project-scoped token). This is a HOT path (grant/revoke on every VM start/stop,
        // resize, delete), so send the name filter as a hint — without it every lookup pulls
        // the project's full volume list, and a paginated response could hide the match.
        // firstLiveVolume still exact-matches client-side: server-side filtering is a
        // best-effort narrowing, never trusted (same caution as getSnapshotByName).
        Map<String, String> q = new HashMap<>();
        q.put("name", name);
        String resp = execute("GET", projectColl("volumes") + query(q), null);
        return firstLiveVolume(resp, name);
    }

    /**
     * Like {@link #getVolumeByName} but also returns a volume that is still {@code Deleting}:
     * a retried delete must wait for that removal instead of taking the volume for gone.
     */
    public Optional<Volume> getVolumeByNameIncludingDeleting(String name) {
        Map<String, String> q = new HashMap<>();
        q.put("name", name);
        String resp = execute("GET", projectColl("volumes") + query(q), null);
        try {
            Volume deleting = null;
            for (JsonNode n : mapper.readTree(resp).path("volumes")) {
                Volume v = mapper.treeToValue(n, Volume.class);
                if (!name.equals(v.getName()) || "Deleted".equals(v.getState())) {
                    continue;
                }
                if (!v.isDeletedOrDeleting()) {
                    return Optional.of(v);
                }
                deleting = v;
            }
            return Optional.ofNullable(deleting);
        } catch (IOException e) {
            throw new LightbitsApiException(Kind.UNKNOWN, "malformed volume list response", e);
        }
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
        Volume before = getVolume(uuid).orElseThrow(() -> new LightbitsApiException(Kind.NOT_FOUND, 404,
                "volume " + uuid + " not found on the cluster"));
        requireRevision(before.getEtag(), "resize of volume " + uuid);
        ObjectNode body = mapper.createObjectNode();
        body.put("size", String.valueOf(newSizeBytes));
        body.put("projectName", project);
        try {
            executeConditional("PUT", projectPath("volumes", uuid), body.toString(), before.getEtag());
        } catch (LightbitsApiException e) {
            if (e.getKind() != Kind.TRANSIENT) {
                throw e;
            }
            // Sent, not confirmed. Not sent again: if it was applied the revision has moved and
            // a replay would be refused with 412 and reported as a failure. The volume decides.
            Volume now = getVolume(uuid).orElseThrow(() -> e);
            if (before.getEtag().equals(now.getEtag()) || now.getSizeBytes() != newSizeBytes) {
                throw e;
            }
            LOG.warn("Lightbits: resize of {} was not confirmed ({}) but the volume shows it applied", uuid, e.getMessage());
        }
        Volume after = waitForVolume(uuid, before.getEtag());
        if (after.getSizeBytes() != newSizeBytes) {
            throw new LightbitsApiException(Kind.UNKNOWN, -1, "volume " + after.getName() + " (" + uuid
                    + ") reports " + after.getSizeBytes() + " bytes after a resize to " + newSizeBytes);
        }
    }

    /**
     * Replace a volume's ACL. {@code ifMatchEtag} (the ETag of the volume the caller computed
     * the new ACL from) makes the update conditional: if anything changed the volume in
     * between, the cluster answers 412 ({@link Kind#PRECONDITION}) and the caller must re-read
     * and retry. Without it two concurrent read-modify-write updates lose one host's access.
     * Returns once the ACL change is applied (the volume is back to Available).
     */
    public void updateVolumeAcl(String uuid, List<String> nqns, String ifMatchEtag) {
        ObjectNode body = mapper.createObjectNode();
        body.put("projectName", project);
        ArrayNode values = body.putObject("acl").putArray("values");
        (nqns == null || nqns.isEmpty() ? List.of("ALLOW_NONE") : nqns).forEach(values::add);
        requireRevision(ifMatchEtag, "ACL update of volume " + uuid);
        executeConditional("PUT", projectPath("volumes", uuid), body.toString(), ifMatchEtag);
        waitForVolume(uuid, ifMatchEtag);
    }

    /**
     * A read-modify-write without the revision it was computed from would be sent
     * unconditionally and could overwrite a concurrent change: refuse it instead.
     */
    private static void requireRevision(String etag, String what) {
        if (etag == null || etag.isEmpty()) {
            throw new LightbitsApiException(Kind.INVALID, -1, what
                    + ": the cluster does not report the volume's revision (ETag), so the update cannot be made conditional");
        }
    }

    /**
     * A conditional (If-Match) update is sent exactly once. If it fails after it was sent the
     * outcome is unknown, and sending it again is wrong either way: if the first one was
     * applied the revision has moved and the replay is refused with 412, which would report a
     * change that happened as a failure. The caller decides from the volume's state.
     */
    private String executeConditional(String method, String path, String jsonBody, String ifMatchEtag) {
        return execute(method, path, jsonBody, ifMatchEtag, REQUEST_TIMEOUT, 1, NO_DEADLINE);
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
            // the DELETE may have been applied before it failed: the volume's state decides
            if (outcomeUnknown(e) && getVolume(uuid).isEmpty()) {
                return true;
            }
            throw e;
        }
    }

    /** A mutating call that failed after it was sent: it may or may not have been applied. */
    private static boolean outcomeUnknown(LightbitsApiException e) {
        return e.getKind() == Kind.TRANSIENT && !e.isNeverSent();
    }

    // ---------------- snapshots ----------------

    public Snapshot createSnapshot(String name, String sourceVolumeUuid) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        body.put("sourceVolumeUUID", sourceVolumeUuid);
        body.put("projectName", project);
        Snapshot created;
        boolean createdHere = true;
        try {
            // Snapshots live under the project-scoped collection; the non-scoped
            // /api/v2/snapshots route does not exist on LightOS (404).
            created = parse(execute("POST", projectColl("snapshots"), body.toString()), Snapshot.class);
        } catch (LightbitsApiException e) {
            if (e.getKind() == Kind.CONFLICT || outcomeUnknown(e)) {
                created = getSnapshotByName(name).orElseThrow(() -> e);
                createdHere = false;
            } else {
                throw e;
            }
        }
        // The POST answers while the snapshot is still Creating, and a clone or rollback from a
        // Creating snapshot is refused: report success only once it is Available.
        try {
            Snapshot settled = waitForSnapshot(created.getUuid());
            settled.setCreatedByThisCall(createdHere);
            return settled;
        } catch (RuntimeException e) {
            // only a snapshot this call created: one found through a 409 is an existing restore point
            if (createdHere) {
                try {
                    deleteSnapshot(created.getUuid());
                } catch (RuntimeException cleanup) {
                    LOG.warn("Lightbits: could not remove snapshot {} ({}) after its create failed: {}",
                            name, created.getUuid(), cleanup.getMessage());
                }
            }
            throw e;
        }
    }

    public Optional<Snapshot> getSnapshot(String uuid) {
        try {
            Snapshot sn = parse(execute("GET", projectPath("snapshots", uuid), null), Snapshot.class);
            return sn.isDeletedOrDeleting() ? Optional.empty() : Optional.of(sn);
        } catch (LightbitsApiException e) {
            if (e.getKind() == Kind.NOT_FOUND) {
                return Optional.empty();
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
    public Volume createVolumeFromSnapshot(String name, String snapshotUuid, long sizeBytes, int replicaCount, List<String> aclNqns, boolean compression, String qosPolicyName) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        // LightOS requires an explicit size even when cloning; it must be >= the snapshot size.
        body.put("size", String.valueOf(sizeBytes));
        body.put("projectName", project);
        body.put("replicaCount", replicaCount);
        body.put("sourceSnapshotUUID", snapshotUuid);
        body.put("compression", String.valueOf(compression)); // "true"/"false"
        // fixed at 512e by design: VM boot disks need 512-byte logical blocks (LightOS default is 4K)
        body.put("sectorSize", 512);
        if (qosPolicyName != null && !qosPolicyName.isBlank()) {
            body.put("qosPolicyName", qosPolicyName);
        }
        ArrayNode values = body.putObject("acl").putArray("values");
        (aclNqns == null || aclNqns.isEmpty() ? List.of("ALLOW_NONE") : aclNqns).forEach(values::add);
        Volume created;
        boolean createdHere = true;
        try {
            // Project-scoped route (flat /api/v2/volumes is denied to a project-scoped token).
            created = parse(execute("POST", projectColl("volumes"), body.toString()), Volume.class);
        } catch (LightbitsApiException e) {
            if (e.getKind() == Kind.CONFLICT || outcomeUnknown(e)) {
                // already there, or the POST failed after it was sent: the name is ours alone,
                // so if the volume exists the create took effect and is settled like any other
                created = getVolumeByName(name).orElseThrow(() -> e);
                createdHere = false;
            } else {
                throw e;
            }
        }
        return settleNewVolume(created, createdHere);
    }

    /**
     * Roll a volume back to one of its snapshots, in place (PUT .../volumes/{uuid}/rollback).
     * Destructive on the volume's current content; the caller ensures the volume is not in use.
     */
    public void rollbackVolume(String volumeUuid, String snapshotUuid) {
        Volume before = getVolume(volumeUuid).orElseThrow(() -> new LightbitsApiException(Kind.NOT_FOUND, 404,
                "volume " + volumeUuid + " not found on the cluster"));
        rollbackVolume(volumeUuid, snapshotUuid, before.getEtag());
    }

    /**
     * Roll back, conditional on {@code ifMatchEtag}: the ETag of the volume revision the caller
     * validated (size, ACL). If the volume changed since (a resize, a host attach) the cluster
     * answers 412 ({@link Kind#PRECONDITION}) and nothing is rolled back.
     */
    public void rollbackVolume(String volumeUuid, String snapshotUuid, String ifMatchEtag) {
        ObjectNode body = mapper.createObjectNode();
        body.put("srcSnapshotUUID", snapshotUuid);
        body.put("projectName", project);
        requireRevision(ifMatchEtag, "rollback of volume " + volumeUuid);
        try {
            executeConditional("PUT", projectPath("volumes", volumeUuid) + "/rollback", body.toString(), ifMatchEtag);
        } catch (LightbitsApiException e) {
            if (e.getKind() != Kind.TRANSIENT) {
                throw e;
            }
            // No way to tell from the volume whether a rollback was applied (another change
            // moves the revision just the same), so this is never reported as a success.
            // Running the revert again is safe: it reads the volume afresh and rolls back again.
            throw new LightbitsApiException(Kind.TRANSIENT, e.getHttpStatus(), "the rollback of volume " + volumeUuid
                    + " was sent but not confirmed (" + e.getMessage() + "); its outcome is unknown, run the revert again");
        }
        // The PUT answers {} immediately and the volume was Available before it: wait for the
        // ETag to move, which is the only evidence that the rollback was applied.
        waitForVolume(volumeUuid, ifMatchEtag);
    }

    /**
     * Idempotent snapshot delete. Whatever status a failed DELETE carries (404, 412, a
     * transient error after the request was applied), the snapshot's STATE decides: if it is
     * gone or on its way out the delete succeeded.
     */
    public boolean deleteSnapshot(String uuid) {
        try {
            execute("DELETE", projectPath("snapshots", uuid), null);
            return true;
        } catch (LightbitsApiException e) {
            if (e.getKind() == Kind.NOT_FOUND) {
                return false;
            }
            if (e.getKind() == Kind.AUTH) {
                throw e;
            }
            if (getSnapshot(uuid).isEmpty()) {
                return false;
            }
            throw e;
        }
    }

    /** Live snapshots whose source is the given volume. */
    public List<Snapshot> listSnapshotsOfVolume(String volumeUuid) {
        List<Snapshot> out = new ArrayList<>();
        for (Snapshot sn : listSnapshots()) {
            if (volumeUuid.equals(sn.getSourceVolumeUUID())) {
                out.add(sn);
            }
        }
        return out;
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
        return execute(method, path, jsonBody, null);
    }

    /**
     * Send one API call, failing over across the cluster's endpoints.
     *
     * Reads (GET) move to the next endpoint on any transient failure (connection failure,
     * timeout, 5xx) and the whole round is repeated with backoff. Mutating calls move on only
     * when the request provably never reached the endpoint (connection refused, connect timeout,
     * unresolvable name, failed TLS handshake): after a 5xx, a read timeout or a reset the change
     * may already be applied, so that failure is reported as is and the caller decides by
     * looking at the object's state. A 4xx is a definitive answer from the cluster and is never
     * retried on another endpoint.
     */
    private String execute(String method, String path, String jsonBody, String ifMatchEtag) {
        return execute(method, path, jsonBody, ifMatchEtag, REQUEST_TIMEOUT, MAX_ATTEMPTS, NO_DEADLINE);
    }

    private static final long NO_DEADLINE = Long.MIN_VALUE;
    private static final long MIN_POLL_TIMEOUT_MILLIS = 1000L;

    /**
     * {@code rounds} passes over all endpoints. A settle poll uses one round and passes its
     * absolute deadline ({@link System#nanoTime()} based): all endpoints share it, so each
     * attempt gets only what is left and a failover stops once the deadline has passed.
     */
    private String execute(String method, String path, String jsonBody, String ifMatchEtag, Duration timeout, int rounds,
            long deadlineNanos) {
        boolean read = "GET".equals(method);
        LightbitsApiException last = null;
        for (int round = 1; round <= rounds; round++) {
            int start = preferred;
            // endpoints known to be down go last: still tried, but not before the live ones
            List<Integer> order = new ArrayList<>();
            List<Integer> down = new ArrayList<>();
            for (int i = 0; i < bases.size(); i++) {
                int idx = (start + i) % bases.size();
                (isMarkedDown(bases.get(idx)) ? down : order).add(idx);
            }
            order.addAll(down);
            String failedVia = null;
            for (int idx : order) {
                String base = bases.get(idx);
                Duration attemptTimeout = timeout;
                if (deadlineNanos != NO_DEADLINE) {
                    long leftMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                    if (last != null && leftMillis <= 0) {
                        throw last;
                    }
                    // rounded up: an attempt that runs into the deadline ends at or after it, never a
                    // fraction of a millisecond before, where the caller would start one more poll
                    attemptTimeout = Duration.ofMillis(Math.max(MIN_POLL_TIMEOUT_MILLIS, Math.min(timeout.toMillis(), leftMillis + 1)));
                }
                try {
                    String body = send(base, method, path, jsonBody, ifMatchEtag, attemptTimeout);
                    DOWN_UNTIL.remove(base);
                    if (failedVia != null) {
                        preferred = idx;
                        LOG.warn("Lightbits API {} {} succeeded via {} after failing via {} ({}); check that endpoint",
                                method, path, base, failedVia, last == null ? "?" : last.getMessage());
                    }
                    return body;
                } catch (LightbitsApiException e) {
                    if (e.getKind() != Kind.TRANSIENT) {
                        throw e;
                    }
                    // Tried last for a while: an endpoint that could not be reached, and one that
                    // took a request and did not answer in time. This is only the order of the
                    // next attempts; whether a mutation may be sent again is decided below.
                    if (e.isNeverSent() || timedOut(e)) {
                        DOWN_UNTIL.put(base, System.currentTimeMillis() + DOWN_MILLIS);
                    }
                    if (!read && !e.isNeverSent()) {
                        throw e;
                    }
                    last = e;
                    failedVia = base;
                }
            }
            if (round < rounds) {
                sleep(retryBackoffMillis * round);
            }
        }
        throw last;
    }

    private String send(String base, String method, String path, String jsonBody, String ifMatchEtag, Duration timeout) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + jwt)
                    .header("Content-Type", "application/json");
            if (ifMatchEtag != null && !ifMatchEtag.isEmpty()) {
                rb.header("If-Match", ifMatchEtag);
            }
            HttpRequest.BodyPublisher pub = jsonBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
            HttpResponse<String> resp = exchange(rb.method(method, pub).build(), timeout);
            int status = resp.statusCode();
            if (status >= 200 && status < 300) {
                return resp.body();
            }
            throw new LightbitsApiException(LightbitsApiException.kindOf(status, resp.body()), status,
                    method + " " + path + " via " + base + " -> HTTP " + status + ": " + snippet(resp.body()));
        } catch (IOException | InterruptedException | UnresolvedAddressException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LightbitsApiException failure = new LightbitsApiException(Kind.TRANSIENT,
                    method + " " + path + " via " + base + " failed: " + describe(e), e);
            return rethrow(neverReachedTheEndpoint(e) ? failure.neverSent() : failure);
        }
    }

    /**
     * The whole exchange within {@code timeout}, body included. The request timeout of the JDK
     * client ends when the response headers arrive; an endpoint that sends headers and then
     * stalls would hold a blocking send for as long as it likes, past the failover and past a
     * settle deadline. The exchange is abandoned at the limit instead and reported as a timeout.
     */
    private HttpResponse<String> exchange(HttpRequest request, Duration timeout) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<String>> pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new HttpTimeoutException("no complete response within " + timeout.toMillis() + " ms");
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof UnresolvedAddressException) {
                throw (UnresolvedAddressException) cause;
            }
            throw new IOException(cause == null ? e.toString() : cause.toString(), cause);
        }
    }

    /** True when the endpoint accepted the request (or the connection) and did not answer in time. */
    static boolean timedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static String rethrow(LightbitsApiException e) {
        throw e;
    }

    /** True for failures that happen before any byte of the request is sent. */
    static boolean neverReachedTheEndpoint(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException
                    || t instanceof UnknownHostException || t instanceof UnresolvedAddressException
                    || t instanceof SSLHandshakeException || t instanceof CertificateException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * A message an operator can act on. A pinning refusal is buried under two SSL wrappers, and
     * a failed CA validation surfaces as "PKIX path building failed", which says nothing about
     * what to do.
     */
    static String describe(Throwable e) {
        boolean handshake = false;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CertificateException && t.getMessage() != null && t.getMessage().contains("pinned")) {
                return t.getMessage();
            }
            handshake |= t instanceof SSLHandshakeException;
            if (t.getCause() == t) {
                break;
            }
        }
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (handshake) {
            return "the certificate of the Lightbits API is not trusted (" + msg + "). A Lightbits cluster is verified by"
                    + " its certificate fingerprint: read it on a Lightbits node with 'openssl s_client -connect 127.0.0.1:443"
                    + " </dev/null | openssl x509 -fingerprint -sha256 -noout' and set it as the " + "tlsFingerprint"
                    + " detail of the storage pool";
        }
        return msg;
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
