# Rotating the Lightbits JWT

The Lightbits API credential is a **project-scoped** JWT supplied when the primary storage
pool is registered (see "Creating a project-scoped JWT" in [INSTALL.md](../INSTALL.md) —
never the cluster-wide system JWT). It is stored **encrypted** in CloudStack's
`storage_pool_details` (chunked across rows because it exceeds the 255-char column) and is
never written in cleartext or logged. Tokens expire, so you will need to roll them.

## How the plugin uses the JWT

- Supplied once at pool registration (`details[0].jwt=...`), encrypted at rest.
- Read (decrypted) by the management-side driver for every control-plane call to the cluster
  (volume create/delete/resize, snapshots, ACL updates, capacity via `GET /api/v2/nodes`).
- **Not** used on the KVM hosts — the data path is NVMe/TCP and needs no JWT. So rotation is a
  management-server-only operation; no host changes, no VM disruption.

## When to rotate

- Before the token's `exp` — check it with `lbcli parse jwt -J "<jwt>"` (prints header +
  payload, including `exp`). Off-cluster without lbcli, mind that JWT payloads are unpadded
  **base64url** (plain `base64 -d` can choke on missing padding and `-`/`_`):
  `python3 -c 'import base64,json,sys;p=sys.argv[1].split(".")[1];print(json.loads(base64.urlsafe_b64decode(p+"="*(-len(p)%4)))["exp"])' "<jwt>"`
- On suspected **token** compromise — and if the signing **private key** may be compromised,
  token renewal is not enough: see "Signing-key compromise" below
- On a policy-driven schedule (recommended: issue shorter-lived project-scoped tokens and
  rotate regularly rather than relying on one long-lived token)

## Rotating without downtime

Rotation only changes a stored credential; **in-flight and future control-plane operations pick
up the new token immediately** on the next call. Running VMs and their NVMe/TCP connections are
unaffected (no JWT on the data path).

1. **Mint a new JWT** with the same project scope (`<project>:admin` role on the pool's
   project). If the signing credential from the initial setup still exists on the cluster,
   this is a single `lbcli create jwt` with the same key pair and needs no cluster-side
   changes (full flow: "Creating a project-scoped JWT" in [INSTALL.md](../INSTALL.md)).
   Keep the old token valid until step 3 succeeds.

2. **Update the pool credential.** The plugin re-encrypts and re-chunks the JWT into
   `storage_pool_details`:
   ```bash
   cmk update storagepool id=<poolId> details[0].jwt="<NEW_JWT>"
   ```
   > If `updateStoragePool` does not accept a new JWT in your build yet, use the fallback below.

   **Fallback (until in-place update lands): only safe for a pool with no volumes.**
   Re-register the pool with the new token — put it in maintenance, delete, and re-create
   with the same `url` + new `details[0].jwt`. ⚠️ Volume names embed the CloudStack pool id
   (`cs-<environment>-p<poolId>-<uuid>`), and a re-created pool gets a **new** id: any
   volumes created under the old pool would become unreachable by name, and pool deletion
   sweeps the old pool's template caches. CloudStack refuses to delete a pool that still
   has user volumes, which enforces the empty-pool constraint. For a pool with data, the
   in-place `updateStoragePool` path is the only rotation route.

3. **Verify** the new token works: trigger a control-plane op (e.g. list/refresh the pool, or
   create+delete a small test volume) and confirm success and that pool capacity still reports.

4. **Revoke the old JWT** on the cluster once step 3 is confirmed.

## Signing-key compromise (credential rotation, not just token renewal)

Renewing the JWT with the **same key pair** does nothing if the private key itself leaked —
the holder can keep minting valid tokens. In that case rotate the **credential**:

1. Register a fresh key pair as a NEW credential (new `--id`) on the project — same flow as
   the initial setup in INSTALL.md.
2. Mint a new JWT signed with the new key (`--key-id=<project>:<new-cred-id>`). The pool
   accepts it via the same `updateStoragePool` step above: the cluster validates a JWT
   against whichever registered credential its `kid` names, so a token from a newly
   registered credential works without any plugin-side change.
3. **Delete the compromised credential**: `lbcli delete credential
   --project-name=<project> --id=<old-cred-id>`. This immediately invalidates every token
   signed with the old key, so do it only after step 2 is verified.

## Verifying the stored credential is encrypted

The value must be ciphertext, never the `eyJ...` token:
```bash
mysql -u cloud -p cloud -e \
  "SELECT name, LEFT(value,12) FROM storage_pool_details \
   WHERE pool_id=<poolId> AND name LIKE 'jwt%';"
# expect: jwt=<chunk count>, jwt.0/jwt.1/... = base64 ciphertext (not eyJ...)
```

## Notes / roadmap

- **Per-environment scoping:** when multiple CloudStacks share a cluster, give each its own JWT
  (and `environment` name); rotating one does not affect the others.
- **Expiry handling:** an expired token surfaces as `AUTH` errors on control-plane calls; the
  data path (running VMs) keeps working. Rotate promptly to restore management operations.
- Automated, non-disruptive in-place rotation via `updateStoragePool` is tracked for a future
  release; the re-register fallback is always available.
