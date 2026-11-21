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
    private static final long CONNECT_TIMEOUT_MS = 60_000;
    private static final AtomicInteger ROTATION = new AtomicInteger();

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
        if (endpoints.isEmpty()) {
            throw new CloudRuntimeException("Lightbits: the storage pool has no NVMe discovery endpoint");
        }
        Result mod = sys.run(30_000, "modprobe", "nvme-tcp");
        if (!mod.ok()) {
            throw new CloudRuntimeException("Lightbits: 'modprobe nvme-tcp' failed (exit " + mod.exit + "): " + mod.output
                    + ". The host needs the nvme-tcp kernel module.");
        }
        List<String> failures = new ArrayList<>();
        int start = Math.floorMod(ROTATION.getAndIncrement(), endpoints.size());
        for (int i = 0; i < endpoints.size(); i++) {
            String endpoint = endpoints.get((start + i) % endpoints.size());
            int colon = endpoint.lastIndexOf(':');
            Result r = sys.run(endpointTimeoutMillis, "nvme", "connect-all", "-t", "tcp",
                    "-a", endpoint.substring(0, colon), "-s", endpoint.substring(colon + 1));
            if (r.ok()) {
                if (!failures.isEmpty()) {
                    LOG.warn("Lightbits: NVMe discovery succeeded via {} after failing via {}; check those endpoints",
                            endpoint, failures);
                }
                return true;
            }
            failures.add(endpoint + " (exit " + r.exit + (r.output.isEmpty() ? "" : ": " + r.output) + ")");
        }
        List<Path> live = livePaths();
        if (live.isEmpty()) {
            throw new CloudRuntimeException("Lightbits: could not connect this host to the cluster: 'nvme connect-all' failed"
                    + " for every discovery endpoint " + failures + " and the host has no live NVMe/TCP path to a"
                    + " Lightbits cluster. Check the data network and that the discovery service (port "
                    + DEFAULT_DISCOVERY_PORT + ") is reachable from this host.");
        }
        LOG.warn("Lightbits: 'nvme connect-all' failed for every discovery endpoint {}; continuing on the {} live"
                + " path(s) this host already has", failures, live.size());
        return false;
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
                    return new Result(p.exitValue(), text.length() > 2000 ? text.substring(0, 2000) : text);
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
        public void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
