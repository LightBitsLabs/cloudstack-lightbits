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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.storage.Storage;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;

/**
 * KVM-agent storage adaptor for Lightbits NVMe/TCP volumes.
 *
 * Auto-discovered by KVMStoragePoolManager's Reflections scan of this package
 * (getStoragePoolType() -> IscsiLUN, which no built-in adaptor claims). Volumes are
 * connected over NVMe/TCP via the cluster discovery service; the device is resolved
 * by the Lightbits volume UUID at /dev/disk/by-id/nvme-uuid.<UUID>.
 */
public class LightbitsStorageAdaptor implements StorageAdaptor {
    private static final Logger LOG = LogManager.getLogger(LightbitsStorageAdaptor.class);

    private static final Map<String, KVMStoragePool> POOLS = new ConcurrentHashMap<>();
    private static final int DISCOVERY_PORT = 8009;
    private static final int DEFAULT_WAIT_SECS = 60;
    private static final String BY_ID_PREFIX = "/dev/disk/by-id/nvme-uuid.";

    public LightbitsStorageAdaptor() {
    }

    @Override
    public Storage.StoragePoolType getStoragePoolType() {
        return StoragePoolType.IscsiLUN;
    }

    @Override
    public KVMStoragePool createStoragePool(String uuid, String host, int port, String path, String userInfo,
                                            StoragePoolType type, Map<String, String> details, boolean isPrimaryStorage) {
        LightbitsStoragePool pool = new LightbitsStoragePool(uuid, host, port, getStoragePoolType(), this, details);
        POOLS.put(uuid, pool);
        LOG.info("Lightbits createStoragePool: registered pool {} host {} port {} (POOLS now has {})",
                uuid, host, port, POOLS.keySet());
        return pool;
    }

    @Override
    public KVMStoragePool getStoragePool(String uuid) {
        return getStoragePool(uuid, false);
    }

    @Override
    public KVMStoragePool getStoragePool(String uuid, boolean refreshInfo) {
        KVMStoragePool pool = POOLS.get(uuid);
        LOG.info("Lightbits getStoragePool({}) -> {} (cached: {})", uuid,
                pool == null ? "NULL" : "found", POOLS.keySet());
        return pool;
    }

    @Override
    public boolean connectPhysicalDisk(String volumePath, KVMStoragePool pool, Map<String, String> details, boolean isVMMigrate) {
        LOG.info("Lightbits connectPhysicalDisk: volumePath={} pool={}", volumePath,
                pool == null ? "null" : pool.getUuid());
        if (volumePath == null || pool == null) {
            throw new CloudRuntimeException("Lightbits connectPhysicalDisk: missing volume path ("
                    + volumePath + ") or pool (" + (pool == null ? "null" : pool.getUuid()) + ")");
        }
        ensureNvmeTcpModule();
        // Discovery-connect to the cluster; all volumes ACL'd to this host's NQN attach.
        String discoveryHost = pool.getSourceHost();
        String out = Script.runSimpleBashScript(
                String.format("nvme connect-all -t tcp -a %s -s %d", discoveryHost, DISCOVERY_PORT));
        LOG.info("nvme connect-all to {}:{} -> {}", discoveryHost, DISCOVERY_PORT, out);
        return waitForDevice(volumePath, DEFAULT_WAIT_SECS);
    }

    private boolean waitForDevice(String volumeUuid, int waitSecs) {
        String devPath = BY_ID_PREFIX + volumeUuid;
        for (int i = 0; i < waitSecs; i++) {
            if (new File(devPath).exists()) {
                LOG.info("Lightbits volume {} present at {}", volumeUuid, devPath);
                return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        boolean present = new File(devPath).exists();
        if (!present) {
            LOG.warn("Lightbits volume {} did not appear at {} within {}s", volumeUuid, devPath, waitSecs);
        }
        return present;
    }

    @Override
    public KVMPhysicalDisk getPhysicalDisk(String volumePath, KVMStoragePool pool) {
        String devPath = BY_ID_PREFIX + volumePath;
        File dev = new File(devPath);
        if (!dev.exists()) {
            LOG.debug("Lightbits device {} not present", devPath);
            return null;
        }
        KVMPhysicalDisk disk = new KVMPhysicalDisk(devPath, volumePath, pool);
        disk.setFormat(PhysicalDiskFormat.RAW);
        disk.setSize(blockDeviceSize(devPath));
        disk.setVirtualSize(disk.getSize());
        return disk;
    }

    private long blockDeviceSize(String devPath) {
        try {
            String out = Script.runSimpleBashScript("blockdev --getsize64 " + devPath);
            return out == null ? 0L : Long.parseLong(out.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public boolean disconnectPhysicalDisk(String volumePath, KVMStoragePool pool) {
        // The volume detaches when its ACL is removed (driver.revokeAccess) and the
        // namespace is rescanned away. We do not tear down the shared subsystem
        // connection here, as other volumes may still be in use.
        return true;
    }

    @Override
    public boolean disconnectPhysicalDisk(Map<String, String> volumeToDisconnect) {
        return true;
    }

    @Override
    public boolean disconnectPhysicalDiskByPath(String localPath) {
        return true;
    }

    @Override
    public boolean deletePhysicalDisk(String uuid, KVMStoragePool pool, Storage.ImageFormat format) {
        // Volume deletion happens on the management side via the REST API (driver).
        return true;
    }

    @Override
    public boolean deleteStoragePool(String uuid) {
        POOLS.remove(uuid);
        return true;
    }

    @Override
    public boolean deleteStoragePool(KVMStoragePool pool) {
        return deleteStoragePool(pool.getUuid());
    }

    @Override
    public List<KVMPhysicalDisk> listPhysicalDisks(String storagePoolUuid, KVMStoragePool pool) {
        return new ArrayList<>();
    }

    @Override
    public boolean refresh(KVMStoragePool pool) {
        return true;
    }

    @Override
    public boolean createFolder(String uuid, String path) {
        return false;
    }

    @Override
    public boolean createFolder(String uuid, String path, String localPath) {
        return false;
    }

    @Override
    public KVMPhysicalDisk createTemplateFromDirectDownloadFile(String templateFilePath, String destTemplatePath,
                                                                KVMStoragePool destPool, Storage.ImageFormat format, int timeout) {
        throw new UnsupportedOperationException("Lightbits: direct-download template not supported");
    }

    @Override
    public KVMPhysicalDisk createPhysicalDisk(String name, KVMStoragePool pool, PhysicalDiskFormat format,
                                              Storage.ProvisioningType provisioningType, long size, byte[] passphrase) {
        // Volumes are provisioned on the cluster via the REST API (management driver),
        // not created on the agent. The device is discovered via connectPhysicalDisk.
        throw new UnsupportedOperationException("Lightbits: volumes are created via the management API, not the agent");
    }

    @Override
    public KVMPhysicalDisk createDiskFromTemplate(KVMPhysicalDisk template, String name, PhysicalDiskFormat format,
                                                  Storage.ProvisioningType provisioningType, long size,
                                                  KVMStoragePool destPool, int timeout, byte[] passphrase) {
        // Root-disk-from-template: the Lightbits volume is already created + connected;
        // stream the template onto the raw NVMe device with qemu-img convert.
        KVMPhysicalDisk dest = getPhysicalDisk(name, destPool);
        if (dest == null) {
            throw new CloudRuntimeException("Lightbits: target volume " + name + " not connected before template copy");
        }
        String cmd = String.format("qemu-img convert -O raw %s %s", template.getPath(), dest.getPath());
        String out = timeout > 0 ? Script.runSimpleBashScript(cmd, timeout) : Script.runSimpleBashScript(cmd);
        LOG.info("qemu-img convert template {} -> {} : {}", template.getPath(), dest.getPath(), out);
        dest.setFormat(PhysicalDiskFormat.RAW);
        return dest;
    }

    @Override
    public KVMPhysicalDisk createDiskFromTemplateBacking(KVMPhysicalDisk template, String name, PhysicalDiskFormat format,
                                                         long size, KVMStoragePool destPool, int timeout, byte[] passphrase) {
        // Backing-file clones require a local file backend; Lightbits uses full copies (thin
        // clone-from-snapshot at the cluster is a future optimization).
        return null;
    }

    @Override
    public KVMPhysicalDisk createTemplateFromDisk(KVMPhysicalDisk disk, String name, PhysicalDiskFormat format,
                                                  long size, KVMStoragePool destPool) {
        throw new UnsupportedOperationException("Lightbits: createTemplateFromDisk not supported");
    }

    @Override
    public KVMPhysicalDisk copyPhysicalDisk(KVMPhysicalDisk disk, String name, KVMStoragePool destPool, int timeout) {
        // Copy a source disk (e.g. a template on NFS secondary, qcow2) onto the connected
        // Lightbits raw NVMe device. Used by copyTemplateToPrimaryStorage and volume clones.
        KVMPhysicalDisk dest = getPhysicalDisk(name, destPool);
        if (dest == null) {
            throw new CloudRuntimeException("Lightbits copyPhysicalDisk: dest volume " + name + " not connected");
        }
        String cmd = String.format("qemu-img convert -n -O raw %s %s", disk.getPath(), dest.getPath());
        String out = timeout > 0 ? Script.runSimpleBashScript(cmd, timeout) : Script.runSimpleBashScript(cmd);
        LOG.info("Lightbits copyPhysicalDisk: qemu-img convert {} -> {} : {}", disk.getPath(), dest.getPath(), out);
        dest.setFormat(PhysicalDiskFormat.RAW);
        dest.setVirtualSize(disk.getVirtualSize());
        return dest;
    }

    @Override
    public KVMPhysicalDisk copyPhysicalDisk(KVMPhysicalDisk disk, String name, KVMStoragePool destPool, int timeout,
                                            byte[] srcPassphrase, byte[] dstPassphrase, Storage.ProvisioningType provisioningType) {
        // v1: no volume encryption (open item); delegate to the plain copy.
        return copyPhysicalDisk(disk, name, destPool, timeout);
    }

    private void ensureNvmeTcpModule() {
        Script.runSimpleBashScript("modprobe nvme-tcp");
    }
}
