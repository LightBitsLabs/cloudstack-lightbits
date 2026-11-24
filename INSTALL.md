# Installing the Lightbits CloudStack plugin

> **Status: alpha, for evaluation only.** Do not use this version in production environments.
> These steps reflect the current standalone (out-of-tree) install model, validated on
> CloudStack 4.22.1.0 (KVM) against LightOS 3.19 and 3.20.

The plugin is a single jar dropped onto an existing CloudStack deployment — no CloudStack
source changes. The **same jar** goes on the management server and every KVM host, because it
contains both the management-side storage provider and the agent-side `StorageAdaptor`.

## Prerequisites

- Apache CloudStack **4.22.x** (KVM), management server + KVM hosts already deployed
- A LightOS cluster reachable from:
  - the **management server** on the API port (`443`)
  - every **KVM host** on NVMe/TCP (`4420`) and discovery (`8009`)
- On each KVM host: `nvme-cli` and the `nvme-tcp` kernel module
  ```bash
  dnf install -y nvme-cli        # or: apt-get install -y nvme-cli
  modprobe nvme-tcp && echo nvme-tcp > /etc/modules-load.d/nvme-tcp.conf
  ```
- A **project-scoped** LightOS JWT (project-admin role on the target **project**) — see
  [Creating a project-scoped JWT](#creating-a-project-scoped-jwt) below. Never store the
  cluster-wide system JWT (the one in `/etc/lbcli/lbcli.yaml` on cluster nodes) as a pool
  credential: the plugin only calls project-scoped routes plus `GET /api/v2/nodes`, so the
  credential each pool holds must be limited to its own project. Cluster-admin access is
  needed exactly once, by the cluster operator, to register the signing credential below —
  it is not what CloudStack stores.

### Creating a project-scoped JWT

Tokens are minted from a key pair whose **public** half is registered on the cluster as a
project **credential** — no cluster secrets are involved, and the cluster never sees the
private key. On any cluster node (or wherever `lbcli` has cluster-admin access):

```bash
# one-time per project: create the project and register a signing credential
lbcli create project --name=<project>
openssl genrsa -out cs-key.pem 2048
openssl rsa -in cs-key.pem -pubout -out cs-pub.pem
lbcli create credential --project-name=<project> --id=cs-cred --type=rsa256pubkey cs-pub.pem

# mint the token (repeat any time — this is also the rotation path, see docs/jwt-rotation.md)
lbcli create jwt --role <project>:admin --key=cs-key.pem --key-id=<project>:cs-cred \
    --issuer=<admin@example.com> --subject=cloudstack \
    --expiry=2027-01-01T00:00:00+00:00
```

Keep `cs-key.pem` safe — anyone holding it can mint tokens for the project. To confirm the
token's scope before registering the pool: it must be able to list the project's volumes
(`GET /api/v2/projects/<project>/volumes` returns 200) and must be **denied** the
cluster-wide route (`GET /api/v2/cluster` returns 403):

```bash
curl -sk -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer <JWT>" \
    https://<api-host>/api/v2/projects/<project>/volumes    # expect 200
curl -sk -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer <JWT>" \
    https://<api-host>/api/v2/cluster                       # expect 403
```

## 1. Install the jar

On the **management server**:
```bash
cp cloud-plugin-storage-volume-lightbits-<version>.jar /usr/share/cloudstack-management/lib/
systemctl restart cloudstack-management
```

On **each KVM host**:
```bash
cp cloud-plugin-storage-volume-lightbits-<version>.jar /usr/share/cloudstack-agent/lib/
systemctl restart cloudstack-agent
```

No configuration files to edit: the management server discovers the provider via its module
scan, and the KVM agent auto-registers the storage adaptor via its package scan. Verify the
provider is present:
```bash
cmk list storageproviders type=primary | grep -i lightbits    # or check the UI provider list
```

## 2. Host NVMe NQN (automatic)

The plugin ACLs each volume to the connecting host's NVMe NQN. The host listener populates
this automatically when a host connects while a Lightbits pool exists: the agent reports
`/etc/nvme/hostnqn` (generating and pinning one via `nvme show-hostnqn` if the file is
missing), and the listener stores it as the `nvme.hostnqn` host detail. Nothing to do —
verify per host if desired:
```bash
cmk list resourcedetails resourcetype=Host resourceid=<hostId> key=nvme.hostnqn
```
If it is missing (e.g. nvme-cli was absent at connect time — the management log carries a
warning), install nvme-cli on the host and reconnect the host.

## 3. Add the primary storage pool

UI: **Infrastructure → Primary Storage → Add Primary Storage**, Provider **Lightbits**.
API/cmk:
```bash
cmk create storagepool name=lightbits-primary provider=Lightbits scope=cluster \
    zoneid=<zone> podid=<pod> clusterid=<cluster> tags=lightbits \
    url="lightbits://<api-host>/<project>?replicas=3&compression=true&environment=<name>&endpoints=<api-host-2>,<api-host-3>&fingerprint=<SHA-256 fingerprint>" \
    details[0].secretkey="<JWT>"
```
- **url** — `lightbits://<api-host>[:443]/<project>` with `?environment=<name>` (required),
  `&fingerprint=<SHA-256 fingerprint>` (needed for a Lightbits cluster, see
  [TLS](#tls-verifying-the-cluster-certificate)) and optional
  `&replicas=1..3&compression=true&qosPolicy=<name>&qosFromIops=true&ipAcl=true&inbandAuth=true&endpoints=<host[:port]>,...`
- **endpoints** — further API endpoints of the same cluster, comma-separated `host[:port]`
  (the port defaults to the pool's API port). List the other nodes so the storage keeps working
  when the node named in the URL is down. Read calls move to the next endpoint on a connection
  failure, a timeout or a 5xx. Calls that change something move on only when the request
  provably never reached the endpoint (connection refused, connect timeout, failed TLS
  handshake); after a 5xx or a read timeout the plugin does not send the change again and
  decides by the state of the object instead. A call that only succeeded through another
  endpoint logs a warning naming the endpoint that failed
  (`Lightbits API ... succeeded via ... after failing via ...`).
- **replicas** — replica factor per volume, `1..3` (default `3`). The replica count must not
  exceed the number of nodes in the Lightbits cluster, so on a **single-node cluster set
  `replicas=1`** (2 nodes → `1..2`, 3+ nodes → `1..3`). Any cluster size is supported; the
  plugin does not require three nodes.
- **environment** — a short identifier for this CloudStack deployment (e.g. `prod-cs1`).
  **Required.** It is embedded in every Lightbits volume name (`cs-<environment>-p<poolId>-<uuid>`) so that
  multiple CloudStacks sharing one cluster never collide and each deployment's volumes are
  easily identifiable in `lbcli`/the cluster UI. **It must be unique per CloudStack deployment
  within a Lightbits project.** Registration is refused when the project already holds
  `cs-<environment>-p<N>-...` objects of a pool this CloudStack does not have, because deleting
  a pool removes every object with its prefix.
- **details[0].secretkey** — the **project-scoped** JWT for `<project>` (see
  [Creating a project-scoped JWT](#creating-a-project-scoped-jwt)). It is stored **encrypted**
  in `storage_pool_details` (chunked, since it exceeds the 255-char column) and is never
  written in clear text or logged by the plugin. **Use this exact name.** CloudStack removes
  parameters named `secretkey` from its API and management logs, and writes any other
  parameter to them in clear text. Earlier versions documented `details[0].jwt`; that name
  still works, but a token supplied that way is readable in
  `/var/log/cloudstack/management/apilog.log` and `management-server.log`. Passing the token
  on a command line also exposes it to shell history and process listings on the machine
  you run `cmk` from. Prefer the UI form, or mitigate (e.g. `HISTCONTROL=ignorespace` with a
  leading space, and run it where untrusted users cannot read `/proc`).
- **discovery** — optional, `&discovery=<host[:port]>,...`: the NVMe discovery endpoints the
  KVM hosts connect through (port 8009 by default). By default the hosts use every API host of
  the pool (the URL host and `endpoints`), which is right when the hosts reach the cluster's
  nodes on the same addresses as the management server. Set it when the data network uses
  other addresses. The hosts receive the list when they connect to the management server, so
  after changing it (`details[0].nvmeDiscovery=...` on an existing pool) restart the agents or
  reconnect the hosts.
- **tags=lightbits** — reference this tag from disk/compute offerings to place volumes on Lightbits.

The pool reports capacity/usage live from the cluster and should show **Up**.

## 4. Use it

Create a disk or compute offering with **storage tag `lightbits`**; volumes and VM root disks
created against it are provisioned as Lightbits volumes (named `cs-<environment>-p<poolId>-<uuid>`),
connected to KVM hosts over NVMe/TCP at attach time.

Volume snapshots (take/revert/delete, create-volume-from-snapshot) map to native Lightbits
snapshots. Volume resize grows the Lightbits volume in place; resize the disk while the VM is
**stopped** — CloudStack does not permit online resize of managed KVM disks for out-of-tree
storage providers, and shrinking is not supported.

**Per-volume replica factor** — override the pool's `replicas` for volumes of a specific
offering with the `lightbitsReplicas` offering detail (integer `1..3`, must not exceed the
cluster's node count):
```bash
# data disks: on a disk offering
cmk create diskoffering name=lb-2replica displaytext="Lightbits 2-replica" \
    storagetype=shared customized=true tags=lightbits details[0].lightbitsReplicas=2
```
Volumes from offerings without the detail use the pool default.

Scope: the override applies to volumes Lightbits creates **fresh** — data disks (and root
disks of ISO-based instances). Volumes materialized as **clones inherit their source's
replica factor** (LightOS requires clone == source, and the count is immutable after
create): template-based root disks inherit the cached template volume (pool default), and
create-volume-from-snapshot inherits the snapshot's base volume. A differing offering value
on those is logged as a warning, not an error. The compute-offering variant
(`serviceofferingdetails[0].lightbitsReplicas`) is honored only for ISO-based root disks.

**Other per-offering options.** Set them the same way as `lightbitsReplicas` (on a disk
offering for data disks, `serviceofferingdetails[...]` on a compute offering for root disks):

| Offering detail | Value | Effect |
|---|---|---|
| `lightbitsCompression` | `true` or `false` | Overrides the pool's `compression` for the volume |
| `lightbitsPlacement` | comma-separated failure domains, up to 25 | A 1-replica volume is placed in one of them; for 2 or 3 replicas the primary replica is. See the note below |
| `lightbitsUnencrypted` | `true` or `false` | `true` creates the volume unencrypted. Only on a cluster that allows unencrypted volumes (hybrid encryption); other clusters reject the create ("UnencryptedVolumesAllowed is not set") |

```bash
cmk create diskoffering name=lb-rack-a displaytext="Lightbits, rack A" storagetype=shared \
    customized=true tags=lightbits details[0].lightbitsReplicas=1 details[1].lightbitsPlacement=fd-rack-a
```

A value that is not valid (for example `lightbitsCompression=yes`) fails the volume creation
with the reason; it is never ignored. As with the replica factor, clones (template-based root
disks and volumes from a snapshot) share their source's data, so they keep the source's
placement and encryption; the detail applies to volumes the cluster creates fresh. For such a
root disk the offering's placement and encryption are not sent at all, so a setting the
cluster would reject for a fresh volume (for example a 1-replica placement while dynamic
rebalance is on) does not fail the deployment. Failure
domains are the ones configured on the cluster's servers (`lbcli list servers`); a server
without configured failure domains is its own failure domain, named after the server.

Placement depends on the cluster's setup:
- For 2 or 3 replicas the restriction applies to the primary replica. A failure domain no
  server is in fails the create ("failed to find a primary node that matches").
- For 1 replica the cluster rejects a restriction while its dynamic rebalance is on
  ("unsupported volume affinity requested while dynamic rebalance is enabled").
- The cluster applies the restriction but does not show it on the volume (`lbcli get volume`
  lists no placement restrictions). Check the volume's primary node instead.

**Labels.** Every volume the plugin creates carries the labels `cloudstack-volume` (the
CloudStack volume UUID), `cloudstack-account` (the owner account UUID) and `cloudstack-pool`
(the pool UUID); a cached template volume carries `cloudstack-template` and `cloudstack-pool`.
They let a cluster admin see who owns a volume. Ownership for cleanup is still decided by the
volume name, never by labels.

**QoS policy.** A volume gets the QoS policy named by its offering's `lightbitsQosPolicy`
detail, else the pool's `qosPolicy`, else the cluster's default. The plugin cannot create
policies: a cluster admin creates them and makes them visible to the pool's project, for
example `lbcli create qos-policy --name=gold --type iops --read-limit 20000 --write-limit 20000
--projects <project>`. A policy name in an offering that the project cannot see fails the
volume creation with the offering detail in the reason.

**QoS from IOPS (pool option `qosFromIops=true`, off by default).** With it, an offering
that sets a max IOPS (fixed, or customized by the user) and names no `lightbitsQosPolicy` gets
the IOPS policy visible to the project with the lowest limit that still gives at least that max
IOPS for reads and for writes (equal limits: the first by name). Unlimited policies,
bandwidth or IOPS-per-GB policies, and policies whose state is not Active (for example
Deprecated) are not candidates. If none fits, the volume creation fails and the error lists the IOPS policies the project can see. Min IOPS has no Lightbits
equivalent and is not used. CloudStack keeps an offering's IOPS only when it is created
with `customizediops=false miniops=<n> maxiops=<n>` (or `customizediops=true` for user-set
IOPS). The cluster may round a policy's limits (on 3.22.1, 1000 was stored as 1024); the
plugin compares against the stored limits. The cluster admin creates the steps, for example:

```bash
lbcli create qos-policy --name=iops-1k --type iops --read-limit 1000 --write-limit 1000 --projects <project>
lbcli create qos-policy --name=iops-5k --type iops --read-limit 5000 --write-limit 5000 --projects <project>
lbcli create qos-policy --name=iops-20k --type iops --read-limit 20000 --write-limit 20000 --projects <project>
```

When a volume moves to another offering (`cmk change offeringforvolume`) whose policy is
different, the plugin moves the volume to that policy. CloudStack hands an offering change to
the plugin only when the size or the IOPS change too; otherwise the new policy is applied at
the volume's next attach (or its VM's next start or migration). An offering that names no
policy, with no IOPS policy picked for it and no pool default (`qosPolicy`), leaves the
volume on the policy it has; otherwise the volume moves to the policy those resolve to (for
example, to the pool default). A failed change is logged ("could not change
the QoS policy of volume ...") and tried again at the next attach or resize. When the
plugin could not confirm the change (the cluster did not answer), the new policy may already
be in effect; check it as shown below.

**Operators: an offering change can leave the old limit in place.** Until one of the events
above, the volume keeps running with its old QoS policy while CloudStack already shows the
new offering. This matters most when moving a volume to a lower tier: it keeps the higher
limit. To apply the new policy at once, use one of these:
- detach the volume and attach it again (a data disk), or stop and start the VM (a root disk);
- migrate the VM to another host;
- change the offering together with a size increase (detached volume, since attached
  volumes cannot be resized online).

Check the policy the cluster applies with `lbcli get volume` (`qosPolicyName`) or in the
management server log ("QoS policy changed from ... to ...").

### Volume IP ACL

**Pool option `ipAcl=true`, off by default.** A Lightbits volume has two access lists: the
host NQNs allowed to connect (the plugin always manages this one) and the IP addresses they
may connect from (`IPAcl`, `ALLOW_ANY` by default). The cluster lets a host see a volume
only when both allow it. With `ipAcl=true` the plugin also manages the IP list:
- On every host connect the agent reports the source address it uses toward the pool's NVMe
  discovery endpoints (what `ip route get <endpoint>` shows as `src`), kept as host detail
  `lightbits.dataips.<pool id>`.
- A volume is created with IP ACL `ALLOW_NONE`. Attach, VM start, migration and detach set
  its IP ACL to the data IPs of the hosts in its NQN ACL, in the same conditional update.
  Removing a host from CloudStack takes its address out with its NQN.
- If a host's data IP changes (re-addressing, another NIC), change it, then reconnect the
  host in CloudStack: the plugin records the new address and updates the IP ACL of the
  pool's volumes that host is in. Until the reconnect the cluster refuses the host's new
  connections to those volumes.
- Every host connect also re-checks the IP ACLs of the volumes the host is in, so a volume
  an earlier update could not reach is put right at the host's next connect, provided the
  plugin knows the data IPs of every host in that volume's NQN ACL. If it does not (a host
  added by hand on the cluster, or one that has not reconnected since `ipAcl=true` was set),
  the volume's IP ACL is left as it is and the management log names the volume's hosts;
  reconnect those hosts, or remove the hand-added NQN. A grant still adds the granted host's
  own addresses to it (removing none), so the host it attaches to is never left out. If the agent
  cannot look up one of the endpoints, it adds what it found to the host's addresses and drops
  none.
- A host reaching the cluster from several addresses (one per NIC) has all of them in its
  volumes' IP ACLs; the management log warns when this happens.
- The IP ACL is the union of the addresses of the volume's hosts, written only when every
  one of them is known: the cluster does not tie each host NQN to its own address. During a
  live migration both hosts are in both lists.

Turn it on for a new pool in the URL (`&ipAcl=true`) or on an existing one:
```bash
cmk update storagepool id=<pool-id> details[0].ipAcl=true
```
then reconnect every host (`cmk reconnect host id=<host-id>`). CloudStack stores the value
without asking the plugin: anything but `true` or `false` makes the pool's volume operations
fail, naming the option, until it is corrected. Each reconnect records the
host's data IP and sets the IP ACL of the volumes it has. Upgrade the agent on every host
first: an older agent reports no data IP, and its host is refused access to the pool's
volumes with an error naming the host. Check a host with
`cmk list resourcedetails resourcetype=Host resourceid=<host-id> key=lightbits.dataips.<pool id>`
and a volume with `lbcli get volume` (`IPAcl`).

To turn it off, first have the cluster admin set `lbcli update volume --ip-acl ALLOW_ANY` on
the pool's volumes, then remove the detail: with the option off the plugin no longer changes
IP ACLs, and a volume left with one host's address refuses every other host.

### In-band authentication

**Pool option `inbandAuth=true`, off by default.** With NVMe in-band authentication
(DH-HMAC-CHAP) turned on, the cluster lets a host open an I/O path only when the host proves
it holds the key registered for it as a trusted host. With `inbandAuth=true` the plugin does
the host side:
- Each KVM host makes its own key the first time it is needed and keeps it in
  `/etc/nvme/lightbits/dhchap-key`, with an nvme-cli configuration file next to it
  (`auth.json`); the directory is 0700 and both files 0600. The agent connects to the pool's
  cluster with `nvme connect-all --config=/etc/nvme/lightbits/auth.json`, so the key never
  appears on a command line. A cluster with in-band authentication off ignores the key.
- On every host connect the agent sends the key to the management server over the agent
  channel. The management server registers the host as a trusted host of the pool's project
  (named `cs-<environment>-host-<host uuid>`, label `cloudstack-host`) with the key as its
  host secret. It keeps only a fingerprint (host detail `lightbits.inband.<pool id>`) and
  never logs the key. A host that is already a trusted host of the project under another
  name gets its key set there.
- Removing a host from CloudStack deletes the trusted host the plugin made for it, best
  effort: if the cluster call fails, the removal still completes and the management log asks
  to delete it by hand (`lbcli delete trusted-host`); until then a machine holding the key
  can still authenticate.
- The discovery service (port 8009) does not authenticate, so discovery is unchanged.
- **Two-way (pool option `inbandAuthTwoWay=true`, with `inbandAuth=true`, off by default).**
  The host also checks the cluster. Each host makes a second key, the controller key
  (`/etc/nvme/lightbits/dhchap-ctrl-key`, same rules as the host key), and the management
  server registers it as the trusted host's target secret (`targetSecretType=Enabled`). The
  agent then connects the pool with its own configuration file
  (`/etc/nvme/lightbits/auth-<pool uuid>.json`), written just before each connect from the
  discovery log page of the endpoint it uses: nvme-cli takes a controller key only per port.
  A path made that way refuses a cluster that cannot prove the key. Without the option the
  authentication is one-way: the cluster checks the host, the host does not check the
  cluster. A trusted host is shared by every pool of its project on the cluster, so a one-way
  pool keeps a target secret a two-way pool set. Readiness for a two-way pool also needs the
  current controller key registered and on every path; move hosts onto new paths as in step
  4 after turning the option on.

**Warning: turning enforcement on too early can freeze a host.** Once the cluster admin runs
`lbcli enable in-band-auth`, any NVMe/TCP path that does not hold the host's current key
(made before `inbandAuth=true`, or before the key was replaced) fails at its next reconnect,
and even a short network blip or controller reset triggers one. A volume with no other
working path then stops: its I/O waits forever, the VMs on it hang, and libvirt and the
CloudStack agent on that host can block, so CloudStack marks the host Down. The plugin
reports the risk ("NOT ready", step 4) but cannot stop the cluster admin from enabling
enforcement. In the lab this happened to a host that still had paths with an old key. To
recover, turn enforcement off (`lbcli disable in-band-auth`) and, on that host, reconnect
the missing paths by hand (`nvme connect -t tcp -a <node data IP> -s 4420 -n <subsystem
NQN>` for each node without a live path); the stuck I/O resumes and the host comes back Up.
Then move it onto keyed paths as in step 4.

Turning in-band authentication on is a cluster-wide switch that only a cluster admin can
flip, and a host without a registered key loses each path at its next reconnect. Follow
this order:
1. The cluster admin turns on the feature flag: `lbcli enable feature-flag in-band-authentication`
   (this only enables the trusted-host API; nothing is enforced yet).
2. Upgrade the plugin on the management server and the agent on every host.
3. Turn the option on, for a new pool in the URL (`&inbandAuth=true`) or on an existing one:
   ```bash
   cmk update storagepool id=<pool-id> details[0].inbandAuth=true
   ```
   then reconnect every host (`cmk reconnect host id=<host-id>`).
4. Watch the management log. Each host connect logs, for each pool with the option, either
   `the <n> host(s) of pool <id> are ready for in-band authentication` or `the hosts of pool
   <id> are NOT ready`, with the hosts that are not registered and those with NVMe/TCP paths
   that do not hold the host's current key: paths made before the option was on hold none,
   and paths made before a key was replaced hold an old one. Move each of those hosts onto keyed
   paths: put it in maintenance (its VMs migrate away), disconnect it from the cluster's
   subsystem (`nvme disconnect -n <subsystem NQN>`, the `nqn.2016-01.com.lightbitslabs:`
   entry of `nvme list-subsys`), and cancel maintenance; its next connects use the key.
5. Enforcement is cluster-wide, and the readiness line covers only the hosts of one pool.
   Before the cluster admin turns it on, every host and client that uses the cluster must be
   ready: the hosts of every CloudStack pool on the cluster (each pool logs its own line,
   including pools of other projects and other CloudStack environments), and every client
   outside CloudStack, which needs its own trusted host and key. A pool whose hosts are
   already trusted hosts of another project cannot register them and reports them NOT
   ready; for those hosts the cluster admin checks the other project's trusted hosts: find
   the host's entry with `lbcli list trusted-hosts --project-name=<other project>` (it must
   have `authRequired=true`), then compare the `hostSecret` of
   `lbcli get trusted-host-secrets --project-name=<other project> --name=<trusted host>`
   with the key in `/etc/nvme/lightbits/dhchap-key` on the host: they must be equal. Then the cluster admin turns enforcement on: `lbcli enable in-band-auth`.

A path that fails authentication shows in the host's kernel log, and nvme-cli reports
"could not add new controller: pre-shared TLS key is missing" for a host with no key (the
wording is nvme-cli's, the cause is DH-HMAC-CHAP). Check a host's registration with
`lbcli list trusted-hosts --project-name=<project>`.

#### Rotating the keys

Rotation is started from CloudStack and needs no shell on the hosts. It replaces both keys of
each host (the host key, and the controller key for two-way) and works with enforcement on.

1. Raise the pool's key generation (it starts at 0):
   ```bash
   cmk update storagepool id=<pool-id> details[0].inbandKeyGeneration=<current + 1>
   ```
2. For each host of the pool in turn: put it in maintenance (its VMs migrate away), then
   cancel maintenance. At that reconnect the agent drops the host's idle Lightbits paths, makes
   new keys, and the management server registers them with every `inbandAuth=true` pool the
   host is in (the keys are per host); the management log says "rotated its in-band
   authentication keys". The host's paths are made again, with the new keys, at the next
   attach or migration.
3. Watch the readiness line: it lists the hosts still on keys older than the pool's key
   generation. A host that reconnects while it still carries volumes (not in maintenance)
   refuses to rotate, keeps its keys, and logs why; it is asked again at its next reconnect.

A rolling rotation of all hosts of a cluster, one at a time:
```bash
for h in $(cmk list hosts clusterid=<cluster-id> type=Routing filter=id | jq -r '.host[].id'); do
  cmk prepare hostformaintenance id=$h && cmk cancel hostmaintenance id=$h
done
```
(check that each host is back Up before the next one, as for any maintenance).

The storage admin starts nothing: a rotation of CloudStack hosts is the CloudStack
operator's step above. To cut a host off at once, the cluster admin deletes its trusted host
(`lbcli delete trusted-host`); with enforcement on it can then no longer connect.

A host whose trusted host belongs to another project (step 5) is not updated by the plugin.
After it rotated (step 2) and before VMs go back to it, the cluster admin sets its new host key
there on **every** Lightbits cluster where the host has such a trusted host (one
`lbcli set trusted-host-secrets` per cluster; add `--target-secret-type=Enabled
--target-secret=...` with the controller key for two-way); a cluster left out refuses the
host's new key. `lbcli set trusted-host-secrets` takes the keys only on its command line (it
has no file or standard-input option), so keep them out of shell history and files, for
example:
```bash
set +o history
K="$(ssh root@<host> cat /etc/nvme/lightbits/dhchap-key)"
lbcli set trusted-host-secrets --project-name=<other project> --name=<trusted host> --host-secret="$K"
unset K; set -o history
```
While that command runs, its arguments, the keys included, are visible to other users of that
machine (`ps`), so run it where only cluster admins can log in.

Do not replace the keys of a host that keeps running VMs by hand (deleting the files under
`/etc/nvme/lightbits`) while enforcement is on: its connected paths keep the old keys, which
the cluster no longer accepts, so their next reconnect fails and a volume with no other path
stops (its I/O waits for a path that never comes back). The rotation above refuses in that
case.

To turn the option off, the cluster admin first turns enforcement off
(`lbcli disable in-band-auth`); then remove the detail. The trusted hosts stay on the
cluster until the admin deletes them.

The plugin creates trusted hosts with `authRequired=true`. If the host was already a trusted
host of the project with `authRequired=false` (the cluster admin's way to let a host connect
without a key during a staged rollout), the plugin sets its key but leaves that setting
alone, and logs the host in a warning and in the readiness line. Such a host may still
connect without a key while enforcement is on; the cluster admin sets
`lbcli update trusted-host --project-name=<project> --name=<name> --auth-required=true` when
it is no longer needed.

A host NQN can be a trusted host in only one project of a cluster. If the host is already a
trusted host of another project, the pool cannot change it and logs that the host is
registered elsewhere; the host still connects, because every pool on the host uses the same
key, as long as that registration holds it.

## TLS: verifying the cluster certificate

The management server always verifies the certificate of the Lightbits API. There is no
"accept any certificate" mode.

**Pin the certificate fingerprint.** A Lightbits cluster's API certificate is issued to the name
`api.service` by a certificate authority internal to the cluster, so validation by CA and host
name of an endpoint addressed by IP cannot pass. Every node of a cluster presents the same
certificate, so one fingerprint covers all endpoints. Read it **on a Lightbits node**, in an SSH
or console session you trust:

```bash
# on a Lightbits node
openssl s_client -connect 127.0.0.1:443 </dev/null 2>/dev/null | openssl x509 -fingerprint -sha256 -noout
# sha256 Fingerprint=CA:B9:B1:57:...:21:67
```

Do not read the fingerprint over the network from the management server and pin whatever comes
back: `openssl s_client` does not verify the peer, so on a network where someone can intercept
the connection that would pin the interceptor's certificate. Running the same command remotely
is fine as a cross-check against the value read on the node.

and pass it as `&fingerprint=<value>` in the pool URL (with or without the colons). With a
fingerprint set the plugin accepts exactly that certificate and nothing else.

**A pool without a fingerprint** is validated against the management server's Java trust store
(CA and host name). Use this only if you replaced the cluster certificate with one issued by a
CA the management server trusts, for the name you use in the pool URL.

**An existing pool** (created with an earlier alpha, or one whose endpoints changed) is updated
in place, with no restart:

```bash
cmk update storagepool id=<pool-id> details[0].tlsFingerprint="<fingerprint>" \
    details[1].apiEndpoints="<api-host-2>,<api-host-3>"
```

After upgrading from `0.1.0-alpha.1`, every pool needs its fingerprint set this way: until
then operations on the pool fail with an error that names `tlsFingerprint`.

**Certificate rotation.** A pin identifies one exact certificate, so when the cluster
certificate is replaced the pin has to follow. Two fingerprints are accepted for this: before
the rotation set `"<current>,<new>"`, after it set `"<new>"`.

**If the fingerprint does not match**, operations on the pool fail with `the Lightbits API
certificate (SHA-256 fingerprint ...) does not match the fingerprint pinned on this storage
pool`. Running VMs are not affected (the NVMe/TCP data path carries no TLS); creating,
attaching, snapshotting and deleting volumes is blocked until the pool is updated.

## Upgrading / uninstalling

- **Upgrade the plugin:** replace the jar on the management server and every KVM host, then
  restart the respective services. No DB schema changes are made by the plugin.
- **After a CloudStack package upgrade:** re-copy the jar (package upgrades replace `lib/`), then
  restart.
- **Uninstall:** put pools into maintenance and delete them, remove the jar from all `lib/`
  directories, and restart the services. A host disconnects from the cluster when its last
  Lightbits pool goes; a host that was down at that moment keeps its NVMe/TCP connections
  until `nvme disconnect -n <subsystem NQN>` is run on it or it reboots (`nvme list-subsys`
  shows them).

## Credential rotation

See [docs/jwt-rotation.md](docs/jwt-rotation.md).
