# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0-alpha.1] - 2026-10-18

First alpha, **for evaluation only. Not for production use.** Built against CloudStack 4.22.1.0
(KVM); tested with LightOS 3.19 and 3.20.

### Added
- Lightbits managed primary storage provider for KVM, cluster-wide and zone-wide pools, with
  project-scoped JWTs (stored encrypted) and full cluster-side cleanup on pool delete.
- Volume create, delete, attach and detach over NVMe/TCP with per-host NQN ACLs; host NQN
  auto-registered on host connect.
- VM root disks from templates via native Lightbits snapshot + clone (template cached once per pool).
- Volume snapshots: take, revert, delete, create-volume-from-snapshot.
- Offline volume resize (grow).
- Per-volume replica factor (`lightbitsReplicas` offering detail), per-pool compression and QoS
  policy, per-offering QoS override (`lightbitsQosPolicy`).
- VM live migration between KVM hosts, including host maintenance evacuation.
- `packaging/install.sh` and `uninstall.sh`.

### Known limitations
- TLS certificate of the LightOS API is not validated yet (planned for a future version).
- Online resize of attached disks is not possible (CloudStack restricts it for out-of-tree
  managed KVM storage); resize with the VM stopped. Shrink is not supported.
- KVM only.
