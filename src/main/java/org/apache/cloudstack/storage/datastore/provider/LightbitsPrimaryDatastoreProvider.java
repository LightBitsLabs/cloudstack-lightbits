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

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.HypervisorHostListener;
import org.apache.cloudstack.storage.datastore.driver.LightbitsPrimaryDataStoreDriver;
import org.apache.cloudstack.storage.datastore.lifecycle.LightbitsPrimaryDataStoreLifeCycle;

public class LightbitsPrimaryDatastoreProvider implements DataStoreProvider {
    public static final String PROVIDER_NAME = "Lightbits";

    private DataStoreLifeCycle lifeCycle;
    private DataStoreDriver driver;

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public DataStoreLifeCycle getDataStoreLifeCycle() {
        return lifeCycle;
    }

    @Override
    public DataStoreDriver getDataStoreDriver() {
        return driver;
    }

    @Override
    public HypervisorHostListener getHostListener() {
        // TODO: host listener wiring KVM hosts to the storage pool (connect/disconnect hooks)
        return null;
    }

    @Override
    public boolean configure(Map<String, Object> params) {
        lifeCycle = new LightbitsPrimaryDataStoreLifeCycle();
        driver = new LightbitsPrimaryDataStoreDriver();
        return true;
    }

    @Override
    public Set<DataStoreProviderType> getTypes() {
        return Collections.singleton(DataStoreProviderType.PRIMARY);
    }
}
