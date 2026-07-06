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

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.HypervisorHostListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ModifyStoragePoolCommand;
import com.cloud.agent.AgentManager;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Host listener for the Lightbits managed primary storage pool.
 *
 * On host connect it sends a ModifyStoragePoolCommand so the KVM agent registers the pool
 * with its LightbitsStorageAdaptor (needed so per-volume connectPhysicalDisk can later find
 * the pool). It also records the host<->pool association. There is no host-side mount:
 * individual volumes connect over NVMe/TCP at attach time (driver grantAccess + adaptor).
 *
 * A non-null listener is REQUIRED: StoragePoolMonitor calls
 * provider.getHostListener().hostConnect(...) for every host that connects while the pool
 * exists; returning null NPEs and wedges the host in Alert.
 */
public class LightbitsHostListener implements HypervisorHostListener {
    private static final Logger LOG = LogManager.getLogger(LightbitsHostListener.class);

    @Inject
    private AgentManager agentMgr;
    @Inject
    private DataStoreManager dataStoreMgr;
    @Inject
    private HostDao hostDao;
    @Inject
    private StoragePoolHostDao storagePoolHostDao;

    @Override
    public boolean hostAdded(long hostId) {
        return true;
    }

    @Override
    public boolean hostConnect(long hostId, long poolId) {
        LOG.info("Lightbits hostConnect: host={} pool={}", hostId, poolId);
        StoragePool pool = (StoragePool) dataStoreMgr.getDataStore(poolId, DataStoreRole.Primary);

        StoragePoolHostVO association = storagePoolHostDao.findByPoolHost(poolId, hostId);
        if (association == null) {
            storagePoolHostDao.persist(new StoragePoolHostVO(poolId, hostId, ""));
        }

        // Register the pool on the KVM agent's LightbitsStorageAdaptor so per-volume
        // connectPhysicalDisk can resolve it. Managed, per-volume NVMe/TCP: no pool mount.
        ModifyStoragePoolCommand cmd = new ModifyStoragePoolCommand(true, pool);
        Answer answer = agentMgr.easySend(hostId, cmd);
        if (answer == null || !answer.getResult()) {
            String detail = answer == null ? "no answer" : answer.getDetails();
            throw new CloudRuntimeException("Lightbits: failed to register pool " + poolId
                    + " on host " + hostId + ": " + detail);
        }
        LOG.info("Lightbits pool {} registered on host {}", poolId, hostId);
        return true;
    }

    @Override
    public boolean hostDisconnected(long hostId, long poolId) {
        StoragePoolHostVO association = storagePoolHostDao.findByPoolHost(poolId, hostId);
        if (association != null) {
            storagePoolHostDao.deleteStoragePoolHostDetails(hostId, poolId);
        }
        return true;
    }

    @Override
    public boolean hostAboutToBeRemoved(long hostId) {
        return true;
    }

    @Override
    public boolean hostRemoved(long hostId, long clusterId) {
        return true;
    }

    @Override
    public boolean hostEnabled(long hostId) {
        return true;
    }
}
