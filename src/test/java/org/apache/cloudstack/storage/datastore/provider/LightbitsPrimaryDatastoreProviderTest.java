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
package org.apache.cloudstack.storage.datastore.provider;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreProvider;
import org.junit.jupiter.api.Test;

class LightbitsPrimaryDatastoreProviderTest {

    /**
     * CloudStack calls hostAdded, hostEnabled, hostAboutToBeRemoved and hostRemoved only on
     * providers of this type. As a plain DataStoreProvider the plugin was never told that a
     * host was removed, and the removed host stayed in the volume ACLs.
     */
    @Test
    void isAPrimaryDataStoreProviderSoHostEventsReachTheListener() {
        assertTrue(PrimaryDataStoreProvider.class.isAssignableFrom(LightbitsPrimaryDatastoreProvider.class));
    }
}
