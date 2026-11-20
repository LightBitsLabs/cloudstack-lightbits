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
package org.apache.cloudstack.storage.datastore.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Snapshot {
    @JsonProperty("UUID")
    private String uuid;
    private String name;
    private String state;
    private String sourceVolumeUUID;
    private String sourceVolumeName;
    private String size;
    private int replicaCount;
    /** Set by the client on a snapshot the current call created (not one it found through a 409). */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean createdByThisCall;

    public boolean isDeletedOrDeleting() {
        return "Deleted".equals(state) || "Deleting".equals(state);
    }

    public boolean isAvailable() {
        return "Available".equals(state);
    }

    public boolean isFailed() {
        return "Failed".equals(state);
    }

    /** Size of the source volume at the time the snapshot was taken, in bytes (0 if unknown). */
    public long getSizeBytes() {
        return size == null || size.isEmpty() ? 0L : Long.parseLong(size);
    }

    public boolean isCreatedByThisCall() { return createdByThisCall; }
    public void setCreatedByThisCall(boolean createdByThisCall) { this.createdByThisCall = createdByThisCall; }
    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getSourceVolumeUUID() { return sourceVolumeUUID; }
    public void setSourceVolumeUUID(String v) { this.sourceVolumeUUID = v; }
    public String getSourceVolumeName() { return sourceVolumeName; }
    public void setSourceVolumeName(String v) { this.sourceVolumeName = v; }
    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }
    public int getReplicaCount() { return replicaCount; }
    public void setReplicaCount(int replicaCount) { this.replicaCount = replicaCount; }
}
