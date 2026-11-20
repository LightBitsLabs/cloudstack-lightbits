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
package org.apache.cloudstack.storage.datastore.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreCapabilities;
import org.junit.jupiter.api.Test;

class LightbitsPrimaryDataStoreDriverTest {

    /**
     * Every snapshot operation the driver implements must be advertised: CloudStack picks the
     * snapshot strategy by capability, and an unadvertised one fails in the core ("supported
     * only for KVM hypervisor") without the driver being called.
     */
    @Test
    void advertisesEveryImplementedSnapshotCapability() {
        Map<String, String> caps = new LightbitsPrimaryDataStoreDriver().getCapabilities();
        for (DataStoreCapabilities c : new DataStoreCapabilities[] {
                DataStoreCapabilities.STORAGE_SYSTEM_SNAPSHOT,
                DataStoreCapabilities.CAN_REVERT_VOLUME_TO_SNAPSHOT,
                DataStoreCapabilities.CAN_CREATE_VOLUME_FROM_SNAPSHOT,
                DataStoreCapabilities.CAN_CREATE_VOLUME_FROM_VOLUME}) {
            assertEquals("true", caps.get(c.toString()), c.toString());
        }
    }
}
