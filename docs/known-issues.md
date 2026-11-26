# Known issues

This page lists issues known in the current release of the Lightbits CloudStack plugin, with a
workaround for each. It is updated when an issue is found or fixed; the release notes in
[CHANGELOG.md](../CHANGELOG.md) list the issues known at each release.

The issues below are rare. Each was seen only in lab tests that inject failures at precise
moments (a storage node cut off, a management server killed in the middle of an operation),
and none affects the data of volumes in use.

## Volume delete reported failed right after a storage node failure

- **Symptom:** deleting a volume fails after about two minutes, and the volume stays in
  CloudStack in the `Destroy` state.
- **When it can happen:** only in the first minutes after a Lightbits node goes down, when the
  cluster takes longer than usual to finish the delete.
- **Impact:** none on other volumes; the cluster completes the delete it accepted.
- **Workaround:** delete the volume again a little later; it completes.
- **Affects:** 0.1.0 and later. Seen with Lightbits 3.22.1.

## Unused object left on the cluster after a management server crash

- **Symptom:** a volume shows as `Allocated` in CloudStack and attaching it fails with "Unable
  to find suitable primary storage", or a new snapshot shows as `Error`. A Lightbits volume or
  snapshot with that volume's or snapshot's uuid stays on the cluster after CloudStack removes
  the volume or snapshot.
- **When it can happen:** only if the management server stops within a second or two of
  creating a new volume for an attach, or of taking a snapshot.
- **Impact:** the object uses capacity on the cluster; nothing else.
- **Workaround:**
  1. Delete the volume in CloudStack and create a new one if it is still needed.
  2. Remove the leftover from the cluster:
     `lbcli delete volume --project-name=<project> --name=<name>` (or `lbcli delete snapshot`).
  Before deleting a `cs-<environment>-p<poolId>-<uuid>` object, check that its uuid is not in
  use: not a volume, not a snapshot, and not a template (the pool's copy of a template is
  named after the template's uuid). A name ending in `-src` belongs to the clone of the same
  name without `-src`, such as a VM's root disk; keep it while that clone exists.
- **Affects:** 0.1.0 and later; the crash window is in CloudStack's own volume and snapshot
  handling.

## Reporting an issue

Open an issue on this repository with the plugin and Lightbits versions, the CloudStack
version, and the relevant lines of the management server and agent logs (remove keys and
tokens first).
