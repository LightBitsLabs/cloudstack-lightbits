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
  `&replicas=1..3&compression=true&qosPolicy=<name>&endpoints=<host[:port]>,...`
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
  easily identifiable in `lbcli`/the cluster UI.
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
  directories, and restart the services.

## Credential rotation

See [docs/jwt-rotation.md](docs/jwt-rotation.md).
