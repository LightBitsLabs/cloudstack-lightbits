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
import com.cloud.storage.Storage.StoragePoolType;
public final class LightbitsUtil {
    private LightbitsUtil() { }
    public static final String PROVIDER_NAME = "Lightbits";
    /**
     * v1 reuses an existing StoragePoolType because the enum is closed and cannot be
     * extended by an out-of-tree plugin on an unmodified CloudStack. IscsiLUN is chosen
     * because it is a block-storage type with NO built-in KVM StorageAdaptor claiming it,
     * so the Lightbits agent adaptor (M2) can register for it without collision.
     *
     * Long term: an upstream PR adds a first-class `Lightbits` enum value; switch this
     * constant to it once it ships in a CloudStack release. See internal decision log (Q3).
     */
    public static final StoragePoolType POOL_TYPE = StoragePoolType.IscsiLUN;

    /**
     * Per-pool name prefix: {@code cs-<environment>-p<poolId>-}.
     *
     * The numeric CloudStack pool id makes the prefix unique to ONE pool even when several
     * pools share the same Lightbits project AND environment (e.g. a 2x-replica pool and a
     * 3x-replica pool, or compression-on vs compression-off, all in one project). This is
     * what lets the pool-delete cleanup sweep by prefix safely: the prefix can only ever
     * match volumes this pool created, so removing one pool never touches a sibling pool's
     * volumes.
     *
     * environment still identifies the owning CloudStack when multiple deployments share a
     * cluster; the pool id disambiguates pools within one deployment.
     */
    public static String poolPrefix(String environment, long poolId) {
        return "cs-" + environment + "-p" + poolId + "-";
    }

    /** What follows the pool prefix in an object name: the CloudStack uuid, and "-src" on a clone's source snapshot. */
    public static final String OBJECT_SUFFIX_REGEX =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(-src)?";

    /**
     * True when {@code name} is an object of this pool exactly: the pool prefix followed by a
     * CloudStack uuid (and "-src" for a clone's source snapshot). A plain prefix match is not
     * enough: environment {@code cs1}, pool 7 has the prefix {@code cs-cs1-p7-}, which is also
     * the start of every name of a deployment whose environment is {@code cs1-p7}.
     */
    public static boolean isPoolObject(String environment, long poolId, String name) {
        return name != null && java.util.regex.Pattern.matches(
                java.util.regex.Pattern.quote(poolPrefix(environment, poolId)) + OBJECT_SUFFIX_REGEX, name);
    }

    /**
     * Name of the Lightbits volume/snapshot backing a CloudStack object.
     *
     * Format: {@code cs-<environment>-p<poolId>-<cloudStackUuid>}. Unique per pool (see
     * {@link #poolPrefix}); ownership is obvious in {@code lbcli}/the cluster UI.
     */
    public static String volumeName(String environment, long poolId, String cloudStackUuid) {
        return poolPrefix(environment, poolId) + cloudStackUuid;
    }
    /**
     * Parse the {@code lightbitsReplicas} offering detail: an integer 1..3. A malformed or
     * out-of-range value fails the volume creation with a pointed message rather than being
     * silently ignored — the operator explicitly asked for a replica factor.
     */
    public static int parseReplicasDetail(String value) {
        int replicas;
        try {
            replicas = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "offering detail lightbitsReplicas must be an integer 1..3 (got '" + value + "')");
        }
        if (replicas < 1 || replicas > 3) {
            throw new IllegalArgumentException(
                    "offering detail lightbitsReplicas must be between 1 and 3 (got " + replicas + ")");
        }
        return replicas;
    }

    /**
     * Parse a true/false offering detail. Anything else fails the volume creation: the operator
     * explicitly asked for something, and a typo must not silently mean "false".
     */
    public static boolean parseBooleanDetail(String key, String value) {
        String v = value == null ? "" : value.trim();
        if (v.equalsIgnoreCase("true")) {
            return true;
        }
        if (v.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("offering detail " + key + " must be true or false (got '" + value + "')");
    }

    /** The cluster accepts at most 25 failure domains of up to 100 characters each in a placement restriction. */
    static final int MAX_FAILURE_DOMAINS = 25;
    static final int MAX_FAILURE_DOMAIN_LENGTH = 100;

    /**
     * Parse the {@code lightbitsPlacement} offering detail: comma-separated failure-domain names.
     * Empty entries are dropped and duplicates collapse; an empty list, too many names or an
     * over-long name fails the volume creation with the cluster's limits in the message.
     */
    public static java.util.List<String> parsePlacementDetail(String value) {
        java.util.LinkedHashSet<String> fds = new java.util.LinkedHashSet<>();
        for (String part : (value == null ? "" : value).split(",")) {
            String fd = part.trim();
            if (fd.isEmpty()) {
                continue;
            }
            if (fd.length() > MAX_FAILURE_DOMAIN_LENGTH) {
                throw new IllegalArgumentException("offering detail lightbitsPlacement: failure domain '" + fd
                        + "' is longer than " + MAX_FAILURE_DOMAIN_LENGTH + " characters");
            }
            fds.add(fd);
        }
        if (fds.isEmpty()) {
            throw new IllegalArgumentException(
                    "offering detail lightbitsPlacement must list at least one failure domain (got '" + value + "')");
        }
        if (fds.size() > MAX_FAILURE_DOMAINS) {
            throw new IllegalArgumentException("offering detail lightbitsPlacement lists " + fds.size()
                    + " failure domains; the cluster accepts at most " + MAX_FAILURE_DOMAINS);
        }
        return java.util.List.copyOf(fds);
    }

    /**
     * The QoS policy for a requested max IOPS: among IOPS policies whose read and write
     * limits are both set, the one with the lowest limit that still gives at least
     * {@code maxIops} in each direction (ties by name). Unlimited policies never match: a max
     * IOPS asks for a limit. Null when none fits.
     */
    public static String policyForIops(java.util.List<org.apache.cloudstack.storage.datastore.client.LightbitsApiClient.IopsPolicy> policies,
                                       long maxIops) {
        org.apache.cloudstack.storage.datastore.client.LightbitsApiClient.IopsPolicy best = null;
        for (org.apache.cloudstack.storage.datastore.client.LightbitsApiClient.IopsPolicy p : policies) {
            if (p.readIops <= 0 || p.writeIops <= 0) {
                continue;
            }
            long limit = Math.min(p.readIops, p.writeIops);
            if (limit < maxIops) {
                continue;
            }
            long bestLimit = best == null ? Long.MAX_VALUE : Math.min(best.readIops, best.writeIops);
            if (limit < bestLimit || (limit == bestLimit && p.name.compareTo(best.name) < 0)) {
                best = p;
            }
        }
        return best == null ? null : best.name;
    }
}
