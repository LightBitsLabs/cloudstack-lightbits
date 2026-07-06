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
package com.cloud.hypervisor.kvm.storage;

import java.util.List;
import java.util.Map;

import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.joda.time.Duration;

import com.cloud.agent.api.to.HostTO;
import com.cloud.hypervisor.kvm.resource.KVMHABase.HAStoragePool;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.StoragePoolType;

/**
 * KVM-agent view of a Lightbits primary storage pool. Managed, per-volume NVMe/TCP:
 * there is no host-side mount; disk operations delegate to {@link LightbitsStorageAdaptor}.
 */
public class LightbitsStoragePool implements KVMStoragePool {
    private final String uuid;
    private final String sourceHost;
    private final int sourcePort;
    private final StoragePoolType type;
    private final StorageAdaptor adaptor;
    private final Map<String, String> details;

    public LightbitsStoragePool(String uuid, String sourceHost, int sourcePort,
                                StoragePoolType type, StorageAdaptor adaptor, Map<String, String> details) {
        this.uuid = uuid;
        this.sourceHost = sourceHost;
        this.sourcePort = sourcePort;
        this.type = type;
        this.adaptor = adaptor;
        this.details = details;
    }

    @Override
    public KVMPhysicalDisk createPhysicalDisk(String volumeUuid, PhysicalDiskFormat format,
                                              Storage.ProvisioningType provisioningType, long size, byte[] passphrase) {
        return adaptor.createPhysicalDisk(volumeUuid, this, format, provisioningType, size, passphrase);
    }

    @Override
    public KVMPhysicalDisk createPhysicalDisk(String volumeUuid, Storage.ProvisioningType provisioningType, long size, byte[] passphrase) {
        return adaptor.createPhysicalDisk(volumeUuid, this, getDefaultFormat(), provisioningType, size, passphrase);
    }

    @Override
    public boolean connectPhysicalDisk(String volumeUuid, Map<String, String> connectDetails) {
        return adaptor.connectPhysicalDisk(volumeUuid, this, connectDetails, false);
    }

    @Override
    public KVMPhysicalDisk getPhysicalDisk(String volumeUuid) {
        return adaptor.getPhysicalDisk(volumeUuid, this);
    }

    @Override
    public boolean disconnectPhysicalDisk(String volumeUuid) {
        return adaptor.disconnectPhysicalDisk(volumeUuid, this);
    }

    @Override
    public boolean deletePhysicalDisk(String volumeUuid, Storage.ImageFormat format) {
        return adaptor.deletePhysicalDisk(volumeUuid, this, format);
    }

    @Override
    public List<KVMPhysicalDisk> listPhysicalDisks() {
        return adaptor.listPhysicalDisks(uuid, this);
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    @Override
    public long getCapacity() {
        return 0;
    }

    @Override
    public long getUsed() {
        return 0;
    }

    @Override
    public long getAvailable() {
        return 0;
    }

    @Override
    public boolean refresh() {
        return true;
    }

    @Override
    public boolean isExternalSnapshot() {
        return true;
    }

    @Override
    public String getLocalPath() {
        return null;
    }

    @Override
    public String getSourceHost() {
        return sourceHost;
    }

    @Override
    public String getSourceDir() {
        return null;
    }

    @Override
    public int getSourcePort() {
        return sourcePort;
    }

    @Override
    public String getAuthUserName() {
        return null;
    }

    @Override
    public String getAuthSecret() {
        return null;
    }

    @Override
    public StoragePoolType getType() {
        return type;
    }

    @Override
    public boolean delete() {
        return adaptor.deleteStoragePool(this);
    }

    @Override
    public PhysicalDiskFormat getDefaultFormat() {
        return PhysicalDiskFormat.RAW;
    }

    @Override
    public boolean createFolder(String path) {
        return false;
    }

    @Override
    public boolean supportsConfigDriveIso() {
        return false;
    }

    @Override
    public Map<String, String> getDetails() {
        return details;
    }

    // --- HA not supported in v1 (matches driver.isStorageSupportHA=false) ---

    @Override
    public boolean isPoolSupportHA() {
        return false;
    }

    @Override
    public String getHearthBeatPath() {
        return null;
    }

    @Override
    public String createHeartBeatCommand(HAStoragePool primaryStoragePool, String hostPrivateIp, boolean hostValidation) {
        return null;
    }

    @Override
    public String getStorageNodeId() {
        return null;
    }

    @Override
    public Boolean hasHeartBeat(HAStoragePool pool, HostTO host) {
        return false;
    }

    @Override
    public Boolean hasVmActivity(HAStoragePool pool, HostTO host, Duration activityScriptTimeout,
                                 String volumeUUIDListString, String vmActivityCheckPath, long duration) {
        return false;
    }
}
