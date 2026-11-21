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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.crypt.DBEncryptionUtil;

/**
 * The pool's API token: how it is supplied, stored and replaced.
 *
 * Supplied at registration as the {@code secretkey} detail ({@code details[0].secretkey=<JWT>}).
 * The name is not cosmetic: CloudStack removes parameters named password, secretkey or
 * accesskey from its API and management logs, and writes any other parameter, such as the
 * {@code jwt} name earlier versions used, to those logs in clear text. {@code jwt} is still
 * accepted, with a warning.
 *
 * Stored encrypted, chunked across {@code jwt}, {@code jwt.0}, {@code jwt.1} ... because the
 * value does not fit one detail row.
 *
 * Replaced from a file. {@code updateStoragePool} cannot carry a token: CloudStack writes the
 * supplied details straight into {@code storage_pool_details} (255 characters per value, less
 * than a token, and in clear text) without calling the plugin, and it logs the response, which
 * echoes those details. So the operator writes the new token to a file on the management
 * server and sets {@code details[0].tokenFile=<path>}. {@link #load} notices the pointer on the
 * next use of the pool (at the latest the next capacity poll), reads the file, stores the token
 * encrypted and removes the pointer. The secret never passes through the API.
 */
public final class LightbitsPoolSecret {
    private static final Logger LOG = LogManager.getLogger(LightbitsPoolSecret.class);

    /** Detail name for supplying the token at registration; masked by CloudStack in its logs. */
    public static final String KEY_TOKEN_INPUT = "secretkey";
    /** Detail naming a file on the management server that holds a replacement token. */
    public static final String KEY_TOKEN_FILE = "tokenFile";
    /** Token files are only read from here: the management server's own configuration directory. */
    public static final String TOKEN_FILE_ROOT = "/etc/cloudstack/management";
    private static final long MAX_TOKEN_FILE_BYTES = 16 * 1024;
    private static final String KEY_STORED = LightbitsPoolConfig.KEY_JWT;
    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern CHUNK_COUNT = Pattern.compile("[0-9]{1,3}");
    static final String HOW_TO_SET = "write the token to a file under " + TOKEN_FILE_ROOT + " on the management server,"
            + " readable only by the cloud user, and run updateStoragePool details[0]." + KEY_TOKEN_FILE
            + "=<path> (see docs/jwt-rotation.md)";
    private static final Object LOCK = new Object();

    private LightbitsPoolSecret() {
    }

    /** The token from the details of a registration request, or null. */
    public static String tokenFromInput(Map<String, String> details) {
        if (details == null) {
            return null;
        }
        String token = details.get(KEY_TOKEN_INPUT);
        if (token == null || token.isBlank()) {
            token = details.get(KEY_STORED);
            if (token != null && !token.isBlank()) {
                LOG.warn("Lightbits: the pool token was supplied as the 'jwt' detail, which CloudStack writes to its"
                        + " API and management logs in clear text. Supply it as details[0].{} instead, and treat this"
                        + " token as exposed to anyone who can read those logs.", KEY_TOKEN_INPUT);
            }
        }
        return token == null || token.isBlank() ? null : token.trim();
    }

    /** The validated configuration of a pool, with its token; takes in a replacement token first. */
    public static LightbitsPoolConfig load(StoragePoolDetailsDao dao, long poolId) {
        return load(dao, poolId, DBEncryptionUtil::encrypt, DBEncryptionUtil::decrypt,
                path -> readTokenFile(path, TOKEN_FILE_ROOT));
    }

    static LightbitsPoolConfig load(StoragePoolDetailsDao dao, long poolId, UnaryOperator<String> encrypt,
                                    UnaryOperator<String> decrypt, UnaryOperator<String> readFile) {
        Map<String, String> details = dao.listDetailsKeyPairs(poolId);
        if (hasReplacement(details)) {
            synchronized (LOCK) {
                details = dao.listDetailsKeyPairs(poolId);
                if (hasReplacement(details)) {
                    replace(dao, poolId, details, encrypt, readFile);
                    details = dao.listDetailsKeyPairs(poolId);
                }
            }
        }
        String stored = LightbitsPoolConfig.readChunked(details, KEY_STORED);
        if (stored == null) {
            throw new IllegalArgumentException("Lightbits pool " + poolId + " has no API token stored. To set one, "
                    + HOW_TO_SET);
        }
        return LightbitsPoolConfig.fromDetails(details, decrypt.apply(stored));
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    /** True when the chunk counter was overwritten by a value passed as details[0].jwt. */
    private static boolean counterOverwritten(Map<String, String> details) {
        String counter = details.get(KEY_STORED);
        return notBlank(counter) && !CHUNK_COUNT.matcher(counter.trim()).matches();
    }

    private static boolean hasReplacement(Map<String, String> details) {
        return notBlank(details.get(KEY_TOKEN_FILE)) || notBlank(details.get(KEY_TOKEN_INPUT)) || counterOverwritten(details);
    }

    /**
     * Take in what updateStoragePool left in the details. Whatever happens, no clear-text
     * token row and no pointer survives this call, so one bad update fails one operation with
     * its reason instead of every operation from then on.
     */
    private static void replace(StoragePoolDetailsDao dao, long poolId, Map<String, String> details,
                                UnaryOperator<String> encrypt, UnaryOperator<String> readFile) {
        String file = details.get(KEY_TOKEN_FILE);
        dao.removeDetail(poolId, KEY_TOKEN_FILE);
        dao.removeDetail(poolId, KEY_TOKEN_INPUT);
        if (counterOverwritten(details)) {
            restoreCounter(dao, poolId, details);   // first, so that no failure below leaves it overwritten
        }

        if (!notBlank(file)) {
            // A token passed through the API (details[0].secretkey, or details[0].jwt, which
            // overwrites the chunk counter) is in CloudStack's logs and, beyond 255 characters, cut.
            // A cut token can still look like a JWT, so none is taken in: the previous token stays.
            LOG.warn("Lightbits: a token for pool {} was passed through updateStoragePool and was rejected; CloudStack"
                    + " logs such a value, so treat it as exposed. {}", poolId, HOW_TO_SET);
            throw new IllegalArgumentException("a token passed through updateStoragePool is not taken in for Lightbits"
                    + " pool " + poolId + ": CloudStack logs it and cuts it at 255 characters. The previous token stays"
                    + " in use. To replace it, " + HOW_TO_SET);
        }
        String token = readFile.apply(file.trim());   // throws with the reason; the stored token is untouched
        if (token == null || !JWT.matcher(token).matches()) {
            throw new IllegalArgumentException("the token file of Lightbits pool " + poolId + " does not hold a JWT"
                    + " (three dot-separated parts). The previous token stays in use. To replace it, " + HOW_TO_SET);
        }
        List<String> old = new ArrayList<>();
        for (String key : details.keySet()) {
            if (key.equals(KEY_STORED) || key.startsWith(KEY_STORED + ".")) {
                old.add(key);
            }
        }
        java.util.HashMap<String, String> chunks = new java.util.HashMap<>();
        LightbitsPoolConfig.putChunked(chunks, KEY_STORED, encrypt.apply(token));
        for (String key : old) {
            dao.removeDetail(poolId, key);
        }
        for (Map.Entry<String, String> e : chunks.entrySet()) {
            dao.addDetail(poolId, e.getKey(), e.getValue(), false);
        }
        LOG.info("Lightbits: the API token of pool {} was replaced and stored encrypted{}", poolId,
                notBlank(file) ? "; delete the token file " + file.trim() : "");
    }

    /** Put back the chunk counter that a value passed as details[0].jwt overwrote. */
    private static void restoreCounter(StoragePoolDetailsDao dao, long poolId, Map<String, String> details) {
        int n = 0;
        while (details.containsKey(KEY_STORED + "." + n)) {
            n++;
        }
        dao.removeDetail(poolId, KEY_STORED);
        if (n > 0) {
            dao.addDetail(poolId, KEY_STORED, String.valueOf(n), false);
        }
    }

    /**
     * Read a replacement token from a file on the management server. Only a small regular file
     * below {@code root} (after resolving links) is read, and its content is never part of an
     * error message, so the setting cannot be used to read other files through the API.
     */
    static String readTokenFile(String path, String root) {
        try {
            java.nio.file.Path real = java.nio.file.Paths.get(path).toRealPath();
            java.nio.file.Path allowed = java.nio.file.Paths.get(root).toRealPath();
            if (!real.startsWith(allowed) || real.equals(allowed)) {
                throw new IllegalArgumentException("the token file must be below " + root + " on the management server");
            }
            if (!java.nio.file.Files.isRegularFile(real) || java.nio.file.Files.size(real) > MAX_TOKEN_FILE_BYTES) {
                throw new IllegalArgumentException("the token file " + path + " is not a regular file of at most "
                        + MAX_TOKEN_FILE_BYTES + " bytes");
            }
            return new String(java.nio.file.Files.readAllBytes(real), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("the token file " + path + " cannot be read by the management server ("
                    + e.getClass().getSimpleName() + "). The previous token stays in use. To replace it, " + HOW_TO_SET);
        }
    }
}
