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
| `lifecycle/LightbitsLifeCyclePreflightTest` | pool preflight against a stubbed cluster (health/auth/params) |

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

- pool up + capacity from the cluster
- volume create → attach (NVMe/TCP) → detach → delete; the backing `cs-<environment>-<uuid>`
  volume is verified on the cluster
- resize
- rainy day: bad credentials rejected, missing `environment` rejected
- **leak checker**: no orphaned `cs-<environment>-*` volumes left on the cluster

```bash
export LB_API_ENDPOINT=<host> LB_JWT=<jwt> LB_PROJECT=default LB_ENVIRONMENT=ci-cs1
nosetests --with-marvin --marvin-config=<zone>.cfg test/integration/plugin/test_lightbits_volumes.py
```

## 4. Web-UI validation (Playwright, internal repo)

`testing/scripts/ui-validation.py` drives the CloudStack UI headless (FHD), labels each step
(banner + Lightbits-context + golden halo), and renders a review video. See the internal repo
`testing/ui-validation/LABELS.md`.

## Quality gate

A milestone advances only when the level-appropriate suites are green (sunny + rainy),
coverage ≥60%, and security checks (TLS on the wire, JWT encrypted at rest) pass.
