#!/usr/bin/env bash
# Stops the vault's Postgres cluster (docs/plans/phase-8.md §4.4, §4.6).
#
#   db-stop.sh <vault dir> <postgres bin dir>
#
# A fast shutdown (sessions ended, a checkpoint written), waiting up to 30 seconds. If that times out, an immediate
# shutdown (a crash stop that WAL replay repairs at the next start), reported with exit 3 and a warning. Done only
# when no server runs on the data directory any more.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -eq 2 ]] || refuse "usage: db-stop.sh <vault dir> <postgres bin dir>"
dir="$(vault_dir "$1")"
bin="$(pg_bin "$2")"
pgdata="$dir/mnt/pgdata"

if ! postmaster_running "$pgdata"; then
    echo "db-stop: not running"
    exit 0
fi

if "$bin/pg_ctl" stop -D "$pgdata" -m fast -w -t 30 -s >/dev/null 2>&1 && ! postmaster_running "$pgdata"; then
    echo "db-stop: done"
    exit 0
fi

echo "db-stop: warning: the fast shutdown did not finish in 30 seconds; stopping immediately" >&2
"$bin/pg_ctl" stop -D "$pgdata" -m immediate -w -t 30 -s >/dev/null 2>&1 || true
if postmaster_running "$pgdata"; then
    fail "the server is still running"
fi
echo "db-stop: done (immediate)"
exit 3
