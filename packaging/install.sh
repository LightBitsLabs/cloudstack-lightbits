#!/usr/bin/env bash
#
# Copyright 2026 Lightbits Labs Ltd.  Apache License 2.0.
#
# Install the Lightbits CloudStack plugin jar on a CloudStack node.
#
# The role of the node (management server, KVM host, or both) is detected from the CloudStack
# library directories that exist. The jar is checked before anything is changed, installed
# atomically, the affected service is restarted, and the script then waits until the service
# reports that it loaded exactly this build.
#
# Run it on the management server and on every KVM host, with the same jar.

set -euo pipefail

MGMT_LIB="${LIGHTBITS_MGMT_LIB:-/usr/share/cloudstack-management/lib}"
AGENT_LIB="${LIGHTBITS_AGENT_LIB:-/usr/share/cloudstack-agent/lib}"
MGMT_LOG="${LIGHTBITS_MGMT_LOG:-/var/log/cloudstack/management/management-server.log}"
AGENT_LOG="${LIGHTBITS_AGENT_LOG:-/var/log/cloudstack/agent/agent.log}"
JAR_GLOB='cloud-plugin-storage-volume-lightbits-*.jar'
PROPS=lightbits-plugin.properties

RESTART=1
CHECK_ONLY=0
FORCE=0
WAIT_SECS=300
JAR=""
errors=0
warnings=0

usage() {
    cat <<'EOF'
Usage: sudo ./install.sh [options] [path-to-plugin.jar]

Without a jar path, the newest cloud-plugin-storage-volume-lightbits-*.jar next to this script
(or in ../target) is used.

Options:
  --check        Check this node and the jar, change nothing.
  --no-restart   Install the jar but do not restart CloudStack services. The new build is
                 not active until cloudstack-management / cloudstack-agent are restarted.
  --force        Install even if the jar was built for another CloudStack version or its
                 build information cannot be read.
  --wait SECS    How long to wait for a restarted service to load the plugin (default 300).
  -h, --help     Show this help.

Exit status: 0 success, 1 a check or a step failed, 2 wrong usage.
EOF
}

say()  { echo ">>> $*"; }
warn() { echo "WARNING: $*" >&2; warnings=$((warnings + 1)); }
fail() { echo "ERROR: $*" >&2; errors=$((errors + 1)); }
die()  { echo "ERROR: $*" >&2; exit 1; }
bad_usage() { echo "ERROR: $*" >&2; usage >&2; exit 2; }
with_warnings() { if [[ $warnings -gt 0 ]]; then echo " with $warnings warning(s)"; fi; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --check) CHECK_ONLY=1 ;;
        --no-restart) RESTART=0 ;;
        --force) FORCE=1 ;;
        --wait)
            [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]] || bad_usage "--wait needs a number of seconds"
            WAIT_SECS="$2"; shift ;;
        -h|--help) usage; exit 0 ;;
        -*) bad_usage "unknown option '$1'" ;;
        *)
            [[ -z "$JAR" ]] || bad_usage "more than one jar given ('$JAR' and '$1')"
            JAR="$1" ;;
    esac
    shift
done

# ---- the node ----

has_mgmt=0; has_agent=0
[[ -d "$MGMT_LIB" ]] && has_mgmt=1
[[ -d "$AGENT_LIB" ]] && has_agent=1
if [[ $has_mgmt -eq 0 && $has_agent -eq 0 ]]; then
    die "neither $MGMT_LIB nor $AGENT_LIB exists: CloudStack is not installed on this node"
fi
if [[ $CHECK_ONLY -eq 0 && $EUID -ne 0 ]]; then
    die "run as root (sudo): installing the jar and restarting services needs it"
fi

# The CloudStack version a library directory belongs to, from the name of its main jar.
cloudstack_version() {  # $1=libdir
    local f name
    for f in "$1"/cloudstack-[0-9]*.jar "$1"/cloud-agent-[0-9]*.jar "$1"/cloud-api-[0-9]*.jar; do
        [[ -e "$f" ]] || continue
        name="$(basename "$f" .jar)"
        name="${name##*-}"
        if [[ "$name" =~ ^[0-9]+(\.[0-9]+)+ ]]; then
            echo "$name"
            return 0
        fi
    done
    return 0
}

# ---- the jar ----

HERE="$(cd "$(dirname "$0")" && pwd)"
if [[ -z "$JAR" ]]; then
    # shellcheck disable=SC2086,SC2012
    JAR="$(ls -1t "$HERE"/$JAR_GLOB "$HERE"/../target/$JAR_GLOB 2>/dev/null | head -1 || true)"
    [[ -n "$JAR" ]] || bad_usage "no plugin jar found next to this script; pass its path"
fi
[[ -f "$JAR" && -r "$JAR" ]] || die "plugin jar '$JAR' does not exist or cannot be read"
jar_name="$(basename "$JAR")"
# shellcheck disable=SC2254
case "$jar_name" in
    $JAR_GLOB) ;;
    *) die "'$jar_name' is not a Lightbits plugin jar (expected a name like $JAR_GLOB)" ;;
esac

can_inspect=1
read_props() {  # prints the properties file inside the jar, or nothing
    if command -v unzip >/dev/null 2>&1; then
        unzip -p "$JAR" "$PROPS" 2>/dev/null || true
    elif command -v python3 >/dev/null 2>&1; then
        python3 -c 'import sys, zipfile; sys.stdout.write(zipfile.ZipFile(sys.argv[1]).read(sys.argv[2]).decode())' "$JAR" "$PROPS" 2>/dev/null || true
    else
        can_inspect=0
    fi
}
command -v unzip >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1 || can_inspect=0
props="$(read_props)"
prop() { echo "$props" | sed -n "s/^$1=//p" | head -1 | tr -d '\r'; }
jar_version="$(prop version)"
jar_built="$(prop built)"
jar_cloudstack="$(prop cloudstack)"
say "plugin jar: $JAR"
if [[ -n "$jar_version" && -n "$jar_built" ]]; then
    say "  plugin $jar_version, built $jar_built for CloudStack ${jar_cloudstack:-unknown}"
elif [[ $can_inspect -eq 0 ]]; then
    warn "neither unzip nor python3 is installed: the jar cannot be inspected, so its version and its target CloudStack version are not checked"
elif [[ $FORCE -eq 1 ]]; then
    warn "'$jar_name' carries no build information ($PROPS); continuing because of --force"
else
    fail "'$jar_name' is damaged or carries no build information ($PROPS), so it cannot be matched to this CloudStack. Use --force only if you know this jar is right."
fi

same_line() {  # true if two versions share major.minor (4.23.0.0 and 4.23.1.0)
    [[ "$(echo "$1" | cut -d. -f1,2)" == "$(echo "$2" | cut -d. -f1,2)" ]]
}
check_target() {  # $1=libdir $2=role
    local installed
    installed="$(cloudstack_version "$1")"
    if [[ -z "$installed" ]]; then
        warn "could not tell the CloudStack version of the $2 from $1"
        return 0
    fi
    say "  $2: CloudStack $installed"
    [[ -n "$jar_cloudstack" ]] || return 0
    if ! same_line "$installed" "$jar_cloudstack"; then
        if [[ $FORCE -eq 1 ]]; then
            warn "the jar was built for CloudStack $jar_cloudstack but the $2 runs $installed (continuing because of --force)"
        else
            fail "the jar was built for CloudStack $jar_cloudstack but the $2 runs $installed. CloudStack changes its storage plugin interfaces between releases: use the jar built for $(echo "$installed" | cut -d. -f1,2), or --force to install anyway."
        fi
    fi
}
if [[ $has_mgmt -eq 1 ]]; then check_target "$MGMT_LIB" "management server"; fi
if [[ $has_agent -eq 1 ]]; then check_target "$AGENT_LIB" "KVM agent"; fi

# ---- KVM host prerequisites ----

if [[ $has_agent -eq 1 ]]; then
    say "checking the NVMe/TCP prerequisites of this KVM host"
    if ! command -v nvme >/dev/null 2>&1; then
        fail "nvme-cli is not installed (dnf install nvme-cli / apt install nvme-cli): the host cannot attach Lightbits volumes without it"
    fi
    if [[ -d /sys/module/nvme_tcp ]]; then
        say "  nvme_tcp kernel module: loaded"
    elif [[ $CHECK_ONLY -eq 1 ]]; then
        if modinfo nvme_tcp >/dev/null 2>&1 || modinfo nvme-tcp >/dev/null 2>&1; then
            say "  nvme_tcp kernel module: available, not loaded (the plugin loads it on the first attach)"
        else
            fail "the nvme_tcp kernel module is not available for the running kernel $(uname -r)"
        fi
    elif modprobe nvme-tcp 2>/dev/null; then
        say "  nvme_tcp kernel module: loaded now"
    else
        fail "the nvme_tcp kernel module could not be loaded for the running kernel $(uname -r)"
    fi
    if [[ ! -s /etc/nvme/hostnqn ]]; then
        if [[ $CHECK_ONLY -eq 1 ]]; then
            warn "/etc/nvme/hostnqn is missing or empty; install.sh without --check generates it"
        elif command -v nvme >/dev/null 2>&1 && mkdir -p /etc/nvme && nvme gen-hostnqn > /etc/nvme/hostnqn.new 2>/dev/null \
                && [[ -s /etc/nvme/hostnqn.new ]] && mv -f /etc/nvme/hostnqn.new /etc/nvme/hostnqn; then
            say "  generated /etc/nvme/hostnqn"
        else
            rm -f /etc/nvme/hostnqn.new
            fail "/etc/nvme/hostnqn is missing and could not be generated"
        fi
    fi
    if [[ -s /etc/nvme/hostnqn ]]; then
        nqn="$(head -1 /etc/nvme/hostnqn | tr -d '[:space:]')"
        if [[ "$nqn" =~ ^nqn\.[0-9]{4}-[0-9]{2}\.[A-Za-z0-9.:_-]+$ ]]; then
            say "  host NQN: $nqn"
            say "  (every host needs its own NQN: a host cloned from an image must get a new one with 'nvme gen-hostnqn')"
        else
            fail "/etc/nvme/hostnqn does not hold a valid NQN: '$nqn'"
        fi
    fi
fi

if [[ $errors -gt 0 ]]; then
    die "$errors check(s) failed, nothing was changed"
fi
if [[ $CHECK_ONLY -eq 1 ]]; then
    say "checks passed$(with_warnings); nothing was changed (--check)"
    exit 0
fi

# ---- install ----

install_to() {  # $1=libdir
    local lib="$1" tmp old
    tmp="$lib/.${jar_name}.installing"
    cp -f -- "$JAR" "$tmp"
    chmod 644 "$tmp"
    # one plugin jar only: two builds on the class path load in an undefined order
    for old in "$lib"/$JAR_GLOB; do
        [[ -e "$old" && "$(basename "$old")" != "$jar_name" ]] || continue
        say "  removing the previous build $(basename "$old")"
        rm -f -- "$old"
    done
    mv -f -- "$tmp" "$lib/$jar_name"
    say "installed $lib/$jar_name"
}

log_size() { if [[ -f "$1" ]]; then stat -c %s "$1" 2>/dev/null || echo 0; else echo 0; fi; }

# Lines the service wrote after the restart that say this build was loaded.
loaded_lines() {  # $1=log $2=offset $3=where
    local want="Lightbits storage plugin"
    [[ -z "$jar_built" ]] || want="built $jar_built"
    tail -c "+$(($2 + 1))" "$1" 2>/dev/null | grep -F "loaded on $3" | grep -F -- "$want" || true
}

# Wait until the service is active and its log, after the restart, shows this build loaded.
wait_loaded() {  # $1=service $2=log $3=offset $4=where
    local svc="$1" log="$2" offset="$3" where="$4" waited=0 line
    while [[ $waited -lt $WAIT_SECS ]]; do
        if [[ "$(systemctl is-active "$svc" 2>/dev/null || true)" == failed ]]; then
            fail "$svc failed to start; see 'journalctl -u $svc' and $log"
            return 0
        fi
        if [[ -f "$log" ]]; then
            if [[ "$(log_size "$log")" -lt "$offset" ]]; then offset=0; fi     # the log was rotated
            line="$(loaded_lines "$log" "$offset" "$where" | tail -1)"
            if [[ -n "$line" ]]; then
                say "$svc loaded the plugin: Lightbits storage plugin${line##*Lightbits storage plugin}"
                return 0
            fi
        fi
        sleep 5; waited=$((waited + 5))
    done
    fail "$svc did not report loading this build within ${WAIT_SECS}s (state: $(systemctl is-active "$svc" 2>/dev/null || true)); look for 'Lightbits storage plugin' in $log"
}

restart_and_verify() {  # $1=service $2=log $3=where
    local offset
    offset="$(log_size "$2")"
    say "restarting $1"
    if ! systemctl restart "$1"; then
        fail "'systemctl restart $1' failed; see 'journalctl -u $1'"
        return 0
    fi
    wait_loaded "$1" "$2" "$offset" "$3"
}

if [[ $has_mgmt -eq 1 ]]; then install_to "$MGMT_LIB"; fi
if [[ $has_agent -eq 1 ]]; then install_to "$AGENT_LIB"; fi

if [[ $RESTART -eq 0 ]]; then
    say "services were NOT restarted (--no-restart). The new build becomes active after:"
    if [[ $has_mgmt -eq 1 ]]; then echo "      systemctl restart cloudstack-management"; fi
    if [[ $has_agent -eq 1 ]]; then echo "      systemctl restart cloudstack-agent"; fi
    exit 0
fi

# The agent first: running VMs are not affected by an agent restart, and on a node with both
# roles the agent reconnects by itself once the management server is back.
if [[ $has_agent -eq 1 ]]; then restart_and_verify cloudstack-agent "$AGENT_LOG" "this KVM agent"; fi
if [[ $has_mgmt -eq 1 ]]; then
    say "the management server restart interrupts the CloudStack API and UI for a few minutes; running VMs are not affected"
    restart_and_verify cloudstack-management "$MGMT_LOG" "the management server"
fi

if [[ $errors -gt 0 ]]; then
    die "the jar is installed but $errors step(s) failed, see above"
fi
say "done$(with_warnings). Install the same jar on the management server and on every KVM host."
if [[ $has_mgmt -eq 1 ]]; then say "Register a storage pool with provider=Lightbits (see INSTALL.md)."; fi
exit 0
