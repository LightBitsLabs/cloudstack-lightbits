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
 * Supplied as the {@code secretkey} detail ({@code details[0].secretkey=<JWT>}), at
 * registration and again whenever it is replaced with {@code updateStoragePool}. The name is
 * not cosmetic: CloudStack removes parameters named password, secretkey or accesskey from its
 * API and management logs, and writes any other parameter, such as the {@code jwt} name earlier
 * versions used, to those logs in clear text. {@code jwt} is still accepted.
 *
 * Stored encrypted, chunked across {@code jwt}, {@code jwt.0}, {@code jwt.1} ... because the
 * value does not fit one detail row.
 *
 * Replaced by {@code updateStoragePool}: CloudStack writes the supplied details straight into
 * {@code storage_pool_details} without calling the plugin, so a new token arrives there in
 * clear text, and a detail value holds 255 characters at most, less than a token. The new
 * token is therefore supplied in numbered pieces of up to 250 characters:
 * {@code details[0].1secretkey=<first piece>}, {@code details[1].2secretkey=<second>} and so
 * on (names ending in "secretkey" are masked in CloudStack's logs). {@link #load} notices the
 * pieces on the next use of the pool (at the latest the next capacity poll), joins, encrypts
 * and stores the token, and removes the clear-text rows.
 */
public final class LightbitsPoolSecret {
    private static final Logger LOG = LogManager.getLogger(LightbitsPoolSecret.class);

    /** Detail name for supplying the token; masked by CloudStack in its logs. */
    public static final String KEY_TOKEN_INPUT = "secretkey";
    private static final String KEY_STORED = LightbitsPoolConfig.KEY_JWT;
    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern CHUNK_COUNT = Pattern.compile("[0-9]{1,3}");
    /** A piece of a replacement token: 1secretkey, 2secretkey, ... */
    private static final Pattern PIECE = Pattern.compile("([0-9]{1,2})" + KEY_TOKEN_INPUT);
    public static final int PIECE_LENGTH = 250;
    static final String HOW_TO_SET = "updateStoragePool with the token split into pieces of up to " + PIECE_LENGTH
            + " characters: details[0].1" + KEY_TOKEN_INPUT + "=<first piece> details[1].2" + KEY_TOKEN_INPUT
            + "=<second piece> ... (see docs/jwt-rotation.md)";
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

    /** The validated configuration of a pool, with its token; absorbs a replaced token first. */
    public static LightbitsPoolConfig load(StoragePoolDetailsDao dao, long poolId) {
        return load(dao, poolId, DBEncryptionUtil::encrypt, DBEncryptionUtil::decrypt);
    }

    static LightbitsPoolConfig load(StoragePoolDetailsDao dao, long poolId, UnaryOperator<String> encrypt,
                                    UnaryOperator<String> decrypt) {
        Map<String, String> details = dao.listDetailsKeyPairs(poolId);
        if (replacement(details) != null) {
            synchronized (LOCK) {
                details = dao.listDetailsKeyPairs(poolId);
                String fresh = replacement(details);
                if (fresh != null) {
                    absorb(dao, poolId, details, fresh, encrypt);
                    details = dao.listDetailsKeyPairs(poolId);
                }
            }
        }
        String stored = LightbitsPoolConfig.readChunked(details, KEY_STORED);
        if (stored == null) {
            throw new IllegalArgumentException("Lightbits pool " + poolId + " has no API token stored; set one with "
                    + HOW_TO_SET);
        }
        return LightbitsPoolConfig.fromDetails(details, decrypt.apply(stored));
    }

    /** A token that updateStoragePool left in the details in clear text, or null. */
    private static String replacement(Map<String, String> details) {
        java.util.TreeMap<Integer, String> pieces = new java.util.TreeMap<>();
        for (Map.Entry<String, String> e : details.entrySet()) {
            java.util.regex.Matcher m = PIECE.matcher(e.getKey());
            if (m.matches() && e.getValue() != null) {
                pieces.put(Integer.parseInt(m.group(1)), e.getValue().trim());
            }
        }
        if (!pieces.isEmpty()) {
            return String.join("", pieces.values());
        }
        // a whole token in one detail: only a token short enough for the column arrives intact
        String fresh = details.get(KEY_TOKEN_INPUT);
        if (fresh != null && !fresh.isBlank()) {
            return fresh.trim();
        }
        String counter = details.get(KEY_STORED);
        if (counter != null && !counter.isBlank() && !CHUNK_COUNT.matcher(counter.trim()).matches()) {
            return counter.trim();
        }
        return null;
    }

    private static boolean isInputKey(String key) {
        return key.equals(KEY_TOKEN_INPUT) || PIECE.matcher(key).matches();
    }

    private static void absorb(StoragePoolDetailsDao dao, long poolId, Map<String, String> details, String token,
                               UnaryOperator<String> encrypt) {
        boolean viaInputKeys = false;
        List<String> inputKeys = new ArrayList<>();
        for (String key : details.keySet()) {
            if (isInputKey(key)) {
                inputKeys.add(key);
                viaInputKeys = true;
            }
        }
        if (!JWT.matcher(token).matches()) {
            // Do not keep an unusable clear-text value around. The stored token is untouched
            // when the value came in under the input names, so the pool keeps working.
            for (String key : inputKeys) {
                dao.removeDetail(poolId, key);
            }
            throw new IllegalArgumentException("the value set as the token of Lightbits pool " + poolId + " is not a JWT"
                    + " (three dot-separated parts); a token passed as one detail is cut at 255 characters. Set it with "
                    + HOW_TO_SET);
        }
        List<String> old = new ArrayList<>(inputKeys);
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
        LOG.info("Lightbits: the API token of pool {} was replaced and stored encrypted", poolId);
        if (!viaInputKeys) {
            LOG.warn("Lightbits: the new token of pool {} was supplied as the 'jwt' detail, which CloudStack writes to its"
                    + " logs in clear text; treat this token as exposed", poolId);
        }
    }
}
