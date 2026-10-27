#!/usr/bin/env bash
# Starts the vault's Postgres cluster (docs/plans/phase-8.md §4.4).
#
#   db-start.sh <vault dir> <postgres bin dir> <port>
#
# Only on this image mounted at <vault dir>/mnt, with a cluster in it whose port is <port>, and with that port free.
# pg_ctl's own output goes to <mount>/pglog/pg_ctl.log, inside the vault; the server detaches from the caller.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -eq 3 ]] || refuse "usage: db-start.sh <vault dir> <postgres bin dir> <port>"
dir="$(vault_dir "$1")"
bin="$(pg_bin "$2")"
port="$(port_number "$3")"
mnt="$dir/mnt"
pgdata="$mnt/pgdata"

[[ "$(image_state "$dir")" == MOUNTED ]] || refuse "the image is not mounted"
[[ -f "$pgdata/PG_VERSION" ]] || refuse "there is no cluster in the vault"
configured="$(sed -nE 's/^[[:space:]]*port[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$pgdata/postgresql.conf" | tail -n 1)"
[[ "$configured" == "$port" ]] || refuse "the cluster is configured for another port"
if postmaster_running "$pgdata"; then
    refuse "the cluster is already running"
fi
if port_in_use "$port"; then
    refuse "the port is already in use"
fi

mkdir -p -m 700 "$mnt/pglog"
"$bin/pg_ctl" start -D "$pgdata" -l "$mnt/pglog/pg_ctl.log" -w -t 60 -s >/dev/null 2>&1 \
    || fail "the cluster did not start (see pglog/pg_ctl.log in the vault)"
echo "db-start: done"
