# cloudstack-lightbits

Apache CloudStack primary storage plugin for [Lightbits](https://www.lightbitslabs.com) NVMe/TCP block storage.

> **Status: alpha (`0.1.0-alpha.1`).** This is an alpha version meant for evaluation only. It
> must not be used in production environments or on clusters that hold production data. APIs,
> configuration and behavior may change between alpha releases without a migration path.
> The Lightbits cluster's TLS certificate is verified by fingerprint pinning (see
> [INSTALL.md](INSTALL.md#tls-verifying-the-cluster-certificate)).

![Creating VMs on Lightbits block storage from CloudStack](docs/vm-on-lightbits.gif)

*Registering the Lightbits cluster as primary storage, then deploying VMs whose root disks are Lightbits NVMe/TCP volumes.*

## What this is

A CloudStack **managed primary storage** driver that provisions VM disks as Lightbits volumes served over NVMe/TCP — NVMe-class performance on standard TCP/IP networks, no special NICs or switch configuration required. Each CloudStack volume maps 1:1 to a Lightbits volume.

Two communication paths:

- **Control path** — the CloudStack management server calls the Lightbits REST/gRPC API (volume lifecycle, snapshots, clones, ACLs, capacity).
- **Data path** — KVM hosts connect to volumes with NVMe/TCP (`nvme-tcp` kernel module + `nvme-cli`) and hand the raw block device to QEMU.

## Scope

Working today (KVM):
- Volume create/delete, attach/detach via per-host NQN ACLs (host NQN auto-registered)
- VM root disks from templates via native snapshot+clone (template cached once per pool)
- Volume snapshots: take / revert (native in-place rollback) / delete, and create-volume-from-snapshot
- Volume resize (grow, offline — online resize of managed KVM disks is restricted by CloudStack itself)
- Per-volume replica factor via the `lightbitsReplicas` offering detail
- Per-offering compression (`lightbitsCompression`), failure-domain placement
  (`lightbitsPlacement`) and unencrypted volumes on hybrid-encryption clusters
  (`lightbitsUnencrypted`); every CloudStack volume carries labels naming the volume, its
  account and the pool, and a cached template volume labels naming the template and the pool
- Per-pool compression (`?compression=true`) and QoS policy (`?qosPolicy=<name>`, validated at
  registration), with a per-offering QoS override via the `lightbitsQosPolicy` offering detail
  or, with the pool option `qosFromIops=true`, a policy picked from the offering's max IOPS.
  A changed offering moves the volume to its policy at the next resize, attach, VM start or
  migration; a failed change is logged and tried again at a later one (see INSTALL.md,
  "QoS policy": until then the limit may be the old or the new one)
- Volume IP ACL (pool option `ipAcl=true`): each volume accepts connections only from the
  data IPs of the hosts it is granted to, on top of the host NQN ACL (see INSTALL.md,
  "Volume IP ACL")
- NVMe in-band authentication (pool option `inbandAuth=true`): each host connects with its
  own DH-HMAC-CHAP key. The key is stored only on the host (root-only); the agent sends it to
  the management server, which registers the host with it as a trusted host on the cluster
  and keeps only a fingerprint. The key is never logged. With `inbandAuthTwoWay=true` the
  host also checks the cluster (two-way, with a second, controller key). Keys are rotated from
  CloudStack (pool detail `inbandKeyGeneration`, then a maintenance cycle and a reconnect per host). **Follow the rollout order in
  INSTALL.md ("In-band authentication") before the cluster admin enables enforcement:**
  enabling it while a host still has paths without its current key can freeze that host's VMs
- Multi-tenancy: pools authenticate with **project-scoped JWTs** (never the system JWT), and
  cluster-side cleanup only ever touches the pool's own objects. Several pools can share one
  Lightbits project **operationally** (a per-pool volume-name prefix keeps names, stats, and
  cleanup collision-free) — but that is not a security boundary: a pool's JWT can read every
  volume in its project, so tenant-level isolation requires **one Lightbits project per pool**
- VM live migration between KVM hosts (the destination host's NQN is added to each volume's
  ACL during migration prepare and the source's is pruned after), including host maintenance
  evacuation
- Cluster-wide and zone-wide storage pools; full cluster-side cleanup on teardown

See [docs/architecture.md](docs/architecture.md) for the design sketch.

## Installing

See **[INSTALL.md](INSTALL.md)** for the full deployment guide (management server + KVM hosts,
pool registration, storage tags) and **[docs/jwt-rotation.md](docs/jwt-rotation.md)** for
credential rotation.

## Requirements (target)

- Apache CloudStack 4.22+
- Lightbits cluster running LightOS ≥ 3.x, reachable from the management server (API, port 443) and KVM hosts (NVMe/TCP, port 4420). Any cluster size works — from a single node (use `replicas=1`) up to a full multi-node cluster; three nodes are **not** required.
- A **project-scoped** LightOS JWT per storage pool (`<project>:admin` role) — **not** the cluster-wide system JWT. Minting instructions: [INSTALL.md](INSTALL.md#creating-a-project-scoped-jwt).
- KVM hosts: `nvme-cli`, `nvme_tcp` kernel module

## Building

CloudStack does not publish its Maven artifacts to Maven Central; building this plugin requires a locally installed CloudStack source tree:

```bash
git clone --depth 1 --branch 4.22.1.0 https://github.com/apache/cloudstack.git
cd cloudstack && mvn -B -DskipTests install -pl api,core,engine/api,engine/storage,engine/storage/volume,framework/ipc,plugins/hypervisors/kvm -am
cd ../cloudstack-lightbits && mvn -B verify   # compiles, runs the unit tests, enforces coverage
```

## Testing

Unit tests live under `src/test/java` and run with the standard Maven lifecycle — no
CloudStack management server or Lightbits cluster required (the API is exercised against an
in-process [WireMock](https://wiremock.org/) stub):

```bash
mvn -B test            # run the unit tests
mvn -B verify          # tests + JaCoCo line-coverage gate (fails under 60%)
```

Coverage report after a run: `target/site/jacoco/index.html`.

**Contributors are encouraged to extend these.** The suite is organized by unit:

| Test | Covers |
|---|---|
| `client/LightbitsApiClientTest` | volume/snapshot lifecycle, idempotency, error mapping, retry/backoff |
| `client/LightbitsApiClientTlsTest` | TLS certificate validation (self-signed rejected; no silent bypass) |
| `util/LightbitsPoolConfigTest` | pool-URL parsing/validation, JWT-never-persisted contract |
| `lifecycle/LightbitsLifeCyclePreflightTest` | pool preflight against a stubbed cluster |

When adding an API call to `LightbitsApiClient`, add a WireMock-backed test alongside it (happy
path **and** the failure it must handle). The coverage gate covers the `client`, `util`, and
`model` packages; the CloudStack-coupled SPI classes are validated by integration/Marvin tests.

## Delivery model

This is a **standalone (out-of-tree) plugin**, installed onto existing CloudStack deployments. The codebase is kept upstream-compatible (Apache 2.0, ASF-clean dependencies, in-tree-compatible layout) so contribution to `apache/cloudstack` remains an option in the future.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE)
