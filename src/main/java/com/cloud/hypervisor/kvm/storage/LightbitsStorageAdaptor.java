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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.storage.Storage;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * KVM-agent storage adaptor for Lightbits NVMe/TCP volumes.
 *
 * Auto-discovered by KVMStoragePoolManager's Reflections scan of this package
 * (getStoragePoolType() -> IscsiLUN, which no built-in adaptor claims). Volumes are
 * connected over NVMe/TCP via the cluster discovery service; the device is resolved
 * by the Lightbits volume UUID at /dev/disk/by-id/nvme-uuid.<UUID> and verified against
 * the namespace's own UUID before it is used (see {@link LightbitsNvmeHost}).
 */
public class LightbitsStorageAdaptor implements StorageAdaptor {
    private static final Logger LOG = LogManager.getLogger(LightbitsStorageAdaptor.class);

    private static final Map<String, KVMStoragePool> POOLS = new ConcurrentHashMap<>();
    private static final int DEFAULT_WAIT_SECS = 60;
    private static final Object CONNECT_LOCK = new Object();
    private static final Object RESCAN_LOCK = new Object();
    /** A lookup that finds no device triggers a connect of its pool at most this often. */
    static final long LOOKUP_CONNECT_INTERVAL_MILLIS = 10_000;
    /** After failed lookup connects the interval doubles per failure, up to this. */
    static final long LOOKUP_CONNECT_MAX_BACKOFF_MILLIS = 300_000;
    /**
     * Time one discovery endpoint gets to answer a connect made for a lookup. The agent polls
     * the lookup up to 100 times, so the full connect timeout per endpoint and per poll would
     * turn a five minute failure into hours and keep other connects waiting behind it.
     */
    static final long LOOKUP_CONNECT_TIMEOUT_MILLIS = 15_000;
    /** A lookup that still finds no device rescans the controllers at most this often. */
    static final long LOOKUP_RESCAN_INTERVAL_MILLIS = 10_000;
    private static final long LOOKUP_WAIT_MILLIS = 2_000;

    /** The lookup-triggered connects of one pool. Guarded by CONNECT_LOCK. */
    private static final class LookupConnect {
        long notBefore;
        int failures;
        String lastFailure;
    }

    private final Map<String, LookupConnect> lookupConnects = new HashMap<>();
    /** Guarded by RESCAN_LOCK. */
    private long lookupRescanNotBefore;
    /** Replaced in unit tests. */
    LongSupplier clock = System::currentTimeMillis;
    /** Pool detail carrying the cluster's discovery endpoints, comma-separated host[:port]. */
    public static final String DETAIL_NVME_DISCOVERY = "nvmeDiscovery";
    /** Pool detail carrying the plugin version of the management server, for the mismatch warning. */
    public static final String DETAIL_PLUGIN_VERSION = "pluginVersion";

    private final LightbitsNvmeHost nvme;

    public LightbitsStorageAdaptor() {
        this(new LightbitsNvmeHost());
    }

    LightbitsStorageAdaptor(LightbitsNvmeHost nvme) {
        this.nvme = nvme;
        LOG.info("{} loaded on this KVM agent", org.apache.cloudstack.storage.datastore.util.LightbitsVersion.describe());
    }

    @Override
    public Storage.StoragePoolType getStoragePoolType() {
        return StoragePoolType.IscsiLUN;
    }

    @Override
    public KVMStoragePool createStoragePool(String uuid, String host, int port, String path, String userInfo,
                                            StoragePoolType type, Map<String, String> details, boolean isPrimaryStorage) {
        if (details == null) {
            // CloudStack's generic paths re-register a pool without details (cancelling maintenance sends
            // ModifyStoragePoolCommand(add) with none, seen in 4.23.0.0): keep what the Lightbits host-connect
            // path sent, or the pool would lose its NVMe discovery endpoints until the next host connect
            KVMStoragePool known = POOLS.get(uuid);
            if (known != null && known.getDetails() != null) {
                details = known.getDetails();
                LOG.info("Lightbits createStoragePool: pool {} re-registered without details, keeping its"
                        + " discovery endpoints {}", uuid, details.get(DETAIL_NVME_DISCOVERY));
            }
        }
        LightbitsStoragePool pool = new LightbitsStoragePool(uuid, host, port, getStoragePoolType(), this, details);
        POOLS.put(uuid, pool);
        warnOnVersionMismatch(details == null ? null : details.get(DETAIL_PLUGIN_VERSION),
                org.apache.cloudstack.storage.datastore.util.LightbitsVersion.version());
        LOG.info("Lightbits createStoragePool: registered pool {} host {} port {} (POOLS now has {})",
                uuid, host, port, POOLS.keySet());
        return pool;
    }

    /** Returns true (and warns) when the management server runs another build of the plugin than this agent. */
    static boolean warnOnVersionMismatch(String managementVersion, String agentVersion) {
        if (managementVersion == null || managementVersion.isBlank() || managementVersion.equals(agentVersion)) {
            return false;
        }
        LOG.warn("Lightbits: this KVM agent runs plugin build {} but the management server runs {}. Install the same"
                + " plugin jar on the management server and on every host and restart cloudstack-agent; until then this"
                + " host may behave differently from what the management server expects.", agentVersion, managementVersion);
        return true;
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
        String uuid = LightbitsNvmeHost.requireVolumeUuid(volumePath);
        List<String> endpoints = discoveryEndpoints(pool);
        // One connect at a time on this host: parallel VM starts would otherwise race each
        // other's connect-all against the same subsystem.
        synchronized (CONNECT_LOCK) {
            nvme.connect(endpoints);
            // the host is connected: a failure an earlier lookup remembered for this pool is history
            lookupConnects.remove(pool.getUuid());
        }
        // Throws with a diagnosis (paths, ACL hint) rather than returning false: the caller
        // would turn false into a generic "failed to connect disks".
        String device = nvme.waitForDevice(uuid, DEFAULT_WAIT_SECS, nvme.hostNqn());
        LOG.info("Lightbits volume {} verified at {}", uuid, device);
        nvme.warnOnDegradedPaths(endpoints.size());
        return true;
    }

    /**
     * Connect this host to the pool's cluster on behalf of a lookup. Lookups are polled every
     * few seconds, so the work is bounded per pool: one connect serves the lookups of the next
     * {@link #LOOKUP_CONNECT_INTERVAL_MILLIS}, each endpoint gets
     * {@link #LOOKUP_CONNECT_TIMEOUT_MILLIS}, and after a failure the next attempt waits twice
     * as long as the one before (up to {@link #LOOKUP_CONNECT_MAX_BACKOFF_MILLIS}). The times
     * count from the end of an attempt, so a slow failure is not retried by the very next poll.
     *
     * A failure to connect is reported to the caller (it carries the diagnosis), and so is
     * every lookup until the next attempt is due, for as long as the host has no live path:
     * no connect is made for those, and the caller ends with the reason instead of a bare
     * "not found". Once the host has a live path (another pool of the same cluster connected
     * it, or the kernel reconnected) the lookup goes on to its wait and rescan instead.
     *
     * A connect that no endpoint answered and that only went on because the host holds live
     * paths counts as a failure for the backoff: those paths can belong to another cluster.
     */
    private void connectIfDue(KVMStoragePool pool) {
        if (pool == null) {
            return;
        }
        synchronized (CONNECT_LOCK) {
            LookupConnect state = lookupConnects.computeIfAbsent(pool.getUuid(), k -> new LookupConnect());
            long now = clock.getAsLong();
            if (now < state.notBefore) {
                if (state.lastFailure != null && nvme.livePaths().isEmpty()) {
                    throw new CloudRuntimeException(state.lastFailure + " (next attempt in "
                            + ((state.notBefore - now + 999) / 1000) + "s)");
                }
                return;
            }
            boolean answered;
            List<String> endpoints = discoveryEndpoints(pool);
            try {
                answered = nvme.connect(endpoints, LOOKUP_CONNECT_TIMEOUT_MILLIS);
            } catch (CloudRuntimeException e) {
                state.lastFailure = e.getMessage();
                backOff(state);
                throw e;
            }
            if (answered) {
                state.lastFailure = null;
                state.failures = 0;
                state.notBefore = clock.getAsLong() + LOOKUP_CONNECT_INTERVAL_MILLIS;
            } else {
                // reported only once the host has no live path left (see above)
                state.lastFailure = "Lightbits: no NVMe discovery endpoint of pool " + pool.getUuid() + " answered "
                        + endpoints + "; the host went on with the live paths it had, which can belong to another cluster";
                backOff(state);
            }
        }
    }

    private void backOff(LookupConnect state) {
        state.failures++;
        long backoff = LOOKUP_CONNECT_INTERVAL_MILLIS << Math.min(state.failures, 10);
        state.notBefore = clock.getAsLong() + Math.min(backoff, LOOKUP_CONNECT_MAX_BACKOFF_MILLIS);
    }

    /**
     * Rescan the controllers on behalf of a lookup, rate-limited. Returns true if a rescan ran.
     * A namespace whose arrival the kernel or udev missed never shows up by itself while the
     * controller stays connected, and connecting again does not rescan a controller that is
     * connected already.
     */
    private boolean rescanIfDue() {
        synchronized (RESCAN_LOCK) {
            if (clock.getAsLong() < lookupRescanNotBefore) {
                return false;
            }
            nvme.rescan();
            lookupRescanNotBefore = clock.getAsLong() + LOOKUP_RESCAN_INTERVAL_MILLIS;
            return true;
        }
    }

    /**
     * Discovery endpoints for a pool: the list the management server sent with the pool
     * (every node of the cluster), or the pool's API host on the discovery port.
     */
    static List<String> discoveryEndpoints(KVMStoragePool pool) {
        Map<String, String> details = pool.getDetails();
        return LightbitsNvmeHost.discoveryEndpoints(details == null ? null : details.get(DETAIL_NVME_DISCOVERY),
                pool.getSourceHost());
    }

    @Override
    public KVMPhysicalDisk getPhysicalDisk(String volumePath, KVMStoragePool pool) {
        String uuid = LightbitsNvmeHost.requireVolumeUuid(volumePath);
        if (!nvme.deviceLinkExists(uuid)) {
            // The agent looks a VM's disks up before it asks for them to be connected (StartCommand
            // builds the disk definitions first). On a host with no connection to the cluster yet,
            // after a reboot or on first use, the device can only appear once the host connects,
            // so a failed lookup makes sure it is connected. The caller polls this method, and
            // it runs before connectPhysicalDisk: a device that is still missing on a connected
            // host is looked for with a rescan here, or the VM start never gets past the lookup.
            connectIfDue(pool);
            if (!nvme.waitForDeviceLink(uuid, LOOKUP_WAIT_MILLIS) && !(rescanIfDue() && nvme.deviceLinkExists(uuid))) {
                LOG.debug("Lightbits device for volume {} not present", uuid);
                return null;
            }
        }
        // never hand out a path whose namespace was not matched against the volume
        String kernelName = nvme.verifyIdentity(uuid);
        KVMPhysicalDisk disk = new KVMPhysicalDisk(LightbitsNvmeHost.BY_ID_PREFIX + uuid, volumePath, pool);
        disk.setFormat(PhysicalDiskFormat.RAW);
        disk.setSize(nvme.sizeBytes(kernelName));
        disk.setVirtualSize(disk.getSize());
        return disk;
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
        KVMStoragePool removed = POOLS.remove(uuid);
        synchronized (CONNECT_LOCK) {
            lookupConnects.remove(uuid);
        }
        if (removed == null || !POOLS.isEmpty()) {
            return true;
        }
        // The last Lightbits pool of this host is gone (deleted, or put in maintenance): drop the
        // NVMe/TCP connections nothing uses any more. The next attach connects again.
        synchronized (CONNECT_LOCK) {
            if (!POOLS.isEmpty()) {
                return true;
            }
            try {
                nvme.disconnectIdleSubsystems();
                lookupConnects.clear();
            } catch (RuntimeException e) {
                LOG.warn("Lightbits: could not clean up NVMe/TCP connections after pool {} was removed: {}", uuid, e.toString());
            }
        }
        return true;
    }

    @Override
    public boolean deleteStoragePool(KVMStoragePool pool) {
        return deleteStoragePool(pool.getUuid());
    }

    /**
     * The agent takes this entry when the management server withdraws a pool from the host
     * (ModifyStoragePoolCommand with add=false, sent for pool maintenance). The interface default
     * returns true without doing anything, which left the pool registered and the host connected.
     */
    @Override
    public boolean deleteStoragePool(String uuid, Map<String, String> details) {
        return deleteStoragePool(uuid);
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
        nvme.convertToRaw(template.getPath(), dest.getPath(), false, timeout);
        LOG.info("qemu-img convert template {} -> {} done", template.getPath(), dest.getPath());
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
        nvme.convertToRaw(disk.getPath(), dest.getPath(), true, timeout);
        LOG.info("Lightbits copyPhysicalDisk: qemu-img convert {} -> {} done", disk.getPath(), dest.getPath());
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
}
