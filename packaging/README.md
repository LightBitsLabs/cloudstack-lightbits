# Packaging & release

## Install / uninstall

- [`install.sh`](install.sh) — detects the node role (management server and/or KVM agent by the
  presence of `/usr/share/cloudstack-management/lib` and `/usr/share/cloudstack-agent/lib`),
  installs the plugin jar (removing any older copy), restarts the affected service(s), and on
  KVM hosts checks the NVMe/TCP prerequisites (`nvme-cli`, `nvme_tcp`, host NQN).
- [`uninstall.sh`](uninstall.sh) — removes the jar and restarts services. Existing Lightbits
  volumes on the cluster are untouched.

The same jar is installed on the management server and every KVM host (it contains both the
management-side provider/driver and the agent-side `StorageAdaptor`). See [../INSTALL.md](../INSTALL.md).

## Versioning strategy (per CloudStack LTS line)

CloudStack does not keep the storage SPI ABI stable across releases, so the plugin is built and
released **per supported CloudStack LTS line**:

- One release branch per supported LTS (initial GA: **4.22**). `main` tracks the newest.
- The Maven property `cloudstack.version` pins the target; artifacts encode both versions:
  `cloud-plugin-storage-volume-lightbits-<pluginVersion>-cs<csVersion>.jar`.
- `install.sh` refuses a version mismatch against the detected CloudStack (planned check).
- Adding a new CloudStack line = a new build profile + CI matrix leg + a Tier-2 validation
  pass — not a fork.

## Upgrade / CloudStack-upgrade interaction

- **Plugin upgrade:** re-run `install.sh` with the new jar; no DB schema changes (all state lives
  in `storage_pool_details` / `host_details`), so no migrations.
- **After a CloudStack package upgrade:** the package replaces `lib/`, so re-run `install.sh`.

## GA release gate (M4)

- Signed release with checksums; `1.0.0` = GA.
- Security scans clean (dependency + secrets); see the internal repo open-items.
- **Clean-room install:** on a fresh Tier-2 environment, install using only the public release
  artifacts + docs (by someone who didn't write the installer), then the full Marvin suite green.
