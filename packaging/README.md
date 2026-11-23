# Packaging & release

## Install / uninstall

- [`install.sh`](install.sh) detects the node role (management server and/or KVM agent, by the
  presence of `/usr/share/cloudstack-management/lib` and `/usr/share/cloudstack-agent/lib`) and:
  - checks the jar before changing anything: it must be a Lightbits plugin jar built for the
    CloudStack line (major.minor) installed on the node, otherwise the script stops
    (`--force` overrides);
  - on a KVM host checks `nvme-cli`, the `nvme_tcp` kernel module and the host NQN, and stops if
    one is missing;
  - installs the jar atomically and removes any other build of the plugin from `lib/`;
  - restarts the affected service and waits until its log shows that exactly this build was
    loaded, failing with the place to look if it does not.
  - `--check` runs the checks only; `--no-restart` installs without restarting;
    `--wait SECS` sets how long to wait for the service (default 300). Exit status 0 success,
    1 a check or step failed, 2 wrong usage.
- [`uninstall.sh`](uninstall.sh) removes the jar and restarts services (`--no-restart` to skip
  the restart). Volumes on the Lightbits cluster are untouched; it points out NVMe/TCP
  connections the host still holds.

The same jar is installed on the management server and every KVM host (it contains both the
management-side provider/driver and the agent-side `StorageAdaptor`). See [../INSTALL.md](../INSTALL.md).

## Versioning strategy (per CloudStack LTS line)

CloudStack does not keep the storage SPI ABI stable across releases, so the plugin is built and
released **per supported CloudStack LTS line**:

- One release branch per supported LTS (initial GA: **4.22**). `main` tracks the newest.
- The Maven property `cloudstack.version` pins the target; artifacts encode both versions:
  `cloud-plugin-storage-volume-lightbits-<pluginVersion>-cs<csVersion>.jar`.
- `install.sh` refuses a jar built for another CloudStack line than the one installed.
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
