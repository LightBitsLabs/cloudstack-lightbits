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

    public LightbitsHostKey() {
        this(Path.of("/etc/nvme/lightbits"), Path.of("/etc/nvme"), 0);
    }

    LightbitsHostKey(Path dir, Path nvmeDir, int ownerUid) {
        this.dir = dir;
        this.nvmeDir = nvmeDir;
        this.ownerUid = ownerUid;
    }

    Path keyFile() {
        return dir.resolve("dhchap-key");
    }

    public Path configFile() {
        return dir.resolve("auth.json");
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

    /** The configuration file for {@code nvme connect-all --config}, made if missing. */
    public Path ensureConfig() {
        ensure();
        return configFile();
    }

    /**
     * Before anything is read: the directory must not be a symbolic link and, if it exists, must
     * be owned by root; it is made 0700. If the key or the configuration file is owned by another
     * user or is a symbolic link, both are removed, so a new key is made: another user may have
     * read the old one (the configuration file holds it too). Otherwise existing files are made
     * 0600 (files from an earlier version, or copied or made by hand with a wider mode, are
     * root-only again). New files are made with these modes.
     */
    private void restrict() throws IOException {
        if (Files.isSymbolicLink(dir)) {
            throw new CloudRuntimeException("Lightbits: " + dir + " is a symbolic link; this host's in-band"
                    + " authentication key must be in a root-owned directory. Remove the link (a new key is made) and"
                    + " reconnect the host");
        }
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            int uid = uid(dir);
            if (uid != ownerUid) {
                throw new CloudRuntimeException("Lightbits: " + dir + " is owned by UID " + uid + ", not root; another user"
                        + " could read or replace this host's in-band authentication key. Remove the directory (a new key"
                        + " is made) and reconnect the host");
            }
            Files.setPosixFilePermissions(dir, DIR_MODE);
        }
        Path[] files = {keyFile(), configFile()};
        boolean untrusted = false;
        for (Path f : files) {
            untrusted |= Files.isSymbolicLink(f) || Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) && uid(f) != ownerUid;
        }
        for (Path f : files) {
            if (untrusted) {
                if (Files.deleteIfExists(f)) {
                    LOG.warn("Lightbits: removed {}: the key or configuration file was not a file owned by root, so a new"
                            + " in-band authentication key is made (register it by reconnecting the host)", f);
                }
            } else if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(f, FILE_MODE);
            }
        }
    }

    private static int uid(Path p) throws IOException {
        return (Integer) Files.getAttribute(p, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    }

    private String readKey() throws IOException {
        String key = read(keyFile());
        if (key == null) {
            return null;
        }
        if (!valid(key)) {
            LOG.warn("Lightbits: {} does not hold a valid DH-HMAC-CHAP key; making a new one", keyFile());
            return null;
        }
        return key;
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
}
