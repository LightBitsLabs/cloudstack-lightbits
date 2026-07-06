# Architecture (design sketch)

> Working draft — this document describes the intended design; almost none of it is implemented yet.

## Overview

The plugin implements CloudStack **managed primary storage**: each CloudStack volume maps 1:1 to a Lightbits volume, provisioned on demand and attached to KVM hosts over NVMe/TCP.

```
┌─────────────────────────────┐        REST API (443)      ┌──────────────────┐
│ CloudStack Management Server │ ─────────────────────────▶ │                  │
│  LightbitsPrimaryDataStore-  │   volume lifecycle, ACLs,  │ Lightbits cluster │
│  Provider/LifeCycle/Driver   │   snapshots, stats         │                  │
└─────────────────────────────┘                             │                  │
┌─────────────────────────────┐      NVMe/TCP (4420)        │                  │
│ KVM host (cloudstack-agent)  │ ─────────────────────────▶ │                  │
│  Lightbits StorageAdaptor    │   block I/O                └──────────────────┘
│  nvme-cli / nvme_tcp         │
└─────────────────────────────┘
```

## Management-server side

- **`LightbitsPrimaryDatastoreProvider`** — registers the provider (name `Lightbits`), exposes lifecycle + driver, `DataStoreProviderType.PRIMARY`.
- **`LightbitsPrimaryDataStoreLifeCycle`** — storage pool creation from URL/parameters (API endpoint, JWT, project, replica count, compression), zone-wide and cluster-wide scope, maintenance mode, capacity registration.
- **`LightbitsPrimaryDataStoreDriver`** — volume CRUD (`createAsync`/`deleteAsync`/`resize`), `grantAccess`/`revokeAccess` mapped to per-host NQN ACLs, snapshots via native Lightbits snapshots, template copy via thin clones, capacity/usage stats from the cluster API.

Control-path client: thin HTTPS/JSON client for the Lightbits REST API (JWT auth). **v1 targets a single Lightbits cluster per storage pool.** The endpoint is a plain configuration value, so richer Lightbits management endpoints (multi-cluster, data-management services) can be targeted in the future without plugin rework.

## KVM agent side (not started)

A `StorageAdaptor` implementation on the CloudStack KVM agent:

- `nvme connect`/`disconnect` (or the Lightbits discovery-client for multi-path/ANA failover)
- device resolution by volume NQN/GUID
- hands the raw block device to libvirt/QEMU

Host prerequisites: `nvme_tcp` kernel module, `nvme-cli`, unique host NQN registered with the cluster.

## Tenancy mapping

CloudStack account/domain → Lightbits project. Pool-level default project initially; finer-grained mapping later.

## Delivery model

Ships as a **standalone out-of-tree plugin** installed onto existing CloudStack deployments (StorPool precedent), built against a source-built CloudStack. The layout deliberately matches what an in-tree `plugins/storage/volume/lightbits` module would look like (see the Linstor plugin, apache/cloudstack#4994), so upstream contribution remains a low-cost future option.
