# Rotating the Lightbits JWT

The Lightbits API credential is a JWT supplied when the primary storage pool is registered.
It is stored **encrypted** in CloudStack's `storage_pool_details` (chunked across rows because
it exceeds the 255-char column) and is never written in cleartext or logged. Tokens expire, so
you will need to roll them.

## How the plugin uses the JWT

- Supplied once at pool registration (`details[0].jwt=...`), encrypted at rest.
- Read (decrypted) by the management-side driver for every control-plane call to the cluster
  (volume create/delete/resize, snapshots, ACL updates, capacity/health).
- **Not** used on the KVM hosts — the data path is NVMe/TCP and needs no JWT. So rotation is a
  management-server-only operation; no host changes, no VM disruption.

## When to rotate

- Before the token's `exp` — check it: `echo <jwt> | cut -d. -f2 | base64 -d | jq .exp`
- On suspected credential compromise
- On a policy-driven schedule (recommended: issue shorter-lived tokens and rotate regularly
  rather than using a long-lived cluster-admin token)

## Rotating without downtime

Rotation only changes a stored credential; **in-flight and future control-plane operations pick
up the new token immediately** on the next call. Running VMs and their NVMe/TCP connections are
unaffected (no JWT on the data path).

1. **Mint a new JWT** on the LightOS cluster with the same project scope (volume-management
   permission on the pool's project). Keep the old token valid until step 3 succeeds.

2. **Update the pool credential.** The plugin re-encrypts and re-chunks the JWT into
   `storage_pool_details`:
   ```bash
   cmk update storagepool id=<poolId> details[0].jwt="<NEW_JWT>"
   ```
   > If `updateStoragePool` does not accept a new JWT in your build yet, use the fallback below.

   **Fallback (until in-place update lands):** re-register the pool with the new token —
   put it in maintenance, delete, and re-create with the same `url` + new `details[0].jwt`.
   This does not touch existing Lightbits volumes (they persist on the cluster); it only
   refreshes CloudStack's stored credential. Schedule it in a maintenance window.

3. **Verify** the new token works: trigger a control-plane op (e.g. list/refresh the pool, or
   create+delete a small test volume) and confirm success and that pool capacity still reports.

4. **Revoke the old JWT** on the cluster once step 3 is confirmed.

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
