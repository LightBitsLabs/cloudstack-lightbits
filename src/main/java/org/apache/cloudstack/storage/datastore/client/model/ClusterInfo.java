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
public class ClusterInfo {
    @JsonProperty("UUID")
    private String uuid;
    private String subsystemNQN;
    private Health health;
    private Statistics statistics;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Health {
        private String state;
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Statistics {
        private String effectivePhysicalStorage;
        private String freePhysicalStorage;
        private String physicalUsedStorage;

        public long effectiveBytes() { return parse(effectivePhysicalStorage); }
        public long freeBytes() { return parse(freePhysicalStorage); }
        public long usedBytes() { return parse(physicalUsedStorage); }

        private static long parse(String v) { return v == null ? 0L : Long.parseLong(v); }

        public String getEffectivePhysicalStorage() { return effectivePhysicalStorage; }
        public void setEffectivePhysicalStorage(String v) { this.effectivePhysicalStorage = v; }
        public String getFreePhysicalStorage() { return freePhysicalStorage; }
        public void setFreePhysicalStorage(String v) { this.freePhysicalStorage = v; }
        public String getPhysicalUsedStorage() { return physicalUsedStorage; }
        public void setPhysicalUsedStorage(String v) { this.physicalUsedStorage = v; }
    }

    public boolean isHealthy() {
        return health != null && "OK".equals(health.getState());
    }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public String getSubsystemNQN() { return subsystemNQN; }
    public void setSubsystemNQN(String subsystemNQN) { this.subsystemNQN = subsystemNQN; }
    public Health getHealth() { return health; }
    public void setHealth(Health health) { this.health = health; }
    public Statistics getStatistics() { return statistics; }
    public void setStatistics(Statistics statistics) { this.statistics = statistics; }
}
