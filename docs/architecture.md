# Architecture

## Overview

The plugin implements CloudStack **managed primary storage**: each CloudStack volume maps 1:1 to a Lightbits volume, provisioned on demand and attached to KVM hosts over NVMe/TCP.

```
┌──────────────────────────────┐      REST API (443)       ┌───────────────────┐
│ CloudStack Management Server │ ────────────────────────▶ │                   │
│  LightbitsPrimaryDataStore-  │  volume lifecycle, ACLs,  │                   │
│  Provider/LifeCycle/Driver   │  snapshots, stats         │                   │
└──────────────────────────────┘                           │ Lightbits cluster │
┌──────────────────────────────┐      NVMe/TCP (4420)      │                   │
│ KVM host (cloudstack-agent)  │ ────────────────────────▶ │                   │
│  Lightbits StorageAdaptor    │  block I/O                │                   │
│  nvme-cli / nvme_tcp         │                           └───────────────────┘
└──────────────────────────────┘
```

## Management-server side

- **`LightbitsPrimaryDatastoreProvider`** — registers the provider (name `Lightbits`), exposes lifecycle + driver, `DataStoreProviderType.PRIMARY`.
- **`LightbitsPrimaryDataStoreLifeCycle`** — storage pool creation from URL/parameters (API endpoint, JWT, project, replica count, compression), zone-wide and cluster-wide scope, maintenance mode, capacity registration.
- **`LightbitsPrimaryDataStoreDriver`** — volume CRUD (`createAsync`/`deleteAsync`/`resize`), `grantAccess`/`revokeAccess` mapped to per-host NQN ACLs, snapshots via native Lightbits snapshots, template copy via thin clones, capacity/usage stats from the node list (`GET /api/v2/nodes`, scaled by the pool's replica factor).

Control-path client: thin HTTPS/JSON client for the Lightbits REST API, authenticated with a **project-scoped JWT** (`<project>:admin` — see INSTALL.md for minting; never the cluster-wide system JWT). The client deliberately exposes no cluster-scoped calls: a project token is denied routes like `GET /api/v2/cluster`, so every pool operation runs against `/api/v2/projects/<p>/...` plus the project-readable node list. **v1 targets a single Lightbits cluster per storage pool.** The endpoint is a plain configuration value, so richer Lightbits management endpoints (multi-cluster, data-management services) can be targeted in the future without plugin rework.

## KVM agent side (implemented)

`LightbitsStorageAdaptor` on the CloudStack KVM agent (auto-loaded via the agent's package
scan — the jar just needs to be on the agent classpath):

- `nvme connect-all` over NVMe/TCP (fans out to all advertised cluster nodes); device
  resolution by the Lightbits volume UUID at `/dev/disk/by-id/nvme-uuid.<uuid>`. Per-volume
  detach keeps the shared subsystem connected; controller teardown on pool removal is not
  yet implemented.
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
