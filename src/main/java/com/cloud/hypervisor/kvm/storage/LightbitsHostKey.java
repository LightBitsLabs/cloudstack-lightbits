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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.exception.CloudRuntimeException;

/**
 * This host's DH-HMAC-CHAP key for NVMe in-band authentication with Lightbits clusters, and
 * the nvme-cli configuration file that hands it to {@code nvme connect-all --config}.
 *
 * <p>The key is made on first use and kept in {@code /etc/nvme/lightbits/dhchap-key}; the
 * configuration file next to it ({@code auth.json}) names this host's NQN, host ID and key.
 * The directory is 0700 and both files 0600, all owned by root; a key file owned by another
 * user is not reused. The key is made here, from
 * {@link SecureRandom}, in the format of {@code nvme gen-dhchap-key} (32 bytes, no
 * transformation), so it never passes through a command line or a command's output. It
 * leaves the host only to the management server, which registers it with the cluster.
 *
 * <p>To rotate the key, delete {@code dhchap-key} and reconnect the host in CloudStack: a new
 * key is made and registered. Paths connected with the old key keep it until they reconnect.
 */
public class LightbitsHostKey {
    private static final Logger LOG = LogManager.getLogger(LightbitsHostKey.class);
    private static final Object LOCK = new Object();
    /** A DH-HMAC-CHAP secret: transformation 00-03, base64 of 32, 48 or 64 key bytes and their CRC-32. */
    private static final Pattern KEY = Pattern.compile("DHHC-1:0[0-3]:([A-Za-z0-9+/]+={0,2}):");
    private static final Pattern HOST_NQN = Pattern.compile("nqn\\.[A-Za-z0-9:._-]{1,220}");
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Set<PosixFilePermission> DIR_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dir;
    private final Path nvmeDir;
    /** The owner the directory and files must have: root (tests use their own UID). */
    private final int ownerUid;
    /** "" for the host-wide (legacy) keys, "-<digest of a cluster's subsystem NQN>" for one cluster's keys. */
    private final String suffix;
    /** For a cluster's keys: the host-wide keys a missing one is first copied from; null otherwise. */
    private final LightbitsHostKey legacy;

    public LightbitsHostKey() {
        this(Path.of("/etc/nvme/lightbits"), Path.of("/etc/nvme"), 0);
    }

    LightbitsHostKey(Path dir, Path nvmeDir, int ownerUid) {
        this(dir, nvmeDir, ownerUid, "", null);
    }

    private LightbitsHostKey(Path dir, Path nvmeDir, int ownerUid, String suffix, LightbitsHostKey legacy) {
        this.dir = dir;
        this.nvmeDir = nvmeDir;
        this.ownerUid = ownerUid;
        this.suffix = suffix;
        this.legacy = legacy;
    }

    /**
     * This host's keys for one cluster, identified by its subsystem NQN: each cluster gets its own
     * host key and controller key, so a cluster that learns one host's keys (it holds them as
     * its trusted host's secrets) cannot use them against another cluster. Null or blank gives
     * the host-wide keys (management servers older than this version send no scope). A cluster's
     * key that does not exist yet is first copied from the host-wide one, so the paths a host
     * already has keep working; the keys separate at the cluster's next rotation.
     */
    public LightbitsHostKey forScope(String subsystemNqn) {
        if (subsystemNqn == null || subsystemNqn.isBlank()) {
            return this;
        }
        if (!HOST_NQN.matcher(subsystemNqn.trim()).matches()) {
            throw new CloudRuntimeException("Lightbits: not a subsystem NQN: " + subsystemNqn);
        }
        LightbitsHostKey base = legacy == null ? this : legacy;
        return new LightbitsHostKey(dir, nvmeDir, ownerUid, "-" + scopeId(subsystemNqn.trim()), base);
    }

    /** A short, file-name safe digest of a subsystem NQN. */
    static String scopeId(String subsystemNqn) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(subsystemNqn.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                b.append(String.format("%02x", d[i]));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Path keyFile() {
        return dir.resolve("dhchap-key" + suffix);
    }

    /** The controller key: the secret the cluster proves itself with, for two-way authentication. */
    Path ctrlKeyFile() {
        return dir.resolve("dhchap-ctrl-key" + suffix);
    }

    /** The two-way nvme-cli configuration of one pool (per-port keys from its discovery log page). */
    Path twoWayConfigFile(String poolUuid) {
        if (poolUuid == null || !UUID.matcher(poolUuid).matches()) {
            throw new CloudRuntimeException("Lightbits: not a pool UUID: " + poolUuid);
        }
        return dir.resolve("auth-" + poolUuid.toLowerCase() + ".json");
    }

    public Path configFile() {
        return dir.resolve("auth" + suffix + ".json");
    }

    /**
     * This host's key and its nvme-cli configuration file, both made if missing. Returns the
     * key. A stored key that is not a valid DH-HMAC-CHAP secret is replaced (and logged).
     */
    public String ensure() {
        synchronized (LOCK) {
            try {
                restrict();
                String key = readKey();
                if (key == null && legacy != null) {
                    key = seedFromLegacy(keyFile(), legacy.keyFile());
                }
                if (key == null) {
                    key = newKey(RANDOM);
                    writePrivate(keyFile(), key + "\n");
                    LOG.info("Lightbits: made this host's in-band authentication key ({})", keyFile());
                }
                String nqn = read(nvmeDir.resolve("hostnqn"));
                if (nqn == null || !HOST_NQN.matcher(nqn).matches()) {
                    throw new CloudRuntimeException("Lightbits: " + nvmeDir.resolve("hostnqn") + " is missing or holds no host NQN ("
                            + nqn + "); in-band authentication needs it");
                }
                String config = config(nqn, hostId(nqn, read(nvmeDir.resolve("hostid"))), key);
                if (!config.equals(read(configFile()) + "\n")) {
                    writePrivate(configFile(), config);
                }
                return key;
            } catch (IOException e) {
                throw new CloudRuntimeException("Lightbits: could not prepare this host's in-band authentication key in "
                        + dir + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Delete this host's keys and every configuration file that holds them (rotation): the next
     * {@link #ensure()} and {@link #ensureCtrlKey()} make new ones. The caller makes sure no path
     * still uses the old keys.
     */
    public void deleteKeys() {
        synchronized (LOCK) {
            try {
                java.util.List<Path> files = new java.util.ArrayList<>(java.util.List.of(keyFile(), ctrlKeyFile(), configFile()));
                if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    try (java.util.stream.Stream<Path> list = Files.list(dir)) {
                        list.filter(f -> f.getFileName().toString().matches("auth-.+\\.json")).forEach(files::add);
                    }
                }
                for (Path f : files) {
                    Files.deleteIfExists(f);
                }
                Files.deleteIfExists(rotationMarker());   // replaced: no longer possibly exposed
                if (legacy != null && !Files.exists(ownMarker(), LinkOption.NOFOLLOW_LINKS)) {
                    // from now on this cluster's keys are its own: never copied from the host-wide ones again
                    Path tmp = Files.createTempFile(dir, ".tmp-", "", PosixFilePermissions.asFileAttribute(FILE_MODE));
                    Files.move(tmp, ownMarker(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new CloudRuntimeException("Lightbits: could not delete this host's in-band authentication keys in " + dir
                        + ": " + e.getMessage(), e);
            }
        }
    }

    /** The configuration file for {@code nvme connect-all --config}, made if missing. */
    public Path ensureConfig() {
        ensure();
        return configFile();
    }

    /**
     * Before anything is read: the directory must not be a symbolic link and, if it exists, must
     * be owned by root; it is made 0700. If a key file (host or controller) or a configuration
     * file (the shared one or a pool's two-way one) is owned by another user or is a symbolic
     * link, all of them are removed, so new keys are made: another user may have read the old
     * ones (the configuration files hold them too). Otherwise existing files are made
     * 0600 (files from an earlier version, or copied or made by hand with a wider mode, are
     * root-only again). New files are made with these modes.
     */
    private void restrict() throws IOException {
        if (Files.isSymbolicLink(dir)) {
            throw new CloudRuntimeException("Lightbits: " + dir + " is a symbolic link; this host's in-band"
                    + " authentication key must be in a root-owned directory. Remove the link (a new key is made) and"
                    + " reconnect the host");
        }
        boolean wider = false;
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            int uid = uid(dir);
            if (uid != ownerUid) {
                throw new CloudRuntimeException("Lightbits: " + dir + " is owned by UID " + uid + ", not root; another user"
                        + " could read or replace this host's in-band authentication key. Remove the directory (a new key"
                        + " is made) and reconnect the host");
            }
            // The directory's own mode exposes no key: a file readable only by root stays so, and a
            // file another user replaced through a writable directory is no longer root's (below).
            // It is made root-only before its entries are listed and checked, so no other user can
            // add or replace an entry between the checks and their use.
            Files.setPosixFilePermissions(dir, DIR_MODE);
        }
        // every file here holds or marks keys: each cluster's keys and configurations, the
        // host-wide ones, the per-pool two-way configurations and the rotation marks
        java.util.List<Path> files = new java.util.ArrayList<>(java.util.List.of(keyFile(), configFile(), ctrlKeyFile()));
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            try (java.util.stream.Stream<Path> list = Files.list(dir)) {
                list.filter(f -> !files.contains(f) && !f.getFileName().toString().startsWith(".tmp-")).forEach(files::add);
            }
        }
        boolean untrusted = false;
        for (Path f : files) {
            untrusted |= Files.isSymbolicLink(f) || Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) && uid(f) != ownerUid;
        }
        java.util.List<String> keyed = new java.util.ArrayList<>();   // suffixes of the keys present
        if (!untrusted) {
            for (Path f : files) {
                if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    String name = f.getFileName().toString();
                    java.util.regex.Matcher m = KEY_FILE.matcher(name);
                    if (m.matches() && !keyed.contains(m.group(1))) {
                        keyed.add(m.group(1));
                    }
                    // keys and the configuration files holding them; the marks hold none
                    if (m.matches() || name.endsWith(".json")) {
                        wider |= groupOrOthers(Files.getPosixFilePermissions(f, LinkOption.NOFOLLOW_LINKS));
                    }
                }
            }
        }
        if (untrusted) {
            // the clusters that had keys or marks here: after the cleanup each makes its own new keys,
            // never copies of host-wide keys made again in the meantime
            // (only files that exist: a scope with none was never used and keeps its first seeding)
            java.util.Set<String> scopes = new java.util.HashSet<>();
            for (Path f : files) {
                java.util.regex.Matcher m = SCOPED_FILE.matcher(f.getFileName().toString());
                if (m.matches() && (Files.exists(f, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(f))) {
                    scopes.add(m.group(1));
                }
            }
            // The new marks are in place before anything is deleted (an existing mark, even a link,
            // is replaced by a root-owned file), so a cleanup cut short never leaves a used scope
            // without its mark.
            java.util.Set<Path> marks = new java.util.HashSet<>();
            for (String sfx : scopes) {
                Path mark = dir.resolve("own-keys" + sfx);
                Path tmp = Files.createTempFile(dir, ".tmp-", "", PosixFilePermissions.asFileAttribute(FILE_MODE));
                Files.move(tmp, mark, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                marks.add(mark);
            }
            for (Path f : files) {
                if (!marks.contains(f) && Files.deleteIfExists(f)) {
                    LOG.warn("Lightbits: removed {}: a key, configuration or mark file was not a file owned by root, so new"
                            + " in-band authentication keys are made (register them by reconnecting the host)", f);
                }
            }
        } else if (wider) {
            // Making the files root-only again does not take back a copy someone made while they
            // were readable. Replacing the keys at once could break this host's live paths, so the
            // keys stay until a rotation replaces them, and the host is reported until then. The
            // marks are written before the modes are tightened: if one cannot be written, the wide
            // modes stay as the evidence and the next attempt tries again.
            for (String sfx : keyed) {
                Path mark = dir.resolve("rotation-required" + sfx);
                if (!Files.exists(mark, LinkOption.NOFOLLOW_LINKS)) {
                    Path tmp = Files.createTempFile(dir, ".tmp-", "", PosixFilePermissions.asFileAttribute(FILE_MODE));
                    Files.move(tmp, mark, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (!keyed.isEmpty()) {
                LOG.warn("Lightbits: this host's in-band authentication key files were readable by other users than root;"
                        + " they are root-only again, but the keys may have been copied. Rotate them (INSTALL.md, \"Rotating"
                        + " the keys\")");
            }
        }
        if (!untrusted) {
            for (Path f : files) {
                if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    Files.setPosixFilePermissions(f, FILE_MODE);
                }
            }
        }
    }

    /** True when the group or other users have any permission (owner bits do not expose a key). */
    private static boolean groupOrOthers(Set<PosixFilePermission> perms) {
        for (PosixFilePermission p : perms) {
            if (p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")) {
                return true;
            }
        }
        return false;
    }

    /** A key file name: host or controller key, host-wide or one cluster's (group 1: the suffix). */
    /** A file of one cluster's keys: key, configuration, rotation or own-keys mark (group 1: the suffix). */
    private static final Pattern SCOPED_FILE = Pattern.compile(
            "(?:dhchap-(?:ctrl-)?key|auth|rotation-required|own-keys)(-[0-9a-f]{12})(?:\\.json)?");

    private static final Pattern KEY_FILE = Pattern.compile("dhchap-(?:ctrl-)?key(|-[0-9a-f]{12})");

    /** Marks a cluster whose keys were rotated at least once: they are no longer copied from the host-wide ones. */
    Path ownMarker() {
        return dir.resolve("own-keys" + suffix);
    }

    /** Marks keys that were readable by other users before {@link #restrict()} tightened them. */
    Path rotationMarker() {
        return dir.resolve("rotation-required" + suffix);
    }

    /**
     * A cluster's key that does not exist yet is the host-wide one, copied: the host's existing
     * paths hold it, so switching would break them. A copy of keys flagged as possibly exposed
     * is flagged too. Returns the key, or null when there is no valid host-wide one.
     */
    private String seedFromLegacy(Path own, Path legacyFile) throws IOException {
        if (Files.exists(ownMarker(), LinkOption.NOFOLLOW_LINKS)) {
            return null;   // rotated once: this cluster's keys are its own
        }
        String key = readKey(legacyFile);
        if (key == null) {
            return null;
        }
        // the exposure mark goes first: if it cannot be written, no copy exists and the next
        // attempt does both again
        if (Files.exists(legacy.rotationMarker(), LinkOption.NOFOLLOW_LINKS) && !keysPossiblyExposedUnlocked()) {
            Path tmp = Files.createTempFile(dir, ".tmp-", "", PosixFilePermissions.asFileAttribute(FILE_MODE));
            Files.move(tmp, rotationMarker(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        writePrivate(own, key + "\n");
        LOG.info("Lightbits: {} starts as a copy of {} (the host's existing paths hold it); it becomes its own at the next"
                + " rotation", own, legacyFile);
        return key;
    }

    /**
     * True when this host's keys were found readable by other users than root (and tightened)
     * and have not been replaced since: they may have been copied, so they should be rotated.
     */
    public boolean keysPossiblyExposed() {
        synchronized (LOCK) {
            return keysPossiblyExposedUnlocked();
        }
    }

    private boolean keysPossiblyExposedUnlocked() {
        return Files.exists(rotationMarker(), LinkOption.NOFOLLOW_LINKS);
    }

    private static int uid(Path p) throws IOException {
        return (Integer) Files.getAttribute(p, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    }

    private String readKey() throws IOException {
        return readKey(keyFile());
    }

    private String readKey(Path file) throws IOException {
        String key = read(file);
        if (key == null) {
            return null;
        }
        if (!valid(key)) {
            LOG.warn("Lightbits: {} does not hold a valid DH-HMAC-CHAP key; making a new one", file);
            return null;
        }
        return key;
    }

    /**
     * This host's controller key (made if missing), for two-way authentication: the cluster
     * proves itself to the host with it. One per host, used for every cluster, like the host
     * key. Kept beside the host key with the same rules; never logged.
     */
    public String ensureCtrlKey() {
        synchronized (LOCK) {
            try {
                restrict();
                String key = readKey(ctrlKeyFile());
                if (key == null && legacy != null) {
                    key = seedFromLegacy(ctrlKeyFile(), legacy.ctrlKeyFile());
                }
                if (key == null) {
                    key = newKey(RANDOM);
                    writePrivate(ctrlKeyFile(), key + "\n");
                    LOG.info("Lightbits: made this host's in-band authentication controller key ({})", ctrlKeyFile());
                }
                return key;
            } catch (IOException e) {
                throw new CloudRuntimeException("Lightbits: could not prepare this host's in-band authentication controller key in "
                        + dir + ": " + e.getMessage(), e);
            }
        }
    }

    /** One data port of a discovery log page: the subsystem NQN, address and service. */
    public static final class Port {
        final String subsystemNqn;
        final String address;
        final String service;

        public Port(String subsystemNqn, String address, String service) {
            this.subsystemNqn = subsystemNqn;
            this.address = address;
            this.service = service;
        }
    }

    private static final Pattern ADDRESS = Pattern.compile("[0-9A-Za-z.:%-]{1,64}");
    private static final Pattern SERVICE = Pattern.compile("[0-9]{1,5}");

    /**
     * The two-way nvme-cli configuration for one pool, written if it changed: the host entry
     * with the host key, and every data port of {@code ports} with the host key and the
     * controller key ({@code nvme connect-all} takes a controller key only per port). Ports with
     * an NQN, address or service that does not look like one are left out, so no value needs
     * escaping. Returns the file.
     */
    public Path ensureTwoWayConfig(String poolUuid, java.util.List<Port> ports) {
        String key = ensure();
        String ctrl = ensureCtrlKey();
        synchronized (LOCK) {
            try {
                String nqn = read(nvmeDir.resolve("hostnqn"));
                String hostId = hostId(nqn, read(nvmeDir.resolve("hostid")));
                java.util.Map<String, java.util.List<Port>> bySubsystem = new java.util.LinkedHashMap<>();
                for (Port p : ports) {
                    if (p.subsystemNqn != null && HOST_NQN.matcher(p.subsystemNqn).matches() && p.address != null
                            && ADDRESS.matcher(p.address).matches() && p.service != null && SERVICE.matcher(p.service).matches()) {
                        bySubsystem.computeIfAbsent(p.subsystemNqn, k -> new java.util.ArrayList<>()).add(p);
                    }
                }
                StringBuilder b = new StringBuilder("[{\"hostnqn\":\"").append(nqn).append("\",\"hostid\":\"").append(hostId)
                        .append("\",\"dhchap_key\":\"").append(key).append("\",\"subsystems\":[");
                boolean firstSub = true;
                for (java.util.Map.Entry<String, java.util.List<Port>> e : bySubsystem.entrySet()) {
                    b.append(firstSub ? "" : ",").append("{\"nqn\":\"").append(e.getKey()).append("\",\"ports\":[");
                    firstSub = false;
                    boolean firstPort = true;
                    for (Port p : e.getValue()) {
                        b.append(firstPort ? "" : ",").append("{\"transport\":\"tcp\",\"traddr\":\"").append(p.address)
                                .append("\",\"trsvcid\":\"").append(p.service).append("\",\"dhchap_key\":\"").append(key)
                                .append("\",\"dhchap_ctrl_key\":\"").append(ctrl).append("\"}");
                        firstPort = false;
                    }
                    b.append("]}");
                }
                String config = b.append("]}]\n").toString();
                Path file = twoWayConfigFile(poolUuid);
                if (!config.equals(read(file) + "\n")) {
                    writePrivate(file, config);
                }
                return file;
            } catch (IOException e) {
                throw new CloudRuntimeException("Lightbits: could not write this host's two-way in-band authentication"
                        + " configuration in " + dir + ": " + e.getMessage(), e);
            }
        }
    }

    /** True for a DH-HMAC-CHAP secret whose CRC-32 matches its key bytes. */
    static boolean valid(String key) {
        Matcher m = KEY.matcher(key == null ? "" : key);
        if (!m.matches()) {
            return false;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(m.group(1));
        } catch (IllegalArgumentException e) {
            return false;
        }
        int len = raw.length - 4;
        if (len != 32 && len != 48 && len != 64) {
            return false;
        }
        CRC32 crc = new CRC32();
        crc.update(raw, 0, len);
        long want = crc.getValue();
        long got = (raw[len] & 0xffL) | (raw[len + 1] & 0xffL) << 8 | (raw[len + 2] & 0xffL) << 16 | (raw[len + 3] & 0xffL) << 24;
        return want == got;
    }

    /** A new secret in the format of {@code nvme gen-dhchap-key}: 32 random bytes, CRC-32 appended little-endian. */
    static String newKey(SecureRandom random) {
        byte[] raw = new byte[36];
        random.nextBytes(raw);
        CRC32 crc = new CRC32();
        crc.update(raw, 0, 32);
        long c = crc.getValue();
        for (int i = 0; i < 4; i++) {
            raw[32 + i] = (byte) (c >>> (8 * i));
        }
        return "DHHC-1:00:" + Base64.getEncoder().encodeToString(raw) + ":";
    }

    /**
     * The host ID nvme-cli uses: {@code /etc/nvme/hostid}, or else the UUID of a UUID-based
     * host NQN. nvme-cli refuses a configuration file without one.
     */
    static String hostId(String hostNqn, String hostIdFile) {
        if (hostIdFile != null && UUID.matcher(hostIdFile).matches()) {
            return hostIdFile.toLowerCase();
        }
        int at = hostNqn.lastIndexOf(":uuid:");
        if (at >= 0 && UUID.matcher(hostNqn.substring(at + 6)).matches()) {
            return hostNqn.substring(at + 6).toLowerCase();
        }
        throw new CloudRuntimeException("Lightbits: this host has no /etc/nvme/hostid and its host NQN " + hostNqn
                + " has no UUID to take one from; write the host ID to /etc/nvme/hostid (nvme gen-hostnqn | sed"
                + " 's/.*uuid://' gives one) and reconnect the host");
    }

    /** The nvme-cli JSON configuration: one host entry with its key. Every value is validated, so no escaping is needed. */
    static String config(String hostNqn, String hostId, String key) {
        return "[{\"hostnqn\":\"" + hostNqn + "\",\"hostid\":\"" + hostId + "\",\"dhchap_key\":\"" + key + "\"}]\n";
    }

    private static String read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String s = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
        return s.isEmpty() ? null : s;
    }

    /** Write a root-only file: made 0600 in a 0700 directory before any content is written, then moved into place. */
    private void writePrivate(Path file, String content) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_MODE));
        }
        Files.setPosixFilePermissions(dir, DIR_MODE);
        Path tmp = Files.createTempFile(dir, ".tmp-", "", PosixFilePermissions.asFileAttribute(FILE_MODE));
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Lightbits NVMe/TCP paths on this host that do not hold the given (current) key: none, or an old one. */
    public static int pathsWithoutKey(String currentKey) {
        return new LightbitsNvmeHost().pathsWithoutKey(currentKey);
    }

    /** As {@link #pathsWithoutKey(String)}, for the paths of one subsystem (null: all). */
    public static int pathsWithoutKey(String currentKey, String subsystem) {
        return new LightbitsNvmeHost().pathsWithoutKey(currentKey, subsystem);
    }

    /** As {@link #pathsWithoutCtrlKey(String)}, for the paths of one subsystem (null: all). */
    public static int pathsWithoutCtrlKey(String currentCtrlKey, String subsystem) {
        return new LightbitsNvmeHost().pathsWithoutCtrlKey(currentCtrlKey, subsystem);
    }

    /** Lightbits NVMe/TCP paths on this host whose controller key is not the given (current) one: none, or an old one. */
    public static int pathsWithoutCtrlKey(String currentCtrlKey) {
        return new LightbitsNvmeHost().pathsWithoutCtrlKey(currentCtrlKey);
    }
}
