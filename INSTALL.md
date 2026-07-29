# Installing the Lightbits CloudStack plugin

> **Status: pre-alpha.** These steps reflect the current standalone (out-of-tree) install
> model, validated on CloudStack 4.22.1.0 (KVM) against LightOS 3.19.

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
- A **project-scoped** LightOS JWT (project-admin role on the target **project**). Do **not**
  use the cluster-wide system JWT: the plugin only calls project-scoped routes plus
  `GET /api/v2/nodes`, so the credential each pool stores should be limited to its own
  project. A cluster-admin token still works, but grants far more than the pool needs.

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
    url="lightbits://<api-host>/<project>?replicas=3&compression=true&environment=<name>" \
    details[0].jwt="<JWT>"
```
- **url** — `lightbits://<api-host>[:443]/<project>` with `?environment=<name>` (required) and
  optional `&replicas=1..3&compression=true&qosPolicy=<name>`
- **replicas** — replica factor per volume, `1..3` (default `3`). The replica count must not
  exceed the number of nodes in the Lightbits cluster, so on a **single-node cluster set
  `replicas=1`** (2 nodes → `1..2`, 3+ nodes → `1..3`). Any cluster size is supported; the
  plugin does not require three nodes.
- **environment** — a short identifier for this CloudStack deployment (e.g. `prod-cs1`).
  **Required.** It is embedded in every Lightbits volume name (`cs-<environment>-p<poolId>-<uuid>`) so that
  multiple CloudStacks sharing one cluster never collide and each deployment's volumes are
  easily identifiable in `lbcli`/the cluster UI.
- **details[0].jwt** — the JWT; stored **encrypted** in `storage_pool_details` (chunked, since it
  exceeds the 255-char column). It is never written in cleartext or logged.
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

## TLS note

The management server validates the cluster's API certificate. If your LightOS cluster uses a
self-signed certificate without a Subject Alternative Name for the API endpoint, the JDK HTTP
client will reject it. Use a certificate that carries a SAN for the API address (recommended),
per your LightOS TLS configuration.

## Upgrading / uninstalling

- **Upgrade the plugin:** replace the jar on the management server and every KVM host, then
  restart the respective services. No DB schema changes are made by the plugin.
- **After a CloudStack package upgrade:** re-copy the jar (package upgrades replace `lib/`), then
  restart.
- **Uninstall:** put pools into maintenance and delete them, remove the jar from all `lib/`
  directories, and restart the services.

## Credential rotation

See [docs/jwt-rotation.md](docs/jwt-rotation.md).
