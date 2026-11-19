# Rotating the Lightbits JWT

The Lightbits API credential is a **project-scoped** JWT supplied when the primary storage
pool is registered (see "Creating a project-scoped JWT" in [INSTALL.md](../INSTALL.md) —
never the cluster-wide system JWT). It is stored **encrypted** in CloudStack's
`storage_pool_details` (chunked across rows because it exceeds the 255-char column) and is
never written in cleartext or logged. Tokens expire, so you will need to roll them.

## How the plugin uses the JWT

- Supplied at pool registration (`details[0].secretkey=...`), encrypted at rest, and replaced
  in place with `updateStoragePool` (below).
- Read (decrypted) by the management-side driver for every control-plane call to the cluster
  (volume create/delete/resize, snapshots, ACL updates, capacity via `GET /api/v2/nodes`).
- **Not** used on the KVM hosts — the data path is NVMe/TCP and needs no JWT. So rotation is a
  management-server-only operation; no host changes, no VM disruption.

## When to rotate

- Before the token's `exp` — check it with `lbcli parse jwt --jwt-file=/path/to/jwt` (prints
  header + payload, including `exp`; the file form keeps the token out of argv/shell history).
  Off-cluster without lbcli, mind that JWT payloads are unpadded **base64url** (plain
  `base64 -d` can choke on missing padding and `-`/`_`) — read from the file, not argv:
  `python3 -c 'import base64,json,sys;p=sys.stdin.read().split(".")[1];print(json.loads(base64.urlsafe_b64decode(p+"="*(-len(p)%4)))["exp"])' < /path/to/jwt`
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

2. **Update the pool credential.** A CloudStack pool detail holds 255 characters and a token
   is longer, so the token is passed in numbered pieces of up to 250 characters:
   ```bash
   T=$(cat /path/to/new.jwt)
   cmk update storagepool id=<poolId> \
       details[0].1secretkey="${T:0:250}" details[1].2secretkey="${T:250:250}" \
       details[2].3secretkey="${T:500:250}"
   ```
   (add `details[3].4secretkey="${T:750:250}"` for a token longer than 750 characters; empty
   pieces are harmless). CloudStack writes the pieces into `storage_pool_details` as given;
   the plugin picks them up on the next use of the pool (at the latest the next capacity
   poll, about a minute), joins, encrypts and stores the token and removes the clear-text
   rows. No restart is needed. Use these exact names: CloudStack keeps parameters whose name
   ends in `secretkey` out of its logs, while a token passed as `details[0].jwt` is written
   to `apilog.log` and `management-server.log` in clear text (and cut at 255 characters).

   Reading the token from a file as above keeps it out of the shell history; it is still
   visible in the process list while `cmk` runs.

   A value that is not a JWT (for example a token cut short) is rejected and removed, and the
   previous token stays in use. A JWT the cluster does not accept makes operations on the
   pool fail with `The cluster rejected the storage pool's API token ...` until a valid one
   is set; running VMs are not affected.

3. **Verify** the new token works: trigger a control-plane op (e.g. list/refresh the pool, or
   create+delete a small test volume) and confirm success and that pool capacity still reports.

4. **Retire the old JWT.** LightOS JWTs are stateless bearer tokens validated by signature —
   there is **no per-token revocation**: the old token stays technically valid until its
   `exp`. Issue short-lived tokens so this window is small. If the old token may have been
   **exposed**, expiry is not enough — rotate the signing credential instead (next section),
   which immediately invalidates every token signed with that key.

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
