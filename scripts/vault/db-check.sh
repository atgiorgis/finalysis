#!/usr/bin/env bash
# The vault database's startup check (docs/plans/phase-8.md §4.4), run before setup and unlock.
#
#   db-check.sh <vault dir> <postgres bin dir> <port>
#
# Prints one line and exits 0:
#   OK
#   NOT_READY <reason> [installed=<version>] [required=<version>] [item=<file or setting name>]
# with <reason> one of postgres-missing, postgres-wrong-version, pgvector-missing, pgvector-wrong-version,
# launchd-conflict, postgresapp-conflict, port-in-use. The backend turns it into a plain sentence.
#
# The pins: PostgreSQL 17 (any 17.x minor; minors share the data format) and exactly the pgvector that Postgres.app's
# PostgreSQL 17 bundles. Change REQUIRED_PGVECTOR deliberately, with the README and a rerun of the vault IT.
#
# Tests point the two conflict checks at temporary files: FINALYSIS_LAUNCHD_DIRS (directories, colon-separated) and
# FINALYSIS_POSTGRESAPP_PREFS (a plist file in place of Postgres.app's settings).
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

REQUIRED_PG_MAJOR=17
REQUIRED_PGVECTOR=0.8.6

[[ $# -eq 3 ]] || refuse "usage: db-check.sh <vault dir> <postgres bin dir> <port>"
dir="$(vault_dir "$1")"
bin="$2"
[[ "$bin" == /* ]] || refuse "the Postgres bin directory must be an absolute path"
port="$(port_number "$3")"

not_ready() {
    echo "NOT_READY $*"
    exit 0
}

# PostgreSQL
[[ -x "$bin/postgres" && -x "$bin/pg_config" && -x "$bin/initdb" && -x "$bin/pg_ctl" ]] \
    || not_ready postgres-missing "required=$REQUIRED_PG_MAJOR"
installed="$("$bin/postgres" --version 2>/dev/null | sed -nE 's/.*PostgreSQL\)? ([0-9]+(\.[0-9]+)?).*/\1/p' | head -n 1)"
[[ -n "$installed" ]] || not_ready postgres-missing "required=$REQUIRED_PG_MAJOR"
[[ "${installed%%.*}" == "$REQUIRED_PG_MAJOR" ]] \
    || not_ready postgres-wrong-version "installed=$installed" "required=$REQUIRED_PG_MAJOR"

# pgvector, as installed in this PostgreSQL
sharedir="$("$bin/pg_config" --sharedir 2>/dev/null || true)"
control="$sharedir/extension/vector.control"
[[ -n "$sharedir" && -f "$control" ]] || not_ready pgvector-missing "required=$REQUIRED_PGVECTOR"
vector="$(sed -nE "s/^[[:space:]]*default_version[[:space:]]*=[[:space:]]*'([^']+)'.*/\1/p" "$control" | head -n 1)"
[[ -n "$vector" ]] || not_ready pgvector-missing "required=$REQUIRED_PGVECTOR"
[[ "$vector" == "$REQUIRED_PGVECTOR" ]] \
    || not_ready pgvector-wrong-version "installed=$vector" "required=$REQUIRED_PGVECTOR"

# Prints the plist as XML text (a binary plist's strings are not plain bytes); nothing for a file that isn't one.
plist_text() {
    plutil -convert xml1 -o - -- "$1" 2>/dev/null || true
}

# launchd: no job may start anything from the vault.
launchd_dirs="${FINALYSIS_LAUNCHD_DIRS:-$HOME/Library/LaunchAgents:/Library/LaunchAgents:/Library/LaunchDaemons}"
old_ifs="$IFS"; IFS=:
set -f
ldirs=($launchd_dirs)
set +f
IFS="$old_ifs"
for ldir in "${ldirs[@]}"; do
    [[ -d "$ldir" ]] || continue
    for job in "$ldir"/*.plist; do
        [[ -f "$job" ]] || continue
        if plist_text "$job" | grep -qF -- "$dir"; then
            not_ready launchd-conflict "item=$(basename "$job")"
        fi
    done
done

# Postgres.app: none of its servers may use the vault. Its settings are searched as text, every key and value.
if [[ -n "${FINALYSIS_POSTGRESAPP_PREFS:-}" ]]; then
    prefs="$(plist_text "$FINALYSIS_POSTGRESAPP_PREFS")"
else
    prefs="$(defaults export com.postgresapp.Postgres2 - 2>/dev/null || true)"
fi
if [[ -n "$prefs" ]] && grep -qF -- "$dir" <<<"$prefs"; then
    not_ready postgresapp-conflict "item=com.postgresapp.Postgres2"
fi

# The port must be free before the cluster starts.
if port_in_use "$port"; then
    not_ready port-in-use "item=$port"
fi

echo OK
