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
    /** The details each pool was last registered with, kept after the pool is removed (see createStoragePool). */
    private static final Map<String, Map<String, String>> LAST_DETAILS = new ConcurrentHashMap<>();
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
    /** Pool detail, "true" when the pool's hosts connect with their in-band authentication key. */
    public static final String DETAIL_INBAND_AUTH = "inbandAuth";
    /** How long reading one endpoint's discovery log page for a two-way configuration may take. */
    static final long DISCOVER_TIMEOUT_MILLIS = 15_000;
    /** Pool detail from the management server: two-way in-band authentication (with inbandAuth). */
    public static final String DETAIL_INBAND_TWO_WAY = "inbandAuthTwoWay";

    private final LightbitsNvmeHost nvme;
    private final LightbitsHostKey hostKey;

    public LightbitsStorageAdaptor() {
        this(new LightbitsNvmeHost(), new LightbitsHostKey());
    }

    LightbitsStorageAdaptor(LightbitsNvmeHost nvme) {
        this(nvme, new LightbitsHostKey());
    }

    LightbitsStorageAdaptor(LightbitsNvmeHost nvme, LightbitsHostKey hostKey) {
        this.nvme = nvme;
        this.hostKey = hostKey;
        LOG.info("{} loaded on this KVM agent", org.apache.cloudstack.storage.datastore.util.LightbitsVersion.describe());
    }

    @Override
    public Storage.StoragePoolType getStoragePoolType() {
        return StoragePoolType.IscsiLUN;
    }

    @Override
    public KVMStoragePool createStoragePool(String uuid, String host, int port, String path, String userInfo,
                                            StoragePoolType type, Map<String, String> details, boolean isPrimaryStorage) {
        if (details != null && details.containsKey(DETAIL_PLUGIN_VERSION)) {
            // the Lightbits host-connect path, which always sends the plugin version: remember its details
            LAST_DETAILS.put(uuid, java.util.Collections.unmodifiableMap(new java.util.HashMap<>(details)));
        } else {
            // CloudStack's generic paths register a pool without the Lightbits details: maintenance removes
            // the pool from the host, and cancelling it sends ModifyStoragePoolCommand(add) with none, which
            // the agent turns into a map with only its own defaults (seen in 4.23.0.0). Lay those over what
            // the host-connect path last sent, or the pool loses its NVMe discovery endpoints until the next
            // host connect.
            Map<String, String> last = LAST_DETAILS.get(uuid);
            if (last != null) {
                Map<String, String> merged = new java.util.HashMap<>(last);
                if (details != null) {
                    merged.putAll(details);
                }
                details = merged;
                LOG.info("Lightbits createStoragePool: pool {} registered without its Lightbits details, keeping its"
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
        java.util.function.Function<String, java.nio.file.Path> configFor = configFor(pool);
        // One connect at a time on this host: parallel VM starts would otherwise race each
        // other's connect-all against the same subsystem.
        synchronized (CONNECT_LOCK) {
            connectPool(pool, endpoints, LightbitsNvmeHost.CONNECT_TIMEOUT_MS, configFor);
            // the host is connected: a failure an earlier lookup remembered for this pool is history
            lookupConnects.remove(pool.getUuid());
        }
        // Throws with a diagnosis (paths, ACL hint) rather than returning false: the caller
        // would turn false into a generic "failed to connect disks".
        String device = nvme.waitForDevice(uuid, DEFAULT_WAIT_SECS, nvme.hostNqn());
        if (twoWay(pool)) {
            requireTwoWayPaths(nvme.verifyIdentity(uuid));
        }
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
                answered = connectPool(pool, endpoints, LOOKUP_CONNECT_TIMEOUT_MILLIS, configFor(pool));
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
     * The nvme-cli configuration file with this host's in-band authentication key, for a pool
     * whose management server sent {@link #DETAIL_INBAND_AUTH}; null for any other pool. The key
     * is made on first use. A key that cannot be prepared fails the connect: without it the
     * host loses its paths once the cluster turns in-band authentication on.
     */
    /**
     * Connect a pool (the caller holds the connect lock). For a two-way pool, a path this connect
     * made without the controller key is dropped again, so two-way never quietly runs one-way.
     */
    private boolean connectPool(KVMStoragePool pool, List<String> endpoints, long timeoutMillis,
                                java.util.function.Function<String, java.nio.file.Path> configFor) {
        boolean twoWay = twoWay(pool);
        if (twoWay) {
            nvme.forgetVanishedRejections();   // before connect-all can reuse a rejected controller's name
        }
        java.util.Set<String> before = twoWay ? nvme.pathNames() : null;
        boolean answered;
        try {
            answered = nvme.connectWith(endpoints, timeoutMillis, configFor);
        } catch (RuntimeException e) {
            // a connect that failed may still have made paths: they get the same check
            if (twoWay) {
                try {
                    nvme.dropNewPathsWithoutCtrlKey(before, hostKey.ensureCtrlKey());
                } catch (RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
        if (twoWay) {
            nvme.dropNewPathsWithoutCtrlKey(before, hostKey.ensureCtrlKey());
        }
        return answered;
    }

    /** The pool uses two-way in-band authentication (both details from the management server). */
    static boolean twoWay(KVMStoragePool pool) {
        Map<String, String> d = pool == null ? null : pool.getDetails();
        return d != null && "true".equals(d.get(DETAIL_INBAND_AUTH)) && "true".equals(d.get(DETAIL_INBAND_TWO_WAY));
    }

    /**
     * Rotate this host's in-band authentication keys (host key and controller key): only when no
     * Lightbits volume is in use here. Under the connect lock, idle Lightbits connections are
     * dropped first (they hold the old keys and would fail their next reconnect once the cluster
     * has the new ones); if any Lightbits path is still left, a volume is still granted to this
     * host and the rotation is refused. Otherwise the keys are deleted; the caller makes new
     * ones, and the next attach connects with them.
     */
    public static void rotateInbandKeys() {
        rotateInbandKeys(new LightbitsNvmeHost(), new LightbitsHostKey());
    }

    static void rotateInbandKeys(LightbitsNvmeHost nvme, LightbitsHostKey hostKey) {
        synchronized (CONNECT_LOCK) {
            List<String> dropped = nvme.disconnectIdleSubsystems();
            int left;
            try {
                left = nvme.lightbitsPathCount();
            } catch (RuntimeException e) {
                throw new CloudRuntimeException("Lightbits: key rotation refused: this host's NVMe/TCP paths could not be counted ("
                        + e.getMessage() + "); the keys are kept", e);
            }
            if (left > 0) {
                throw new CloudRuntimeException("Lightbits: key rotation refused: " + left + " Lightbits NVMe/TCP path(s) of this"
                        + " host still carry volumes. Put the host in maintenance (its VMs migrate away), then reconnect it");
            }
            hostKey.deleteKeys();
            LOG.info("Lightbits: rotating this host's in-band authentication keys (disconnected idle subsystems {})", dropped);
        }
    }

    /**
     * The configuration file to connect a pool with, chosen per discovery endpoint: none for a
     * pool without in-band authentication, the shared one-way file ({@link #authConfig}) for
     * one-way, and for two-way the pool's own file with the host and controller keys on every
     * data port the endpoint advertises (read just before the connect, from its discovery log
     * page; nvme-cli takes a controller key only per port). A key that cannot be prepared, or a
     * discovery that fails, counts as that endpoint failing.
     */
    java.util.function.Function<String, java.nio.file.Path> configFor(KVMStoragePool pool) {
        java.nio.file.Path oneWay = authConfig(pool);
        if (oneWay == null) {
            return null;
        }
        Map<String, String> details = pool.getDetails();
        if (!"true".equals(details.get(DETAIL_INBAND_TWO_WAY))) {
            return endpoint -> oneWay;
        }
        String poolUuid = pool.getUuid();
        return endpoint -> hostKey.ensureTwoWayConfig(poolUuid, nvme.discover(endpoint, DISCOVER_TIMEOUT_MILLIS));
    }

    java.nio.file.Path authConfig(KVMStoragePool pool) {
        Map<String, String> details = pool.getDetails();
        if (details == null || !"true".equals(details.get(DETAIL_INBAND_AUTH))) {
            return null;
        }
        return hostKey.ensureConfig();
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
        if (twoWay(pool)) {
            requireTwoWayPathsUnlessInUse(kernelName, uuid);
        }
        KVMPhysicalDisk disk = new KVMPhysicalDisk(LightbitsNvmeHost.BY_ID_PREFIX + uuid, volumePath, pool);
        disk.setFormat(PhysicalDiskFormat.RAW);
        disk.setSize(nvme.sizeBytes(kernelName));
        disk.setVirtualSize(disk.getSize());
        return disk;
    }

    /**
     * The two-way check before a handoff, under the connect lock: a connect holds it until it has
     * dropped the paths it made without the controller key, so the check never sees a connect
     * half done. A later connect that cannot drop such a path fails and marks it rejected, which
     * the next check refuses.
     */
    private void requireTwoWayPaths(String kernelName) {
        String ctrlKey = hostKey.ensureCtrlKey();
        synchronized (CONNECT_LOCK) {
            nvme.requireTwoWayPaths(kernelName, ctrlKey);
        }
    }

    /**
     * The two-way check of a lookup. A lookup also serves operations that hand nothing to a VM:
     * a detach looks the disk up before it removes it from the VM, and so do volume statistics.
     * A device a VM on this host already holds open is therefore looked up without the check;
     * every handoff (attach, VM start, migration onto the host) also goes through
     * {@link #connectPhysicalDisk}, whose check has no such exception.
     */
    private void requireTwoWayPathsUnlessInUse(String kernelName, String uuid) {
        try {
            requireTwoWayPaths(kernelName);
        } catch (CloudRuntimeException e) {
            if (!nvme.openByVm(kernelName)) {
                throw e;
            }
            LOG.info("Lightbits: volume {} ({}) is already in use by a VM on this host; looked up although this host's"
                    + " paths are not all two-way yet (a new handoff of a volume is still refused until they are)", uuid,
                    kernelName);
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
        synchronized (CONNECT_LOCK) {
            lookupConnects.remove(uuid);
        }
        if (!POOLS.isEmpty()) {
            return true;
        }
        // No Lightbits pool is left on this host (deleted, or put in maintenance): drop the NVMe/TCP
        // connections nothing uses any more. The next attach connects again. This also runs when the
        // pool was already withdrawn: maintenance withdraws it before it stops the VMs, so a subsystem
        // that still had a namespace then is only idle when the pool is finally deleted.
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
