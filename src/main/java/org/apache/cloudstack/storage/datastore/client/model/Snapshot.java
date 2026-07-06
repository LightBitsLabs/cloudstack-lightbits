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

    public boolean isDeletedOrDeleting() {
        return "Deleted".equals(state) || "Deleting".equals(state);
    }

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
}
