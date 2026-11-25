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
package com.cloud.hypervisor.kvm.storage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.exception.CloudRuntimeException;

/**
 * NVMe/TCP operations of the KVM agent for Lightbits volumes: connect through the cluster's
 * discovery service, find the namespace of a volume, and prove it is the right one.
 *
 * Rules this class enforces:
 * - every external command is run as an argument vector, never through a shell, and every
 *   value that reaches a command or a path is validated first;
 * - a failed connect is reported with what failed, not as a silent 60 second wait;
 * - a device path is handed out only after the namespace's own UUID (sysfs) has been matched
 *   against the volume: a by-id link is a udev artifact and can be stale or point elsewhere.
 */
class LightbitsNvmeHost {
    private static final Logger LOG = LogManager.getLogger(LightbitsNvmeHost.class);

    static final String BY_ID_PREFIX = "/dev/disk/by-id/nvme-uuid.";
    static final String LIGHTBITS_NQN_PREFIX = "nqn.2016-01.com.lightbitslabs:";
    static final int DEFAULT_DISCOVERY_PORT = 8009;
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern ENDPOINT = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]{0,252})(:[0-9]{1,5})?");
    static final long CONNECT_TIMEOUT_MS = 60_000;
    private static final Pattern SUBSYS_NQN = Pattern.compile("nqn\\.2016-01\\.com\\.lightbitslabs:[A-Za-z0-9:._-]{1,200}");
    private static final Pattern NAMESPACE_ENTRY = Pattern.compile("nvme[0-9]+(c[0-9]+)?n[0-9]+");
    private static final Pattern CONTROLLER_ENTRY = Pattern.compile("nvme[0-9]+");
    private static final String SUBSYSTEMS = "/sys/class/nvme-subsystem";
    private static final AtomicInteger ROTATION = new AtomicInteger();
    /** Two-way: paths rejected for lacking the controller key that could not be disconnected (agent-wide). */
    private static final java.util.Map<String, String> REJECTED = new java.util.concurrent.ConcurrentHashMap<>();

    /** The host, as far as this class needs it. Replaced by a fake in unit tests. */
    interface Sys {
        Result run(long timeoutMillis, String... argv);
        boolean exists(String path);
        /** Trimmed content of a small file, or null if it cannot be read. */
        String read(String path);
        /** The path with symbolic links resolved, or null if it does not resolve. */
        String canonical(String path);
        /** Names in a directory, empty if it does not exist. */
        List<String> list(String dir);
        /** Names in a directory, or null if it cannot be read (gone, or an I/O error). */
        default List<String> listOrNull(String dir) {
            return list(dir);
        }
        /** Monotonic time in nanoseconds (a test moves it by hand). */
        default long nanoTime() {
            return System.nanoTime();
        }

        /**
         * Make one NVMe controller: write {@code options} to /dev/nvme-fabrics, as nvme-cli does.
         * The options carry the keys, so they never reach a command line or a message. Throws
         * with the kernel's error (for example "Operation already in progress" for a port this
         * host is connected to already, "Key was rejected by service" for a failed
         * authentication) or after {@code timeoutMillis}.
         */
        default void connectFabrics(String options, long timeoutMillis) {
            throw new UnsupportedOperationException("connectFabrics");
        }
        void sleep(long millis);
    }

    static final class Result {
        final int exit;
        final String output;

        Result(int exit, String output) {
            this.exit = exit;
            this.output = output == null ? "" : output.trim();
        }

        boolean ok() {
            return exit == 0;
        }
    }

    /** One NVMe controller (a path to one cluster node) of a Lightbits subsystem. */
    static final class Path {
        final String controller;
        final String subsysNqn;
        final String address;
        final String state;

        Path(String controller, String subsysNqn, String address, String state) {
            this.controller = controller;
            this.subsysNqn = subsysNqn;
            this.address = address;
            this.state = state;
        }

        boolean live() {
            return "live".equals(state);
        }

        @Override
        public String toString() {
            return controller + " " + address + " " + state;
        }
    }

    /**
     * One data port a two-way pool connects to directly, and the options written to
     * /dev/nvme-fabrics for it. The options hold the keys: {@link #toString()} leaves them out.
     */
    static final class Target {
        final String subsysNqn;
        final String address;
        final String service;
        final String options;

        Target(String subsysNqn, String address, String service, String options) {
            this.subsysNqn = subsysNqn;
            this.address = address;
            this.service = service;
            this.options = options;
        }

        String port() {
            return subsysNqn + " " + address + ":" + service;
        }

        @Override
        public String toString() {
            return address + ":" + service;
        }
    }

    private final Sys sys;

    LightbitsNvmeHost() {
        this(new RealSys());
    }

    LightbitsNvmeHost(Sys sys) {
        this.sys = sys;
    }

    static String requireVolumeUuid(String volumeUuid) {
        if (volumeUuid == null || !UUID.matcher(volumeUuid).matches()) {
            throw new CloudRuntimeException("Lightbits: '" + volumeUuid + "' is not a volume UUID");
        }
        return volumeUuid.toLowerCase();
    }

    /**
     * Discovery endpoints as {@code host:port}: a comma-separated list of {@code host[:port]},
     * or the single fallback host when the list is empty.
     */
    static List<String> discoveryEndpoints(String list, String fallbackHost) {
        List<String> out = new ArrayList<>();
        String raw = list == null || list.isBlank() ? fallbackHost : list;
        if (raw == null) {
            return out;
        }
        for (String e : raw.split(",")) {
            String entry = e.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (!ENDPOINT.matcher(entry).matches()) {
                throw new CloudRuntimeException("Lightbits: '" + entry + "' is not a discovery endpoint (host or host:port)");
            }
            String withPort = entry.contains(":") ? entry : entry + ":" + DEFAULT_DISCOVERY_PORT;
            if (!out.contains(withPort)) {
                out.add(withPort);
            }
        }
        return out;
    }

    /**
     * Connect this host to the cluster through its discovery service: {@code nvme connect-all}
     * against one endpoint connects every node the cluster advertises, and is a no-op for
     * nodes that are connected already. Endpoints are tried in turn, starting at a rotating
     * position, until one answers.
     *
     * If none answers but the host already holds live paths the attach goes on (the data path
     * does not need the discovery service); with no live path it fails here, naming every
     * endpoint and its error, instead of waiting for a device that cannot appear.
     */
    boolean connect(List<String> endpoints) {
        return connect(endpoints, CONNECT_TIMEOUT_MS);
    }

    /**
     * {@link #connect(List)} with the time one endpoint gets to answer. A caller that is polled
     * (the disk lookup) passes a short one, so that a cluster that does not answer costs each
     * poll seconds, not minutes.
     *
     * Returns true if a discovery endpoint answered, false if none did and the call went on
     * because the host holds live paths. Those paths can belong to another Lightbits cluster,
     * so false is no proof that this pool's cluster is reachable.
     */
    boolean connect(List<String> endpoints, long endpointTimeoutMillis) {
        return connect(endpoints, endpointTimeoutMillis, null);
    }

    /**
     * {@link #connect(List, long)} with an nvme-cli configuration file ({@code --config}) that
     * holds this host's in-band authentication key, or null to connect without a key. The
     * discovery service itself does not authenticate; the key is used for the I/O paths, and a
     * cluster with in-band authentication off ignores it.
     */
    boolean connect(List<String> endpoints, long endpointTimeoutMillis, java.nio.file.Path authConfig) {
        return connectWith(endpoints, endpointTimeoutMillis, authConfig == null ? null : endpoint -> authConfig);
    }

    /**
     * {@link #connect(List, long, java.nio.file.Path)} with the configuration file chosen per
     * endpoint, just before that endpoint is used (two-way authentication writes the ports of
     * that endpoint's discovery log page into it). A configuration that cannot be prepared
     * counts as that endpoint failing, and the next endpoint is tried.
     */
    boolean connectWith(List<String> endpoints, long endpointTimeoutMillis,
                        java.util.function.Function<String, java.nio.file.Path> configFor) {
        prepareConnect(endpoints);
        List<String> failures = new ArrayList<>();
        int start = Math.floorMod(ROTATION.getAndIncrement(), endpoints.size());
        for (int i = 0; i < endpoints.size(); i++) {
            String endpoint = endpoints.get((start + i) % endpoints.size());
            int colon = endpoint.lastIndexOf(':');
            List<String> argv = new ArrayList<>(Arrays.asList("nvme", "connect-all", "-t", "tcp",
                    "-a", endpoint.substring(0, colon), "-s", endpoint.substring(colon + 1)));
            if (configFor != null) {
                java.nio.file.Path config;
                try {
                    config = configFor.apply(endpoint);
                } catch (RuntimeException e) {
                    failures.add(endpoint + " (" + e.getMessage() + ")");
                    continue;
                }
                argv.add("--config=" + config);
            }
            Result r = sys.run(endpointTimeoutMillis, argv.toArray(new String[0]));
            if (r.ok()) {
                if (!failures.isEmpty()) {
                    LOG.warn("Lightbits: NVMe discovery succeeded via {} after failing via {}; check those endpoints",
                            endpoint, failures);
                }
                return true;
            }
            failures.add(endpoint + " (exit " + r.exit + (r.output.isEmpty() ? "" : ": " + r.output) + ")");
        }
        return noEndpointAnswered("'nvme connect-all' failed", failures);
    }

    /**
     * Two-way: connect exactly the data ports {@code targetsFor} returns for an endpoint (built
     * from that endpoint's discovery log page, read once), one controller per port written to
     * /dev/nvme-fabrics with both keys. There is no {@code nvme connect-all}, so no second
     * discovery read can add a port that lacks the controller key: every path this makes holds
     * it, and the kernel refuses a cluster that answers with another key. A port that already
     * has a path is left as it is. An endpoint counts as answered when at least one of its ports
     * is connected; the ports that failed are logged. All ports of an endpoint share its timeout.
     * Otherwise as {@link #connectWith}.
     */
    boolean connectDirect(List<String> endpoints, long endpointTimeoutMillis,
                          java.util.function.Function<String, List<Target>> targetsFor) {
        prepareConnect(endpoints);
        List<String> failures = new ArrayList<>();
        int start = Math.floorMod(ROTATION.getAndIncrement(), endpoints.size());
        for (int i = 0; i < endpoints.size(); i++) {
            String endpoint = endpoints.get((start + i) % endpoints.size());
            List<Target> targets;
            try {
                targets = targetsFor.apply(endpoint);
            } catch (RuntimeException e) {
                failures.add(endpoint + " (" + e.getMessage() + ")");
                continue;
            }
            if (targets.isEmpty()) {
                failures.add(endpoint + " (its discovery log page lists no data port)");
                continue;
            }
            java.util.Set<String> present = new java.util.HashSet<>();
            for (Path p : paths()) {
                present.add(p.subsysNqn + " " + addressPart(p.address, "traddr") + ":" + addressPart(p.address, "trsvcid"));
            }
            int connected = 0;
            List<String> portFailures = new ArrayList<>();
            // one time budget for all the endpoint's ports, as connect-all had: unreachable ports
            // do not add up while the caller holds the agent-wide connect lock
            long deadline = sys.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(endpointTimeoutMillis);
            for (Target t : targets) {
                if (present.contains(t.port())) {
                    connected++;
                    continue;
                }
                long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - sys.nanoTime());
                if (remaining <= 0) {
                    portFailures.add(t + " (not tried: the endpoint's " + endpointTimeoutMillis + " ms ran out)");
                    continue;
                }
                try {
                    sys.connectFabrics(t.options, remaining);
                    connected++;
                } catch (RuntimeException e) {
                    String why = String.valueOf(e.getMessage());
                    if (why.contains("already in progress")) {
                        connected++;   // made meanwhile (the kernel refuses a second path to the same port)
                    } else {
                        portFailures.add(t + " (" + why + ")");
                    }
                }
            }
            if (connected > 0) {
                if (!portFailures.isEmpty()) {
                    LOG.warn("Lightbits: connected {} of the {} data port(s) {} advertises; failed: {}", connected, targets.size(),
                            endpoint, portFailures);
                }
                if (!failures.isEmpty()) {
                    LOG.warn("Lightbits: NVMe discovery succeeded via {} after failing via {}; check those endpoints",
                            endpoint, failures);
                }
                return true;
            }
            failures.add(endpoint + " (no data port could be connected: " + portFailures + ")");
        }
        return noEndpointAnswered("connecting the advertised data ports failed", failures);
    }

    /** One field ({@code traddr}, {@code trsvcid}) of a controller's sysfs address, or "" if absent. */
    static String addressPart(String address, String field) {
        if (address != null) {
            for (String kv : address.split(",")) {
                if (kv.startsWith(field + "=")) {
                    return kv.substring(field.length() + 1).trim();
                }
            }
        }
        return "";
    }

    private void prepareConnect(List<String> endpoints) {
        if (endpoints.isEmpty()) {
            throw new CloudRuntimeException("Lightbits: the storage pool has no NVMe discovery endpoint");
        }
        Result mod = sys.run(30_000, "modprobe", "nvme-tcp");
        if (!mod.ok()) {
            throw new CloudRuntimeException("Lightbits: 'modprobe nvme-tcp' failed (exit " + mod.exit + "): " + mod.output
                    + ". The host needs the nvme-tcp kernel module.");
        }
    }

    private boolean noEndpointAnswered(String what, List<String> failures) {
        List<Path> live = livePaths();
        if (live.isEmpty()) {
            throw new CloudRuntimeException("Lightbits: could not connect this host to the cluster: " + what
                    + " for every discovery endpoint " + failures + " and the host has no live NVMe/TCP path to a"
                    + " Lightbits cluster. Check the data network and that the discovery service (port "
                    + DEFAULT_DISCOVERY_PORT + ") is reachable from this host.");
        }
        LOG.warn("Lightbits: {} for every discovery endpoint {}; continuing on the {} live"
                + " path(s) this host already has", what, failures, live.size());
        return false;
    }

    /**
     * Disconnect this host from every Lightbits subsystem that exposes no namespace to it, and
     * return their NQNs. Called when the host's last Lightbits pool is removed: without it the
     * NVMe/TCP connections (and their reconnect attempts, once the cluster is gone) stay on the
     * host until it reboots.
     *
     * A subsystem with a namespace is never touched: a namespace means a volume is still
     * granted to this host, by CloudStack or by anything else on the host that uses the same
     * cluster. Each subsystem is checked right before its own disconnect; a namespace the
     * kernel adds in the moment between the two cannot be excluded, since nothing lets a
     * process hold off namespace changes. A failed disconnect is logged and does not fail
     * the caller; a disconnect counts as done only when no controller of the subsystem is
     * left in sysfs, since some nvme-cli builds return 0 when a controller deletion fails.
     */
    List<String> disconnectIdleSubsystems() {
        List<String> disconnected = new ArrayList<>();
        for (String subsys : sys.list(SUBSYSTEMS)) {
            String dir = SUBSYSTEMS + "/" + subsys;
            String nqn = sys.read(dir + "/subsysnqn");
            if (nqn == null || !SUBSYS_NQN.matcher(nqn).matches()) {
                continue;
            }
            // With native NVMe multipath a namespace shows directly under the subsystem
            // (nvme3n1); without it (nvme_core.multipath=N) it shows under a controller
            // (nvme3/nvme3n1). Either counts.
            // A directory that cannot be read proves nothing: the subsystem is kept.
            List<String> namespaces = new ArrayList<>();
            String unreadable = null;
            List<String> entries = sys.listOrNull(dir);
            if (entries == null) {
                unreadable = dir;
                entries = new ArrayList<>();
            }
            for (String entry : entries) {
                if (NAMESPACE_ENTRY.matcher(entry).matches()) {
                    namespaces.add(entry);
                } else if (CONTROLLER_ENTRY.matcher(entry).matches()) {
                    List<String> below = sys.listOrNull(dir + "/" + entry);
                    if (below == null) {
                        unreadable = dir + "/" + entry;
                        continue;
                    }
                    for (String sub : below) {
                        if (NAMESPACE_ENTRY.matcher(sub).matches()) {
                            namespaces.add(entry + "/" + sub);
                        }
                    }
                }
            }
            if (unreadable != null && namespaces.isEmpty()) {
                LOG.warn("Lightbits: keeping the NVMe/TCP connection to {}: could not read {} to check for namespaces",
                        nqn, unreadable);
                continue;
            }
            if (!namespaces.isEmpty()) {
                LOG.info("Lightbits: keeping the NVMe/TCP connection to {}: {} namespace(s) still present on this host {}",
                        nqn, namespaces.size(), namespaces);
                continue;
            }
            Result r = sys.run(30_000, "nvme", "disconnect", "-n", nqn);
            List<String> left = new ArrayList<>();
            for (String entry : sys.list(dir)) {
                if (CONTROLLER_ENTRY.matcher(entry).matches()) {
                    left.add(entry);
                }
            }
            if (r.ok() && left.isEmpty()) {
                LOG.info("Lightbits: disconnected this host from {} (no Lightbits pool left, no namespace present)", nqn);
                disconnected.add(nqn);
            } else if (r.ok()) {
                LOG.warn("Lightbits: 'nvme disconnect -n {}' returned 0 but controller(s) {} are still there. The"
                        + " connection stays until it is disconnected by hand or the host reboots.", nqn, left);
            } else {
                LOG.warn("Lightbits: 'nvme disconnect -n {}' failed (exit {}): {}. The connection stays until it is"
                        + " disconnected by hand or the host reboots.", nqn, r.exit, r.output);
            }
        }
        return disconnected;
    }

    /** Every controller of a Lightbits subsystem on this host, from sysfs. */
    List<Path> paths() {
        List<Path> out = new ArrayList<>();
        for (String ctrl : sys.list("/sys/class/nvme")) {
            String nqn = sys.read("/sys/class/nvme/" + ctrl + "/subsysnqn");
            if (nqn == null || !nqn.startsWith(LIGHTBITS_NQN_PREFIX)) {
                continue;
            }
            out.add(new Path(ctrl, nqn, sys.read("/sys/class/nvme/" + ctrl + "/address"),
                    sys.read("/sys/class/nvme/" + ctrl + "/state")));
        }
        return out;
    }

    /**
     * Lightbits paths that do not hold this host's current in-band authentication key: no key
     * ({@code dhchap_secret} reads "none", or the kernel has no such attribute) or another key,
     * such as one from before the key was rotated or its file deleted. The registered key is the
     * current one, so once the cluster turns in-band authentication on, such a path fails at its
     * next reconnect, and a volume reachable only through it hangs. The secrets are compared in
     * memory and never logged. Throws when the controller list, or a listed controller's
     * subsystem NQN, cannot be read (the caller then reports the count as unknown), so a failed
     * read is never taken for "no such path". It enumerates the one listing it checked, unlike
     * {@link #paths()}, which skips what it cannot read.
     */
    int pathsWithoutKey(String currentKey) {
        return pathsWithoutSecret("dhchap_secret", currentKey);
    }

    /**
     * Lightbits paths whose controller key (the key the cluster proves itself with, two-way
     * authentication) is not the given current one: none, or an old one. After the controller
     * key changes, a path with the old one fails its next reconnect once the cluster answers
     * with the new key. Same rules as {@link #pathsWithoutKey(String)}.
     */
    int pathsWithoutCtrlKey(String currentCtrlKey) {
        return pathsWithoutSecret("dhchap_ctrl_secret", currentCtrlKey);
    }

    private int pathsWithoutSecret(String attribute, String current) {
        List<String> controllers = sys.listOrNull("/sys/class/nvme");
        if (controllers == null) {
            throw new CloudRuntimeException("could not list /sys/class/nvme to check this host's NVMe/TCP paths");
        }
        int n = 0;
        for (String ctrl : controllers) {
            String nqn = sys.read("/sys/class/nvme/" + ctrl + "/subsysnqn");
            if (nqn == null) {
                throw new CloudRuntimeException("could not read the subsystem NQN of " + ctrl
                        + " to check this host's NVMe/TCP paths");
            }
            if (!nqn.startsWith(LIGHTBITS_NQN_PREFIX)) {
                continue;
            }
            String secret = sys.read("/sys/class/nvme/" + ctrl + "/" + attribute);
            if (secret == null || !secret.trim().equals(current)) {
                n++;
            }
        }
        return n;
    }

    /**
     * The data ports a discovery endpoint advertises ({@code nvme discover ... -o json}, which
     * needs no key: the discovery service does not authenticate). Throws when the endpoint does
     * not answer or the answer cannot be read.
     */
    List<LightbitsHostKey.Port> discover(String endpoint, long timeoutMillis) {
        int colon = endpoint.lastIndexOf(':');
        Result r = sys.run(timeoutMillis, "nvme", "discover", "-t", "tcp", "-a", endpoint.substring(0, colon),
                "-s", endpoint.substring(colon + 1), "-o", "json");
        if (!r.ok()) {
            throw new CloudRuntimeException("'nvme discover' failed (exit " + r.exit + (r.output.isEmpty() ? "" : ": " + r.output) + ")");
        }
        return parseDiscovery(r.output);
    }

    /** The NVMe subsystem records (not referrals) of a discovery log page in nvme-cli's JSON. */
    static List<LightbitsHostKey.Port> parseDiscovery(String output) {
        int from = output == null ? -1 : output.indexOf('{');
        int to = output == null ? -1 : output.lastIndexOf('}');
        if (from < 0 || to < from) {
            throw new CloudRuntimeException("'nvme discover' printed no JSON");
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.substring(from, to + 1));
        } catch (java.io.IOException e) {
            throw new CloudRuntimeException("could not read the output of 'nvme discover': " + e.getMessage(), e);
        }
        List<LightbitsHostKey.Port> ports = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode rec : root.path("records")) {
            if ("tcp".equals(rec.path("trtype").asText()) && "nvme subsystem".equals(rec.path("subtype").asText())) {
                ports.add(new LightbitsHostKey.Port(rec.path("subnqn").asText(null), rec.path("traddr").asText(null),
                        rec.path("trsvcid").asText(null)));
            }
        }
        return ports;
    }

    /**
     * How many Lightbits paths this host has, from a checked enumeration: throws when the
     * controller list or a listed controller's subsystem NQN cannot be read, so an unknown count
     * is never taken for zero (rotation deletes keys only on a known zero).
     */
    int lightbitsPathCount() {
        List<String> controllers = sys.listOrNull("/sys/class/nvme");
        if (controllers == null) {
            throw new CloudRuntimeException("could not list /sys/class/nvme to count this host's NVMe/TCP paths");
        }
        int n = 0;
        for (String ctrl : controllers) {
            String nqn = sys.read("/sys/class/nvme/" + ctrl + "/subsysnqn");
            if (nqn == null) {
                throw new CloudRuntimeException("could not read the subsystem NQN of " + ctrl + " to count this host's NVMe/TCP paths");
            }
            if (nqn.startsWith(LIGHTBITS_NQN_PREFIX)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Two-way: before a namespace is handed to a VM, every path of its subsystem on this host must
     * hold the current controller key, and none may be one a connect rejected but could not
     * disconnect. Otherwise I/O could go over a path on which the host does not check the cluster;
     * the handoff is refused with what to do. Paths the host cannot read make it refuse too.
     */
    void requireTwoWayPaths(String kernelName, String currentCtrlKey) {
        String subsystem = sys.read("/sys/block/" + kernelName + "/device/subsysnqn");
        if (subsystem == null) {
            throw new CloudRuntimeException("Lightbits: could not read the subsystem of " + kernelName
                    + " to check its two-way authentication paths");
        }
        List<String> controllers = sys.listOrNull("/sys/class/nvme");
        if (controllers == null) {
            throw new CloudRuntimeException("Lightbits: could not list /sys/class/nvme to check this host's two-way paths");
        }
        List<String> unsafe = new ArrayList<>();
        for (String ctrl : controllers) {
            String nqn = sys.read("/sys/class/nvme/" + ctrl + "/subsysnqn");
            if (nqn == null) {
                throw new CloudRuntimeException("Lightbits: could not read the subsystem NQN of " + ctrl
                        + " to check this host's two-way paths");
            }
            if (!subsystem.trim().equals(nqn.trim())) {
                continue;
            }
            String secret = sys.read("/sys/class/nvme/" + ctrl + "/dhchap_ctrl_secret");
            if (secret == null || !secret.trim().equals(currentCtrlKey) || rejected(ctrl)) {
                String address = sys.read("/sys/class/nvme/" + ctrl + "/address");
                unsafe.add(ctrl + (address == null ? "" : " (" + address + ")"));
            }
        }
        if (!unsafe.isEmpty()) {
            throw new CloudRuntimeException("Lightbits: the pool uses two-way in-band authentication, but path(s) " + unsafe
                    + " of subsystem " + subsystem.trim() + " on this host do not hold its current controller key, so the host"
                    + " would not check the cluster on them; the volume is not handed over. Move the host onto two-way paths"
                    + " in the host order of INSTALL.md (\"In-band authentication\"): migrate its VMs to hosts already moved"
                    + " (CloudStack stops a VM that maintenance cannot migrate), put it in maintenance, disconnect its Lightbits"
                    + " paths (nvme disconnect -n " + subsystem.trim() + "), cancel maintenance and reconnect the host."
                    + " Running VMs are not affected.");
        }
    }

    /**
     * True when a QEMU process on this host holds the namespace's device open: a VM already uses
     * the volume. Read only on the path that would otherwise refuse a lookup, so the scan of
     * /proc is not paid by every lookup.
     */
    boolean openByVm(String kernelName) {
        String device = "/dev/" + kernelName;
        for (String pid : sys.list("/proc")) {
            if (pid.isEmpty() || !pid.chars().allMatch(Character::isDigit)) {
                continue;
            }
            String comm = sys.read("/proc/" + pid + "/comm");
            if (comm == null || !comm.startsWith("qemu")) {
                continue;
            }
            for (String fd : sys.list("/proc/" + pid + "/fd")) {
                if (device.equals(sys.canonical("/proc/" + pid + "/fd/" + fd))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A rejected controller is recorded with its controller ID: Linux reuses a controller name
     * once the controller is gone, and a new controller under that name (a path made later with
     * the right keys) is not the rejected one. An unreadable ID counts as the same controller.
     */
    private String controllerId(String ctrl) {
        String id = sys.read("/sys/class/nvme/" + ctrl + "/cntlid");
        return id == null ? "" : id.trim();
    }

    private boolean rejected(String ctrl) {
        String recorded = REJECTED.get(ctrl);
        if (recorded == null) {
            return false;
        }
        String now = controllerId(ctrl);
        return recorded.isEmpty() || now.isEmpty() || recorded.equals(now);
    }

    private void reject(String ctrl) {
        REJECTED.put(ctrl, controllerId(ctrl));
    }

    /** Forget rejected controllers that are gone, before a connect can reuse their names. */
    void forgetVanishedRejections() {
        List<String> listed = sys.listOrNull("/sys/class/nvme");
        if (listed != null) {
            REJECTED.keySet().retainAll(new java.util.HashSet<>(listed));
        }
    }

    /** The controller names of this host's Lightbits paths, any state. */
    java.util.Set<String> pathNames() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Path p : paths()) {
            out.add(p.controller);
        }
        return out;
    }

    /**
     * Two-way: disconnect the Lightbits paths made since {@code before} that do not hold the
     * current controller key (a data port the cluster advertised after the configuration was
     * written gets the host key only, so the host would not check the cluster on it). Paths that
     * were there before are left alone: they may carry volumes. Returns the controllers dropped.
     */
    List<String> dropNewPathsWithoutCtrlKey(java.util.Set<String> before, String currentCtrlKey) {
        List<String> dropped = new ArrayList<>();
        // a rejected controller that is gone is forgotten (the name can be reused); one that cannot
        // be read is kept, and an unreadable listing fails the check
        List<String> listed = sys.listOrNull("/sys/class/nvme");
        if (listed == null) {
            throw new CloudRuntimeException("Lightbits: could not list /sys/class/nvme to check this host's two-way paths");
        }
        REJECTED.keySet().retainAll(new java.util.HashSet<>(listed));
        for (Path p : paths()) {
            // a path an earlier connect rejected but could not disconnect stays rejected
            if (before.contains(p.controller) && !rejected(p.controller)) {
                continue;
            }
            String secret = sys.read("/sys/class/nvme/" + p.controller + "/dhchap_ctrl_secret");
            if (secret == null || !secret.trim().equals(currentCtrlKey)) {
                Result r = sys.run(30_000, "nvme", "disconnect", "-d", p.controller);
                if (!r.ok() || sys.exists("/sys/class/nvme/" + p.controller)) {
                    reject(p.controller);
                    throw new CloudRuntimeException("Lightbits: path " + p.controller + " (" + p.address + ") was made without this"
                            + " host's controller key (two-way) and could not be disconnected (exit " + r.exit + ": " + r.output
                            + "); disconnect it by hand (nvme disconnect -d " + p.controller + ")");
                }
                LOG.warn("Lightbits: dropped path {} ({}): it was made without this host's controller key, so the host would not"
                        + " check the cluster on it (two-way); the next connect makes it again", p.controller, p.address);
                REJECTED.remove(p.controller);
                dropped.add(p.controller);
            }
        }
        return dropped;
    }

    List<Path> livePaths() {
        List<Path> out = new ArrayList<>();
        for (Path p : paths()) {
            if (p.live()) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Warn when the host is not fully connected: a path that is not live, or fewer live paths
     * than discovery endpoints configured. Volumes keep working on the remaining paths, which
     * is exactly why nobody notices until the next node goes down.
     */
    void warnOnDegradedPaths(int expectedAtLeast) {
        List<Path> all = paths();
        List<Path> notLive = new ArrayList<>();
        for (Path p : all) {
            if (!p.live()) {
                notLive.add(p);
            }
        }
        int live = all.size() - notLive.size();
        if (!notLive.isEmpty() || live < expectedAtLeast) {
            LOG.warn("Lightbits: this host has {} live NVMe/TCP path(s) to the cluster, {} expected; paths not live: {};"
                    + " all paths: {}", live, Math.max(expectedAtLeast, all.size()), notLive, all);
        }
    }

    /**
     * Wait for the namespace of a volume and return its verified device path
     * ({@code /dev/disk/by-id/nvme-uuid.<uuid>}). Half way through the wait udev is settled
     * and the controllers are rescanned once, for the case where the kernel or udev missed the
     * event. A timeout is reported with what is known and the usual cause (the volume's ACL).
     */
    String waitForDevice(String volumeUuid, int waitSecs, String hostNqnHint) {
        String uuid = requireVolumeUuid(volumeUuid);
        String link = BY_ID_PREFIX + uuid;
        boolean rescanned = false;
        for (int i = 0; i <= waitSecs; i++) {
            if (sys.exists(link)) {
                verifyIdentity(uuid);
                return link;
            }
            if (!rescanned && i >= Math.max(1, waitSecs / 2)) {
                rescanned = true;
                rescan();
                if (sys.exists(link)) {
                    verifyIdentity(uuid);
                    return link;
                }
            }
            if (i < waitSecs) {
                sys.sleep(1000);
            }
        }
        List<Path> all = paths();
        throw new CloudRuntimeException("Lightbits: volume " + uuid + " did not appear on this host within " + waitSecs
                + "s. NVMe/TCP paths to the cluster: " + (all.isEmpty() ? "none" : all.toString())
                + (livePathCount(all) > 0
                    ? ". The host is connected, so the usual cause is the volume's ACL: it must contain this host's NQN"
                        + (hostNqnHint == null ? "" : " (" + hostNqnHint + ")") + "."
                    : ". The host has no live path to the cluster: check the data network."));
    }

    private static int livePathCount(List<Path> paths) {
        int n = 0;
        for (Path p : paths) {
            n += p.live() ? 1 : 0;
        }
        return n;
    }

    /**
     * Settle udev and rescan the namespaces of every live Lightbits controller: recovers a
     * namespace whose arrival the kernel or udev missed while the controller stayed connected.
     */
    void rescan() {
        sys.run(15_000, "udevadm", "settle", "--timeout=10");
        for (Path p : paths()) {
            if (p.live() && p.controller.matches("nvme[0-9]+")) {
                sys.run(15_000, "nvme", "ns-rescan", "/dev/" + p.controller);
            }
        }
        sys.run(15_000, "udevadm", "settle", "--timeout=10");
    }

    /**
     * The device behind the volume's by-id link must be a namespace of a Lightbits subsystem
     * whose own UUID is the volume's. Returns the kernel name (for example {@code nvme3n2}).
     * Anything else is refused: handing a guest the wrong disk is the worst outcome there is.
     */
    String verifyIdentity(String volumeUuid) {
        String uuid = requireVolumeUuid(volumeUuid);
        String link = BY_ID_PREFIX + uuid;
        String target = sys.canonical(link);
        if (target == null || !target.matches("/dev/nvme[0-9]+n[0-9]+")) {
            throw new CloudRuntimeException("Lightbits: " + link + " does not resolve to an NVMe namespace (it points to "
                    + target + "); refusing to use it for volume " + uuid);
        }
        String name = target.substring("/dev/".length());
        String actual = sys.read("/sys/block/" + name + "/uuid");
        if (actual == null || !actual.equalsIgnoreCase(uuid)) {
            throw new CloudRuntimeException("Lightbits: " + link + " points to " + target + " whose UUID is " + actual
                    + ", not " + uuid + " (stale device link); refusing to hand out the wrong disk");
        }
        String nqn = sys.read("/sys/block/" + name + "/device/subsysnqn");
        if (nqn == null || !nqn.startsWith(LIGHTBITS_NQN_PREFIX)) {
            throw new CloudRuntimeException("Lightbits: " + target + " belongs to subsystem " + nqn
                    + ", which is not a Lightbits cluster; refusing to use it for volume " + uuid);
        }
        return name;
    }

    /** Size in bytes of a verified volume device, from sysfs (512-byte sectors). */
    long sizeBytes(String kernelName) {
        String sectors = sys.read("/sys/block/" + kernelName + "/size");
        try {
            return sectors == null ? 0L : Long.parseLong(sectors) * 512L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    boolean deviceLinkExists(String volumeUuid) {
        return sys.exists(BY_ID_PREFIX + requireVolumeUuid(volumeUuid));
    }

    /** True once the volume's device link exists, waiting up to {@code millis} for it. */
    boolean waitForDeviceLink(String volumeUuid, long millis) {
        String link = BY_ID_PREFIX + requireVolumeUuid(volumeUuid);
        for (long waited = 0; ; waited += 250) {
            if (sys.exists(link)) {
                return true;
            }
            if (waited >= millis) {
                return false;
            }
            sys.sleep(250);
        }
    }

    /** This host's NQN, for error messages only. */
    String hostNqn() {
        return sys.read("/etc/nvme/hostnqn");
    }

    /** {@code qemu-img convert} of a source image onto a raw device, as an argument vector. */
    void convertToRaw(String sourcePath, String devicePath, boolean targetExists, long timeoutMillis) {
        if (sourcePath == null || sourcePath.isEmpty() || sourcePath.startsWith("-")) {
            throw new CloudRuntimeException("Lightbits: invalid source image path '" + sourcePath + "'");
        }
        List<String> argv = new ArrayList<>(Arrays.asList("qemu-img", "convert"));
        if (targetExists) {
            argv.add("-n");
        }
        argv.addAll(Arrays.asList("-O", "raw", sourcePath, devicePath));
        Result r = sys.run(timeoutMillis > 0 ? timeoutMillis : TimeUnit.HOURS.toMillis(3), argv.toArray(new String[0]));
        if (!r.ok()) {
            throw new CloudRuntimeException("Lightbits: qemu-img convert " + sourcePath + " -> " + devicePath
                    + " failed (exit " + r.exit + "): " + r.output);
        }
    }

    /** The real host. */
    static final int MAX_MESSAGE_OUTPUT = 2000;
    static final int MAX_DATA_OUTPUT = 1_000_000;

    static final class RealSys implements Sys {
        @Override
        public Result run(long timeoutMillis, String... argv) {
            Process p = null;
            try {
                File out = File.createTempFile("lightbits-cmd", ".out");
                try {
                    p = new ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(out).start();
                    if (!p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        p.destroyForcibly();
                        return new Result(124, argv[0] + " timed out after " + (timeoutMillis / 1000) + "s");
                    }
                    String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
                    // a failure's output goes into messages: keep it short; a success's output can be
                    // data (the discovery log page as JSON): keep it whole, within a memory bound
                    int limit = p.exitValue() == 0 ? MAX_DATA_OUTPUT : MAX_MESSAGE_OUTPUT;
                    return new Result(p.exitValue(), text.length() > limit ? text.substring(0, limit) : text);
                } finally {
                    Files.deleteIfExists(out.toPath());
                }
            } catch (IOException e) {
                return new Result(127, argv[0] + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (p != null) {
                    p.destroyForcibly();
                }
                return new Result(130, argv[0] + " interrupted");
            }
        }

        @Override
        public boolean exists(String path) {
            return new File(path).exists();
        }

        @Override
        public String read(String path) {
            try {
                return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).trim();
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public String canonical(String path) {
            try {
                return new File(path).getCanonicalPath();
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public List<String> list(String dir) {
            String[] names = new File(dir).list();
            return names == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(names));
        }

        @Override
        public List<String> listOrNull(String dir) {
            String[] names = new File(dir).list();
            return names == null ? null : new ArrayList<>(Arrays.asList(names));
        }

        @Override
        public void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void connectFabrics(String options, long timeoutMillis) {
            // the kernel makes the controller inside the write, which can block (a port that does
            // not answer): it runs on its own thread, and a timeout leaves it to finish there
            java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(() -> {
                try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(Paths.get("/dev/nvme-fabrics"),
                        java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)) {
                    ch.write(java.nio.ByteBuffer.wrap(options.getBytes(StandardCharsets.US_ASCII)));
                    ch.read(java.nio.ByteBuffer.allocate(256));   // "instance=N,cntlid=M"
                }
                return null;
            });
            Thread t = new Thread(task, "lightbits-nvme-connect");
            t.setDaemon(true);
            t.start();
            try {
                task.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                throw new CloudRuntimeException("timed out after " + (timeoutMillis / 1000) + "s");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause() == null ? e : e.getCause();
                throw new CloudRuntimeException(c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CloudRuntimeException("interrupted");
            }
        }
    }
}
