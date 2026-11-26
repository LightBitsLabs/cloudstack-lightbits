# Known limitations and issues

This page lists what to know before and while running the Lightbits CloudStack plugin: the
limitations of the current release, and the issues known in it, each with what to do. It is
kept current between releases; the release notes in [CHANGELOG.md](../CHANGELOG.md) record
what was known at each release.

- [Limitations](#limitations): how the plugin works today, by design or because of the
  platform. Plan for these.
- [Known issues](#known-issues): rare cases seen only in lab tests that inject failures at
  precise moments. Most deployments will never meet them; each has a workaround.

## Limitations

### General

- **KVM only.** Other CloudStack hypervisors are not supported.
- **Resize with the VM stopped, grow only.** CloudStack does not allow online resize of managed
  KVM disks for out-of-tree storage, and Lightbits volumes cannot shrink.
- **Upgrade the agent on every host together with the management server.** A host whose agent
  is older than the management server's plugin does not report what newer options need: with
  `ipAcl=true` its data IP (attaches on it are refused), with `inbandAuth=true` its key (it is
  not registered, or registered one-way for `inbandAuthTwoWay=true`). The management log names
  such hosts.
- **Pool options are not checked when they are set.** CloudStack stores
  `cmk update storagepool details[0].<option>=...` without asking the plugin, so a value other
  than `true` or `false` for `ipAcl`, `inbandAuth` or `qosFromIops` is accepted there. The
  pool's volume operations then fail, naming the option, until the value is corrected.
- **An offering change that keeps the size and IOPS applies its QoS policy later.** CloudStack
  does not call the storage plugin for it, so the new policy takes effect at the volume's next
  attach, VM start or migration. To apply it at once, detach and attach the volume, stop and
  start (or migrate) the VM, or combine the change with a size increase (INSTALL.md, "QoS
  policy").
- **Volume statistics pause for a minute after a failed read.** If the Lightbits API does not
  answer, the plugin stops reading that pool's statistics for 60 seconds and reports the last
  figures; a volume with no earlier figure reports an error for that round. Running VMs and
  volume I/O are not affected.

### Volume IP ACL (`ipAcl=true`)

- **Turning it off leaves the IP ACLs in place.** The plugin stops updating them, so a volume
  attached on another host afterwards is refused. Before turning it off, have the cluster admin
  set `--ip-acl ALLOW_ANY` on the pool's volumes.
- **Hosts the plugin does not know.** A volume whose access list holds a host added by hand on
  the cluster, or a host that has not reconnected since the option was turned on, keeps its IP
  ACL as it is until every host's addresses are known; the management log says so.

### In-band authentication (`inbandAuth=true`, `inbandAuthTwoWay=true`)

Read INSTALL.md ("In-band authentication") before turning on enforcement on the cluster.

- **Reconnect hosts before enforcement.** Paths a host made before the option was on, or before
  its key changed, keep working until they reconnect; once the cluster enforces
  authentication, that reconnect fails. The management log lists such hosts; put each in
  maintenance and reconnect it first.
- **One project per host on a cluster.** A host can be a trusted host in only one project of a
  cluster. With in-band pools in several projects of the same cluster, the first pool owns the
  host's registration and the others report the host not ready; their volumes still work.
- **Existing trusted hosts keep their settings.** A host that was already a trusted host with
  `authRequired=false` keeps it (the management log names it); the cluster admin sets
  `--auth-required=true` when the rollout is done.
- **The host NQN must match.** A host whose `/etc/nvme/hostnqn` no longer matches the NQN
  CloudStack keeps for it is not registered, and the pool reports it not ready, until the two
  are reconciled.
- **Key files are root-only.** The agent refuses a key directory not owned by root and replaces
  a key file owned by another user, or a symbolic link, with a new key; the host is not ready
  until it reconnects.
- **Turning it off** leaves the trusted hosts on the cluster and the keys on the hosts. The
  cluster admin turns enforcement off first, or hosts keep needing their keys.
- **What two-way checks.** Two-way authentication checks the cluster on connections the
  cluster authenticates, that is, once enforcement is on. Like any Linux NVMe/TCP host, the
  host also accepts a target that does not ask for authentication, so two-way complements an
  isolated storage network rather than replacing it (INSTALL.md, "What two-way checks").
- **Two-way for all pools of a cluster, or none.** A one-way pool of the same cluster connects
  with the host key only, and its paths also carry the two-way pool's volumes; the host is then
  reported not ready for the two-way pool.
- **Turning two-way on with running VMs needs a host order.** A migration onto a host whose
  paths do not yet hold its controller key is refused, so follow the host order in INSTALL.md:
  empty one host before turning the option on, and migrate VMs by hand onto moved hosts.

## Known issues

These cases are rare. Each was seen only in lab tests that inject failures at precise moments
(a storage node cut off, a management server or agent stopped in the middle of an operation,
the database failing during a host removal), and none affects the data of volumes in use.

### Volume delete reported failed right after a storage node failure

- **Symptom:** deleting a volume fails after about two minutes, and the volume stays in
  CloudStack in the `Destroy` state.
- **When it can happen:** in the first minutes after a Lightbits node goes down, when the
  cluster takes longer than usual to finish the delete.
- **Impact:** none on other volumes; the cluster completes the delete it accepted.
- **Workaround:** delete the volume again a little later; it completes.

### Unused object left on the cluster after a management server crash

- **Symptom:** a volume shows as `Allocated` in CloudStack and attaching it fails with "Unable
  to find suitable primary storage", or a new snapshot shows as `Error`. After CloudStack
  removes the volume or snapshot, a Lightbits object with its uuid stays on the cluster.
- **When it can happen:** if the management server stops within a second or two of creating a
  new volume for an attach, or of taking a snapshot.
- **Impact:** the object uses capacity on the cluster; nothing else.
- **Workaround:**
  1. Delete the volume in CloudStack and create a new one if it is still needed.
  2. Remove the leftover from the cluster:
     `lbcli delete volume --project-name=<project> --name=<name>` (or `lbcli delete snapshot`).
  Before deleting a `cs-<environment>-p<poolId>-<uuid>` object, check that its uuid is not in
  use: not a volume, not a snapshot, and not a template (the pool's copy of a template is named
  after the template's uuid). A name ending in `-src` belongs to the clone of the same name
  without `-src`, such as a VM's root disk; keep it while that clone exists.

### Host removal not finished after a database failure

- **Symptom:** a removed host is still in the volumes' access lists or in the cluster's trusted
  hosts, and the management log reports a pending revocation that could not be saved.
- **When it can happen:** if the database fails while the host is being removed and the
  management server handling the removal is then lost as well.
- **Workaround:** keep the removed machine fenced and remove its access by hand (INSTALL.md,
  "Removing a host").

### A pool keeps an older key after an agent failure during key rotation

- **Symptom:** after a key rotation, a path of a pool reconnects and is refused.
- **When it can happen:** if the agent fails during a key rotation of another pool of the same
  cluster and project, on a host that connected the pool before the cluster's identity could be
  read.
- **Workaround:** reconnect the host (maintenance, cancel maintenance, reconnect).

### Short waits that defer work to the next host connect

In a few places the plugin waits for a lock and, if another operation holds it too long,
leaves the work to the next host connect and logs it:

- a host connect that waits more than 120 s for the host's in-band lock leaves the key
  registration to the next connect;
- a host removal that waits more than 120 s for the pool's IP ACL lock removes the host's NQN
  from the access lists (which already refuses it) and leaves its address until the volume's
  next attach, migration or host connect;
- a host added back with the NQN of a removed host that waits more than 60 s records the host
  at its next connect; until then volumes cannot be attached to it.

**Workaround:** reconnect the host if the log reports a deferred step.

## Reporting an issue

Open an issue on this repository with the plugin, Lightbits and CloudStack versions and the
relevant lines of the management server and agent logs (remove keys and tokens first).
