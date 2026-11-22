# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- API failover: `&endpoints=<host[:port]>,...` lists further API endpoints of the cluster.
  Reads fail over on any transient failure; changes fail over only when the request provably
  never reached the endpoint, otherwise the state of the object decides. An endpoint that
  refused or timed out a connection is tried last for the next minute.
- TLS certificate pinning: `&fingerprint=<SHA-256>` (two values during a certificate rotation).
  Both settings can be changed on an existing pool with `updateStoragePool`
  (`tlsFingerprint`, `apiEndpoints`).

- KVM hosts connect through every discovery endpoint of the cluster instead of one address,
  with a warning naming an endpoint that failed. `&discovery=<host[:port]>,...` overrides the
  list for clusters whose data network differs from the API network.
- An attach that cannot work fails at once with the reason (every discovery endpoint and its
  error, the host's NVMe/TCP paths, a pointer to the volume ACL or the data network) instead of
  "device did not appear" after 60 seconds.
- A VM can start on a host that has no NVMe connection to the cluster yet (first use, or
  after a host reboot). CloudStack looks a VM's disks up before it asks for them to be
  connected, and that lookup only checked for the device: such a start waited five minutes
  and failed. A lookup that finds no device now connects the host first.
- A namespace that does not show up is looked for again after a udev settle and a controller
  rescan. Paths that are not live are logged as a warning after every attach.
- The disk lookup that connects a cold host is bounded: its connect is tracked per pool (a
  lookup on one pool no longer holds back the first connect of another), an endpoint gets 15
  seconds, and after a failure the next attempt waits twice as long as the one before (up to
  five minutes) while the lookups in between report the reason. A VM start against a cluster
  that does not answer fails in minutes, not hours. The lookup also rescans the controllers
  (at most every 10 seconds) when the device is still missing on a connected host.
- A pool URL with an IPv6 address as the API host is refused at registration with a clear
  message. It was accepted before, and no volume of such a pool could be attached.
- The plugin logs its version and build time when it loads on the management server and on
  every KVM agent, and an agent warns when it runs another build than the management server
  (the jar was not replaced there, or the agent was not restarted).
- Pool registration is refused when the cluster already holds objects named for this
  environment name and a pool id this CloudStack does not have: another CloudStack uses the
  same name, or a removed pool left objects behind. Deleting a pool removes every object with
  its prefix, so a shared environment name could destroy the other deployment's volumes.

### Security
- **The pool token was written to CloudStack's logs.** The token was supplied as
  `details[0].jwt`, a parameter name CloudStack does not mask, so every pool registration left
  it in clear text in `apilog.log` and `management-server.log` on the management server. It is
  now supplied as `details[0].secretkey`, which CloudStack keeps out of its logs. **After
  upgrading from 0.1.0-alpha.1, treat the tokens used so far as exposed: replace them (and
  rotate the signing key if the logs left the server), and remove the old log files.**
- Replacing the token of an existing pool now works, from a file on the management server
  (`updateStoragePool details[0].tokenFile=<path>`, see docs/jwt-rotation.md), so the new
  token never passes through the API or its logs. The previously documented command
  (`details[0].jwt=<token>`) overwrote the stored token's bookkeeping with a clear-text value
  cut at 255 characters and broke the pool; such a value, and any token passed to
  `updateStoragePool` as `details[0].secretkey`, is now rejected and removed, whatever its
  length, and the pool keeps working on its previous token.
- An expired or rejected token produces an error that says so and how to set a new one.
- A volume's device is handed to a VM only after the NVMe namespace's own UUID and subsystem
  were matched against the volume; a stale or foreign device link is refused.
- Commands on the KVM hosts (`nvme`, `modprobe`, `qemu-img`) run as argument vectors with
  validated values; no value from the management server reaches a shell.
- The plugin no longer accepts any certificate from the Lightbits API. A pool is either pinned
  to a fingerprint or validated against the management server's trust store. **Pools created
  with 0.1.0-alpha.1 need their fingerprint set after the upgrade** (see INSTALL.md).

### Fixed
- Volume and snapshot lists are read page by page (the cluster returns at most 1000 objects
  per request). Before, the capacity sum, the environment-name check and the pool-delete sweep
  saw only the first 1000 objects of the project.
- Deleting a pool removes only objects named exactly as its own (`cs-<environment>-p<id>-`
  plus a CloudStack uuid), not every name with that prefix: environment `cs1`, pool 7 no longer
  matches the objects of an environment named `cs1-p7`. The pool's provisioned-bytes sum uses
  the same rule.
- Snapshot revert never reached the plugin: the driver did not advertise the
  `CAN_REVERT_VOLUME_TO_SNAPSHOT` capability, so CloudStack rejected every revert.
- A failed cluster-side delete was reported to CloudStack as success, orphaning the volume.
- Create, clone and snapshot returned before the object was `Available`; a create that ends in
  `Failed` or never settles is now removed from the cluster and reported as a failure.
- Resize, ACL update and rollback returned before the change was applied; they now wait for it.
- Concurrent ACL updates of one volume could drop a host; updates are now conditional on the
  volume ETag and retried.
- A failed clone left its clone-source snapshot behind.
- A 5xx answer whose body mentions "quota" is treated as a transient failure (it fails over and
  is retried) instead of a quota error.
- A wait for a volume or snapshot to settle no longer runs past its deadline when the first API
  endpoint is slow: all endpoints share the one deadline.
- An `endpoints` entry the HTTP client cannot use (for example `bad..host`), or a list longer
  than the 255 characters CloudStack can store, is rejected when the pool is registered.

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
