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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional fields of a volume create request beyond size, replicas, ACL, compression and QoS.
 *
 * <ul>
 * <li>labels: key/value pairs shown on the cluster (who owns the volume in CloudStack).</li>
 * <li>failureDomains: placement affinity. The cluster places a single-replica volume in one of
 * these failure domains, and the primary replica of a multi-replica volume (keys FD and
 * PrimaryFD of placementRestrictions).</li>
 * <li>unencrypted: create the volume without encryption on a cluster in hybrid encryption mode.</li>
 * </ul>
 */
public final class VolumeExtras {

    public static final VolumeExtras NONE = new VolumeExtras(Map.of(), List.of(), false);

    /** The cluster allows at most 16 labels per volume. */
    public static final int MAX_LABELS = 16;

    private final Map<String, String> labels;
    private final List<String> failureDomains;
    private final boolean unencrypted;

    public VolumeExtras(Map<String, String> labels, List<String> failureDomains, boolean unencrypted) {
        if (labels != null && labels.size() > MAX_LABELS) {
            throw new IllegalArgumentException("at most " + MAX_LABELS + " labels per volume (got " + labels.size() + ")");
        }
        this.labels = labels == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(labels));
        this.failureDomains = failureDomains == null ? List.of() : List.copyOf(failureDomains);
        this.unencrypted = unencrypted;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public List<String> getFailureDomains() {
        return failureDomains;
    }

    public boolean isUnencrypted() {
        return unencrypted;
    }

    /** The same labels, without placement or encryption choices (what a clone may carry). */
    public VolumeExtras labelsOnly() {
        return new VolumeExtras(labels, List.of(), false);
    }
}
