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

## KVM agent side (implemented)

`LightbitsStorageAdaptor` on the CloudStack KVM agent (auto-loaded via the agent's package
scan — the jar just needs to be on the agent classpath):

- `nvme connect`/`disconnect` over NVMe/TCP; device resolution by the Lightbits volume UUID
  at `/dev/disk/by-id/nvme-uuid.<uuid>`
- hands the raw block device to libvirt/QEMU (attach/detach)
- `grantAccess`/`revokeAccess` map the host's NVMe NQN into the volume ACL at attach/detach time

Host prerequisites: `nvme_tcp` kernel module and `nvme-cli`. The host NQN is registered with
the cluster automatically — the HostListener reads `/etc/nvme/hostnqn` on host connect and
stores it as a host detail (no manual step).

## Tenancy mapping

CloudStack account/domain → Lightbits project. Pool-level default project initially; finer-grained mapping later.

## Delivery model

Ships as a **standalone out-of-tree plugin** installed onto existing CloudStack deployments,
built against a source-built CloudStack. The layout deliberately matches what an in-tree
`plugins/storage/volume/lightbits` module would look like, so upstream contribution remains a
low-cost future option.
