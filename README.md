# cloudstack-lightbits

Apache CloudStack primary storage plugin for [Lightbits](https://www.lightbitslabs.com) NVMe/TCP block storage.

> **Status: pre-alpha, under active development.** Nothing here is ready for production use. APIs, layout, and behavior will change without notice.

## What this is

A CloudStack **managed primary storage** driver that provisions VM disks as Lightbits volumes served over NVMe/TCP — NVMe-class performance on standard TCP/IP networks, no special NICs or switch configuration required. Each CloudStack volume maps 1:1 to a Lightbits volume.

Two communication paths, mirroring our [Proxmox plugin](https://github.com/LightBitsLabs/pve-lightbits):

- **Control path** — the CloudStack management server calls the Lightbits REST/gRPC API (volume lifecycle, snapshots, clones, ACLs, capacity).
- **Data path** — KVM hosts connect to volumes with NVMe/TCP (`nvme-tcp` kernel module + `nvme-cli`) and hand the raw block device to QEMU.

## Planned scope

- KVM hypervisor first
- Volume create/delete/resize, attach/detach via per-host NQN ACLs
- Native Lightbits snapshots; templates via thin clones
- Zone-wide and cluster-wide storage pools
- QoS mapping from CloudStack disk offerings to Lightbits QoS policies

See [docs/architecture.md](docs/architecture.md) for the design sketch.

## Requirements (target)

- Apache CloudStack 4.22+
- Lightbits cluster ≥ 3.x, reachable from management server (API, port 443) and KVM hosts (NVMe/TCP, port 4420)
- KVM hosts: `nvme-cli`, `nvme_tcp` kernel module

## Building

CloudStack does not publish its Maven artifacts to Maven Central; building this plugin requires a locally installed CloudStack source tree:

```bash
git clone --depth 1 --branch 4.22.1.0 https://github.com/apache/cloudstack.git
cd cloudstack && mvn -B -DskipTests install -pl api,core,engine/api,engine/storage,engine/storage/volume,framework/ipc -am
cd ../cloudstack-lightbits && mvn -B compile
```

## Delivery model

This is a **standalone (out-of-tree) plugin**, installed onto existing CloudStack deployments — the same model as [StorPool's CloudStack integration](https://github.com/storpool/storpool-cloudstack-integration) and our own [pve-lightbits](https://github.com/LightBitsLabs/pve-lightbits). The codebase is kept upstream-compatible (Apache 2.0, ASF-clean dependencies, in-tree-compatible layout) so contribution to `apache/cloudstack` remains an option in the future.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE)
