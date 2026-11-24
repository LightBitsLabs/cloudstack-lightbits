# Changelog

All notable changes to this project will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Pool option `inbandAuth=true`: NVMe in-band authentication (DH-HMAC-CHAP, host to cluster).
  Each KVM host makes its own key (root-only, under `/etc/nvme/lightbits/`) and connects to
  the pool's cluster with it through an nvme-cli configuration file, so the key never appears
  on a command line. On host connect the management server registers the host as a trusted
  host of the pool's project with that key; it keeps only a fingerprint and never logs the
  key. Each host connect logs whether every host of the pool is registered and connects with
  its key. Enforcement is cluster-wide, so the cluster admin turns it on only when every pool
  on the cluster logs that its hosts are ready and every client outside CloudStack has its
  own key. Removing a host
  deletes the trusted host the plugin made. Pool creation with the option fails, with the
  `lbcli` command to run, when the cluster's InBandAuthentication feature flag is off.
- Pool option `ipAcl=true`: each volume's IP ACL holds the data IPs of the hosts in its NQN
  ACL, so a connection to the volume must come from one of those addresses (the union over
  the volume's hosts; the cluster does not tie each NQN to its own address). The agent reports the
  address it uses toward the pool's cluster on host connect; grants, revokes, migrations and
  host removal update both lists in one conditional update, and new volumes start at
  `ALLOW_NONE`. A host whose data IP changed updates the IP ACLs of its volumes when it
  reconnects.
- QoS follows the offering: a volume moved to an offering with another `lightbitsQosPolicy`
  is moved to that policy on the cluster. A policy named by an offering is checked when a
  volume is created, with the offering detail in the error when the project cannot see it.
- Pool option `qosFromIops=true`: an offering with a max IOPS and no `lightbitsQosPolicy`
  gets the project's IOPS policy with the lowest limit that still gives that max IOPS.
- Offering details `lightbitsCompression` (compression per offering), `lightbitsPlacement`
  (failure domains for the volume, or its primary replica) and `lightbitsUnencrypted` (an
  unencrypted volume on a cluster in hybrid encryption mode). Clones keep their source's
  placement and encryption. Every volume carries the labels `cloudstack-volume`,
  `cloudstack-account` and `cloudstack-pool` (`cloudstack-template` for a cached template).
- Per-volume statistics: CloudStack shows and meters how much of each Lightbits volume holds
  data (the cluster's logical figure, before compression and replication) next to its size.
  A volume with no figure in one round reports its last one: CloudStack drops the figures of
  every volume of a host in a round where one is missing. After a failed read, the volumes of
  the pool report their last figure without asking the cluster for one minute, so a stalled
  API endpoint does not hold up a whole statistics round.
- Host cleanup. When the last Lightbits pool of a KVM host is deleted or put in maintenance,
  the host disconnects from the cluster's NVMe subsystems, but only from a subsystem that
  shows no namespace on that host: a connection with a volume on it is never touched. The next
  attach connects again. Before this the connections stayed until the host rebooted.
- When a host is removed from CloudStack it is taken out of the ACL of every volume of every
  Lightbits pool, so a machine CloudStack no longer tracks does not keep access to a volume.
  This is best effort: a volume whose ACL cannot be updated is logged ("could not remove host
  ... from the ACL of volume ...") and the host removal goes on. Check the management server
  log for that warning and remove such entries on the cluster before treating the host's
  access as revoked.
  Once the removal has started, the host gets no new volume access: a grant for it that
  overlaps the removal either finishes first and is cleaned up, or is refused. A host that
  connects again (its removal did not go through) can be granted access again.
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
- `packaging/install.sh` checks before it changes anything (jar is a plugin build for the
  installed CloudStack line, KVM host prerequisites), installs atomically, and after the restart
  waits until the service reports loading exactly this build. New options `--check`,
  `--no-restart`, `--force`, `--wait`. Unknown arguments are an error (exit 2).
- Messages of the plugin are plain ASCII.
- Revert is refused when the volume was grown after the snapshot was taken (a rollback would
  shrink it) and when the volume is still exposed to a host.
- Builds and passes unit tests against CloudStack 4.23.0.0 (`-Dcloudstack.version=4.23.0.0`) as
  well as 4.22.1.0.

### Known limitations
- `inbandAuth` is one-way: the cluster checks the host, the host does not check the cluster
  (no target secret). nvme-cli `connect-all` cannot take a controller key on its command line,
  and the configuration file needs one entry per cluster port for it.
- With `inbandAuth=true`, NVMe/TCP paths a host made before the option was on hold no key.
  They keep working until they reconnect, and once the cluster admin turns in-band
  authentication on, that reconnect fails. The management log lists such hosts on each host
  connect; put each one in maintenance and reconnect it (INSTALL.md, "In-band
  authentication") before in-band authentication is turned on.
- A host NQN can be a trusted host in only one project of a cluster. With `inbandAuth=true`
  on pools of different projects of the same cluster, the first pool to register a host owns
  its trusted host; the others cannot change it and log that the host is registered
  elsewhere. Their volumes still work, because every pool registers the same per-host key.
- With `inbandAuth=true`, a host whose agent is older than this plugin version reports no key
  and is not registered; the management log names it. Upgrade the agent on every host first.
- With `inbandAuth=true`, a host that was already a trusted host of the project with
  `authRequired=false` keeps that setting: the plugin sets its key but does not change it, so
  the host may still connect without a key while enforcement is on. The management log names
  such hosts; the cluster admin sets `--auth-required=true` when the staged rollout is done.
- Turning `inbandAuth` off leaves the trusted hosts on the cluster and the key on the hosts;
  the cluster admin turns in-band authentication off first, or hosts keep needing their keys.
- With `ipAcl=true`, a host whose agent is older than this plugin version does not report its
  data IP and is refused access to the pool's volumes (the attach fails and names the
  host). Upgrade the agent on every host before turning the option on.
- Turning `ipAcl` off leaves the volumes' IP ACLs as they are (the plugin no longer changes
  them), so a volume then attached on another host is refused by the cluster. Before turning
  it off, have the cluster admin set `--ip-acl ALLOW_ANY` on the pool's volumes.
- CloudStack stores `cmk update storagepool details[0].<option>=...` without asking the
  plugin, so a value other than `true` or `false` for `ipAcl`, `inbandAuth` or `qosFromIops` is not refused
  there. The pool's volume operations then fail, with the option named in the error, until
  the value is corrected.
- With `ipAcl=true`, a volume whose NQN ACL holds a host the plugin has no data IP for (a
  host added by hand on the cluster, or one that has not reconnected since the option was
  turned on) keeps its IP ACL as it is, and the management log says so.
- An offering change that keeps the volume's size and IOPS reaches the plugin only at the
  volume's next attach, VM start or migration (CloudStack does not call the storage plugin for
  it), so the plugin tries the new QoS policy then; a failed change is logged and tried again
  at a later attach or resize. Until that, the volume can keep its old limit
  while CloudStack shows the new offering (a change the plugin could not confirm may already
  be in effect). To apply it at once, detach and attach the
  volume, stop and start (or migrate) the VM, or change the offering together with a size
  increase. INSTALL.md ("QoS policy") has the details.
- Volume statistics pause after a failed read. When the plugin cannot read a volume's
  statistics from the cluster (for example, the Lightbits API does not answer), it stops
  asking the cluster about that pool's volumes for 60 seconds. During that minute a volume
  with an earlier figure reports the last one read, which is older than the round when the
  outage lasts longer. A volume with no earlier figure (such as one created during the
  outage) gets an error, and CloudStack then does not update the figures of any volume on
  that host in that round (it keeps their previous ones). Reads resume on their own after
  the minute. Running VMs and volume I/O are not affected.

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
