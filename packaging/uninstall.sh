#!/usr/bin/env bash
#
# Copyright 2026 Lightbits Labs Ltd.  Apache License 2.0.
#
# Remove the Lightbits CloudStack plugin jar from a CloudStack node and restart
# the affected service(s). Put any Lightbits primary storage pools into
# maintenance and delete them BEFORE running this.
#
# Usage:  sudo ./uninstall.sh
set -euo pipefail

MGMT_LIB=/usr/share/cloudstack-management/lib
AGENT_LIB=/usr/share/cloudstack-agent/lib
removed_any=0

remove_from() {  # $1=libdir  $2=service
    local lib="$1" svc="$2"
    [[ -d "$lib" ]] || return 0
    if ls "$lib"/cloud-plugin-storage-volume-lightbits-*.jar >/dev/null 2>&1; then
        echo ">>> removing plugin from $lib"
        rm -f "$lib"/cloud-plugin-storage-volume-lightbits-*.jar
        echo ">>> restarting $svc"
        systemctl restart "$svc"
        removed_any=1
    fi
}

remove_from "$MGMT_LIB" cloudstack-management
remove_from "$AGENT_LIB" cloudstack-agent

[[ "$removed_any" -eq 1 ]] && echo ">>> uninstalled." || echo ">>> no Lightbits plugin jar found."
echo "NOTE: existing Lightbits volumes on the cluster are NOT touched by this script."
