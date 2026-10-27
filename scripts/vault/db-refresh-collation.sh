#!/usr/bin/env bash
# After Postgres.app's ICU library changed (docs/plans/phase-8.md §4.4): rebuilds the vault database's indexes with
# the new collation, then records the new collation version. The app's status names this step when it sees a change
# at unlock; lock and unlock again afterwards to clear the notice.
#
#   db-refresh-collation.sh <vault dir> <postgres bin dir> <port>
#
# Needs the vault unlocked (image mounted, its cluster running). Prints the versions only.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -eq 3 ]] || refuse "usage: db-refresh-collation.sh <vault dir> <postgres bin dir> <port>"
dir="$(vault_dir "$1")"
bin="$(pg_bin "$2")"
port="$(port_number "$3")"
mnt="$dir/mnt"

[[ "$(image_state "$dir")" == MOUNTED ]] || refuse "the vault is not unlocked (its image is not mounted)"
postmaster_running "$mnt/pgdata" || refuse "the vault's database is not running"
[[ -f "$mnt/secrets/db-password" ]] || refuse "the vault has no database password"

export PGPASSFILE="$mnt/secrets/pgpass"
trap 'rm -f "$PGPASSFILE"' EXIT
printf '127.0.0.1:%s:finalysis:finalysis:%s\n' "$port" "$(cat "$mnt/secrets/db-password")" > "$PGPASSFILE"
chmod 600 "$PGPASSFILE"

sql() {
    "$bin/psql" -X -w -q -h 127.0.0.1 -p "$port" -U finalysis -d finalysis -v ON_ERROR_STOP=1 "$@"
}

versions="SELECT coalesce(datcollversion, 'none') || ' -> ' || coalesce(pg_database_collation_actual_version(oid), 'none')
          FROM pg_database WHERE datname = current_database()"
echo "collation version (recorded -> library): $(sql -tAc "$versions")"
sql -c "REINDEX DATABASE finalysis" || fail "REINDEX did not finish; nothing was recorded"
sql -c "ALTER DATABASE finalysis REFRESH COLLATION VERSION" >/dev/null || fail "the new version was not recorded"
echo "collation version now: $(sql -tAc "$versions")"
echo "db-refresh-collation: done; lock and unlock Finalysis to clear the notice"
