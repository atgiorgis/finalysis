# Shared helpers for the vault scripts (docs/plans/phase-8.md §4.3, §4.4). Sourced, never run.
#
# Rules every vault script follows: fixed arguments, refused otherwise; secrets only on stdin, never in an argument,
# the environment or a file outside the vault; fixed messages only, never a key, a password, file contents or a
# tool's own output.
#
# Exit codes: 0 done; 1 refused (arguments or state); 2 a tool failed; 3 done, but the unmount had to be forced;
# 4 refused because a Postgres server still runs on the volume.

set -euo pipefail
export PATH=/usr/bin:/bin:/usr/sbin:/sbin
umask 077

VAULT_SCRIPT="$(basename "$0" .sh)"
VAULT_BUNDLE_NAME=finalysis.sparsebundle

refuse() {
    echo "$VAULT_SCRIPT: refused: $*" >&2
    exit 1
}

fail() {
    echo "$VAULT_SCRIPT: failed: $*" >&2
    exit 2
}

# The vault directory: an existing absolute path, resolved without symlinks.
vault_dir() {
    local dir="$1"
    [[ "$dir" == /* ]] || refuse "the vault directory must be an absolute path"
    [[ "$dir" != *$'\n'* ]] || refuse "the vault directory has a newline in its name"
    [[ -d "$dir" ]] || refuse "the vault directory does not exist"
    (cd "$dir" && pwd -P)
}

# Reads one secret line from stdin into the named variable (the trailing newline, if any, is dropped).
read_secret() {
    local __name="$1" __value=""
    IFS= read -r __value || [[ -n "$__value" ]] || refuse "no secret on stdin"
    [[ -n "$__value" ]] || refuse "the secret on stdin is empty"
    printf -v "$__name" '%s' "$__value"
}

# Prints MOUNTED (this image at <dir>/mnt), ELSEWHERE (this image attached anywhere else) or UNMOUNTED.
image_state() {
    local dir="$1" bundle="$1/$VAULT_BUNDLE_NAME" mnt="$1/mnt"
    hdiutil info | awk -v bundle="$bundle" -v mnt="$mnt" '
        function strip_data(p) { sub(/^\/System\/Volumes\/Data/, "", p); return p }
        /^=+$/ { mine = 0; next }
        /^image-path[[:space:]]*:/ {
            path = $0; sub(/^image-path[[:space:]]*:[[:space:]]*/, "", path)
            mine = (strip_data(path) == bundle); next
        }
        mine && /^\/dev\// {
            n = split($0, f, "\t")
            if (n >= 3 && f[3] != "") { attached = 1; if (strip_data(f[3]) == mnt) here = 1 }
            else attached = 1
        }
        END { print (here ? "MOUNTED" : (attached ? "ELSEWHERE" : "UNMOUNTED")) }'
}

# True when a Postgres server runs on the data directory (its postmaster.pid names a live process).
postmaster_running() {
    local pidfile="$1/postmaster.pid" pid
    [[ -f "$pidfile" ]] || return 1
    pid="$(head -n 1 "$pidfile" 2>/dev/null | tr -cd '0-9')"
    [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

# The Postgres binaries directory: absolute, with the tools the vault needs.
pg_bin() {
    local bin="$1" tool
    [[ "$bin" == /* ]] || refuse "the Postgres bin directory must be an absolute path"
    for tool in postgres initdb pg_ctl pg_config; do
        [[ -x "$bin/$tool" ]] || refuse "the Postgres bin directory has no $tool"
    done
    printf '%s' "$bin"
}

port_number() {
    [[ "$1" =~ ^[0-9]{4,5}$ ]] && (( $1 >= 1024 && $1 <= 65535 )) || refuse "the port must be a number from 1024 to 65535"
    printf '%s' "$1"
}

port_in_use() {
    nc -z -G 1 127.0.0.1 "$1" >/dev/null 2>&1
}
