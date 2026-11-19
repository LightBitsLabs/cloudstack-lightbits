# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed
- Snapshot revert never reached the plugin: the driver did not advertise the
  `CAN_REVERT_VOLUME_TO_SNAPSHOT` capability, so CloudStack rejected every revert.
- A failed cluster-side delete was reported to CloudStack as success, orphaning the volume.
- Create, clone and snapshot returned before the object was `Available`; a create that ends in
  `Failed` or never settles is now removed from the cluster and reported as a failure.
- Resize, ACL update and rollback returned before the change was applied; they now wait for it.
- Concurrent ACL updates of one volume could drop a host; updates are now conditional on the
  volume ETag and retried.
- A failed clone left its clone-source snapshot behind.

### Changed
- Revert is refused when the volume was grown after the snapshot was taken (a rollback would
  shrink it) and when the volume is still exposed to a host.
- Builds and passes unit tests against CloudStack 4.23.0.0 (`-Dcloudstack.version=4.23.0.0`) as
  well as 4.22.1.0.

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
