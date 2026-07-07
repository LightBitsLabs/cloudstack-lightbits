#!/usr/bin/env bash
#
# Copyright 2026 Lightbits Labs Ltd.  Apache License 2.0.
#
# Install the Lightbits CloudStack plugin jar onto a CloudStack node.
# Detects the role (management server and/or KVM agent) by which packages/lib
# dirs are present, installs the jar, and restarts the affected service(s).
#
# Usage:  sudo ./install.sh [path-to-plugin.jar]
#         (defaults to the newest cloud-plugin-storage-volume-lightbits-*.jar next to this script)
set -euo pipefail

JAR="${1:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
if [[ -z "$JAR" ]]; then
    JAR="$(ls -1t "$HERE"/../target/cloud-plugin-storage-volume-lightbits-*.jar "$HERE"/cloud-plugin-storage-volume-lightbits-*.jar 2>/dev/null | head -1 || true)"
fi
[[ -n "$JAR" && -f "$JAR" ]] || { echo "ERROR: plugin jar not found; pass it as the first argument"; exit 1; }
echo ">>> plugin jar: $JAR"

MGMT_LIB=/usr/share/cloudstack-management/lib
AGENT_LIB=/usr/share/cloudstack-agent/lib
installed_any=0

install_to() {  # $1=libdir  $2=service
    local lib="$1" svc="$2"
    [[ -d "$lib" ]] || return 0
    echo ">>> installing to $lib (role: $svc)"
    # remove any older plugin jar to avoid duplicates on the classpath
    rm -f "$lib"/cloud-plugin-storage-volume-lightbits-*.jar
    cp "$JAR" "$lib/"
    echo ">>> restarting $svc"
    systemctl restart "$svc"
    installed_any=1
}

install_to "$MGMT_LIB" cloudstack-management
install_to "$AGENT_LIB" cloudstack-agent

if [[ "$installed_any" -eq 0 ]]; then
    echo "ERROR: neither $MGMT_LIB nor $AGENT_LIB found — is CloudStack installed on this node?"
    exit 1
fi

if [[ -d "$AGENT_LIB" ]]; then
    echo ">>> checking KVM host NVMe/TCP prerequisites"
    command -v nvme >/dev/null || echo "WARN: nvme-cli not found; install it (dnf/apt install nvme-cli)"
    modprobe nvme-tcp 2>/dev/null && echo "nvme_tcp: loaded" || echo "WARN: could not load nvme_tcp module"
    [[ -f /etc/nvme/hostnqn ]] || { nvme gen-hostnqn > /etc/nvme/hostnqn 2>/dev/null && echo "generated /etc/nvme/hostnqn"; }
    echo "    host NQN: $(cat /etc/nvme/hostnqn 2>/dev/null || echo '(unavailable)')"
    echo "    (auto-registered as the 'nvme.hostnqn' host detail on host connect)"
fi

echo ">>> done. Register a pool with provider=Lightbits (see INSTALL.md)."
