# Testing

The plugin is validated at three levels — unit, component, and end-to-end — covering both
sunny-day and rainy-day scenarios. Line coverage of the pure-logic packages is gated at ≥60%.

## 1. Unit tests (`src/test/java`, run by `mvn verify`)

No CloudStack server or Lightbits cluster needed — the Lightbits API is stubbed with WireMock,
and TLS/JWT behavior is exercised directly.

| Suite | Covers |
|---|---|
| `client/LightbitsApiClientTest` | volume/snapshot CRUD, idempotency (409/404), typed error mapping, retry/backoff |
| `client/LightbitsApiClientTlsTest` | TLS cert validation — self-signed rejected; dev bypass still enforces hostname verification |
| `util/LightbitsPoolConfigTest` | pool-URL parse/validate, required `environment`, JWT chunking/never-persisted-plaintext |
| `lifecycle/LightbitsLifeCyclePreflightTest` | pool preflight against a stubbed cluster (project access via project-scoped token, node capacity, replica check, QoS validation, params) |

```bash
mvn -B verify        # compile + unit tests + JaCoCo coverage gate (fails < 60%)
```

## 2. Component tests (live plugin against real subsystems)

Exercise a real component in its real runtime with faults injected at its boundary. These are
the scripted checks used during bring-up (see the internal repo `testing/scripts/`):

- **Management side** — plugin jar in an unmodified management server: provider discovery, pool
  register (against a real cluster API), volume create/delete round-trip. Fault: cluster
  unreachable, management-server restart mid-create (idempotent recovery).
- **Agent side** — `LightbitsStorageAdaptor` on a real KVM host + cluster: `nvme connect`,
  device resolution by NGUID, ACL grant/revoke, attach to a running VM. Fault: device-never-
  appears, duplicate connect, detach-while-in-use, SELinux enforcing.
- **Data integrity** — checksummed content (fio/md5) after attach; part of the definition of
  done for every data-path feature.

## 3. End-to-end tests (Marvin, `test/integration/plugin/`)

Full user-visible flows on a Tier-2 lab (KVM host(s) + Lightbits cluster), structured to be
upstream-submittable. `test_lightbits_volumes.py` covers:

- pool up + capacity from the cluster (the suite registers its OWN pool with storage tag
  `lb-ci` and its own environment, so it never interferes with other pools on the cluster)
- volume create → attach (NVMe/TCP) → detach → delete; the backing
  `cs-<environment>-p<poolId>-<uuid>` volume is verified on the cluster, and verified GONE
  after delete
- resize (attach-first so the managed volume is materialized, then offline grow)
- rainy day: bad credentials rejected, missing `environment` rejected
- **leak checker**: every `cs-<environment>-p<poolId>-*` cluster volume maps to a live
  CloudStack volume or cached template

### Installing Marvin (once per management server)

Marvin's `cloudstackAPI` bindings are generated, not shipped. Generating them from the live
management server guarantees they match the deployed version:

```bash
# 1. enable the integration API port (dynamic setting) and confirm it answers
cmk update configuration name=integration.api.port value=8096   # then restart mgmt once
# 2. from the CloudStack source tree, copy tools/marvin to the mgmt server, then:
cd marvin && python3 marvin/codegenerator.py -e localhost -o marvin && pip3 install .
# 3. python3.9 dependency pins (newer pyvmomi requires >=3.10; system oauthlib too old):
pip3 install "pyvmomi<9" -U oauthlib requests-oauthlib
```

Note: the stock codegenerator crashes on 4.22's live `listApis` output (empty response
entries); it needs a small patch to skip nameless entries at all three sites.

### Environment prerequisites

- `wait.detach.device` global setting ≥ `60000`. CloudStack reports a VM Running when qemu
  starts, but a virtio disk unplug is only ACKed once the guest kernel is fully booted — with
  the default 10s window, detach-after-attach on a freshly deployed VM fails ("device busy").
  The suite additionally waits `LB_GUEST_SETTLE_SECS` (default 90) after deploying a VM.

### Running

```bash
export LB_API_ENDPOINT=<cluster-api-host> LB_JWT=<project-scoped-jwt> LB_PROJECT=default LB_ENVIRONMENT=ci-cs1
nosetests -v --with-marvin --marvin-config=<mgmt>.cfg --zone=<zone> --hypervisor=kvm \
    test/integration/plugin/test_lightbits_volumes.py
```

Minimal marvin config (`<mgmt>.cfg`): `mgtSvr` (localhost:8096), `dbSvr` (cloud/cloud), and
`zones: [{"name": "<zone>"}]`. Results land under the configured `logger.LogFolderPath`.

## 4. Web-UI validation (Playwright, internal repo)

`testing/scripts/ui-validation.py` drives the CloudStack UI headless (FHD), labels each step
(banner + Lightbits-context + golden halo), and renders a review video. See the internal repo
`testing/ui-validation/LABELS.md`.

## Quality gate

A milestone advances only when the level-appropriate suites are green (sunny + rainy),
coverage ≥60%, and security checks (TLS on the wire, JWT encrypted at rest) pass.
