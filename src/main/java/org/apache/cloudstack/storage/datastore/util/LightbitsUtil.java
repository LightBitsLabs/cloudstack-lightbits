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
     * Name of the Lightbits volume backing a CloudStack volume.
     *
     * Format: {@code cs-<environment>-<cloudStackVolumeUuid>}. The environment suffix
     * identifies the owning CloudStack when multiple deployments share one Lightbits
     * cluster — avoiding collisions and making ownership obvious in `lbcli`/the cluster UI.
     */
    public static String volumeName(String environment, String cloudStackVolumeUuid) {
        return "cs-" + environment + "-" + cloudStackVolumeUuid;
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
}
