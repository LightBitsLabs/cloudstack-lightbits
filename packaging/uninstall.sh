#!/usr/bin/env bash
#
# Copyright 2026 Lightbits Labs Ltd.  Apache License 2.0.
#
# Remove the Lightbits CloudStack plugin jar from a CloudStack node and restart the affected
# service(s). Put every Lightbits primary storage pool into maintenance and delete it BEFORE
# running this: CloudStack cannot use, or delete, a pool whose plugin is gone.

set -euo pipefail

MGMT_LIB="${LIGHTBITS_MGMT_LIB:-/usr/share/cloudstack-management/lib}"
AGENT_LIB="${LIGHTBITS_AGENT_LIB:-/usr/share/cloudstack-agent/lib}"
JAR_GLOB='cloud-plugin-storage-volume-lightbits-*.jar'
RESTART=1
removed_any=0
errors=0

usage() {
    cat <<'USAGE'
Usage: sudo ./uninstall.sh [--no-restart]

Options:
  --no-restart   Remove the jar but do not restart CloudStack services. The plugin stays
                 loaded until cloudstack-management / cloudstack-agent are restarted.
  -h, --help     Show this help.

Exit status: 0 success, 1 a step failed, 2 wrong usage.
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-restart) RESTART=0 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERROR: unknown argument '$1'" >&2; usage >&2; exit 2 ;;
    esac
    shift
done
if [[ $EUID -ne 0 ]]; then
    echo "ERROR: run as root (sudo): removing the jar and restarting services needs it" >&2
    exit 1
fi

remove_from() {  # $1=libdir  $2=service
    local lib="$1" svc="$2" jar found=0
    [[ -d "$lib" ]] || return 0
    # shellcheck disable=SC2231
    for jar in "$lib"/$JAR_GLOB; do
        [[ -e "$jar" ]] || continue
        echo ">>> removing $jar"
        rm -f -- "$jar"
        found=1
    done
    [[ $found -eq 1 ]] || return 0
    removed_any=1
    if [[ $RESTART -eq 0 ]]; then
        echo ">>> $svc was NOT restarted (--no-restart): run 'systemctl restart $svc'"
    else
        echo ">>> restarting $svc"
        if ! systemctl restart "$svc"; then
            echo "ERROR: 'systemctl restart $svc' failed; see 'journalctl -u $svc'" >&2
            errors=$((errors + 1))
        fi
    fi
}

remove_from "$AGENT_LIB" cloudstack-agent
remove_from "$MGMT_LIB" cloudstack-management

if [[ $removed_any -eq 1 ]]; then
    echo ">>> uninstalled."
else
    echo ">>> no Lightbits plugin jar found on this node."
fi
echo "NOTE: volumes and snapshots on the Lightbits cluster are not touched by this script."
if [[ -d "$AGENT_LIB" ]] && command -v nvme >/dev/null 2>&1 && nvme list-subsys 2>/dev/null | grep -q 'com\.lightbitslabs'; then
    echo "NOTE: this host is still connected to a Lightbits cluster over NVMe/TCP ('nvme list-subsys')."
    echo "      If nothing on the host uses it any more: nvme disconnect -n <subsystem NQN>"
fi
[[ $errors -eq 0 ]] || exit 1
exit 0
