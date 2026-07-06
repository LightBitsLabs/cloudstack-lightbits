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
import org.apache.cloudstack.storage.datastore.util.LightbitsUtil;

import com.cloud.utils.component.ComponentContext;

public class LightbitsPrimaryDatastoreProvider implements DataStoreProvider {
    public static final String PROVIDER_NAME = LightbitsUtil.PROVIDER_NAME;

    private DataStoreLifeCycle lifeCycle;
    private DataStoreDriver driver;
    private HypervisorHostListener hostListener;

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    // Lazy-init getters: the provider registry captures getHostListener()/getDataStoreDriver()
    // at registration time, which can precede configure() depending on the registration path.
    // Initializing on first access guarantees a non-null instance regardless of ordering
    // (a null host listener here NPEs connectHostToSharedPool and wedges hosts).

    @Override
    public synchronized DataStoreLifeCycle getDataStoreLifeCycle() {
        if (lifeCycle == null) {
            lifeCycle = ComponentContext.inject(LightbitsPrimaryDataStoreLifeCycle.class);
        }
        return lifeCycle;
    }

    @Override
    public synchronized DataStoreDriver getDataStoreDriver() {
        if (driver == null) {
            driver = ComponentContext.inject(LightbitsPrimaryDataStoreDriver.class);
        }
        return driver;
    }

    @Override
    public synchronized HypervisorHostListener getHostListener() {
        if (hostListener == null) {
            hostListener = ComponentContext.inject(LightbitsHostListener.class);
        }
        return hostListener;
    }

    @Override
    public boolean configure(Map<String, Object> params) {
        // Eagerly initialize (idempotent with the lazy getters). ComponentContext.inject
        // wires @Inject fields — the standard out-of-tree pattern (SolidFire/Linstor/ScaleIO).
        getDataStoreLifeCycle();
        getDataStoreDriver();
        getHostListener();
        return true;
    }

    @Override
    public Set<DataStoreProviderType> getTypes() {
        return Collections.singleton(DataStoreProviderType.PRIMARY);
    }
}
