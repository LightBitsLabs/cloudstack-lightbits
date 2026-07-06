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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Volume {
    @JsonProperty("UUID")
    private String uuid;
    private String name;
    private String state;
    private String size;
    private int replicaCount;
    private String projectName;
    private Acl acl;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Acl {
        private List<String> values;

        public List<String> getValues() { return values; }
        public void setValues(List<String> values) { this.values = values; }
    }

    /** Volume is gone or on its way out — list results must filter these (trial finding F3). */
    public boolean isDeletedOrDeleting() {
        return "Deleted".equals(state) || "Deleting".equals(state);
    }

    public boolean isAvailable() {
        return "Available".equals(state);
    }

    public long getSizeBytes() {
        return size == null ? 0L : Long.parseLong(size);
    }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }
    public int getReplicaCount() { return replicaCount; }
    public void setReplicaCount(int replicaCount) { this.replicaCount = replicaCount; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public Acl getAcl() { return acl; }
    public void setAcl(Acl acl) { this.acl = acl; }
}
