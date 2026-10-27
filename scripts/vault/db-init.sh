#!/usr/bin/env bash
# Creates the vault's Postgres cluster at vault setup (docs/plans/phase-8.md §4.4, §4.5).
#
#   db-init.sh <vault dir> <postgres bin dir> <port>     the database password on stdin
#
# Needs the image mounted and <mount>/pgdata empty. Keeps the password in <mount>/secrets/db-password (0600, inside
# the vault), runs initdb (superuser "finalysis", SCRAM, UTF-8, ICU en-US collation, data checksums), applies
# postgresql.vault.conf and pg_hba.vault.conf, and creates the database "finalysis". Leaves the server stopped.
# initdb's own output goes to <mount>/pglog/initdb.log, inside the vault.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"

[[ $# -eq 3 ]] || refuse "usage: db-init.sh <vault dir> <postgres bin dir> <port>"
dir="$(vault_dir "$1")"
bin="$(pg_bin "$2")"
port="$(port_number "$3")"
mnt="$dir/mnt"
pgdata="$mnt/pgdata"

[[ "$(image_state "$dir")" == MOUNTED ]] || refuse "the image is not mounted"
[[ -d "$pgdata" && -z "$(ls -A "$pgdata")" ]] || refuse "the data directory is missing or not empty"
mkdir -p -m 700 "$mnt/secrets" "$mnt/pglog"
chmod 700 "$pgdata"

read_secret password
printf '%s' "$password" > "$mnt/secrets/db-password"
chmod 600 "$mnt/secrets/db-password"

if ! printf '%s\n' "$password" | "$bin/initdb" -D "$pgdata" --username=finalysis --pwfile=/dev/stdin \
        --auth=scram-sha-256 --encoding=UTF8 --locale=C --locale-provider=icu --icu-locale=en-US \
        --data-checksums >"$mnt/pglog/initdb.log" 2>&1; then
    password=""
    fail "initdb did not finish (see pglog/initdb.log in the vault)"
fi
password=""

sed "s/@PORT@/$port/" "$here/postgresql.vault.conf" >> "$pgdata/postgresql.conf"
cp "$here/pg_hba.vault.conf" "$pgdata/pg_hba.conf"
chmod 600 "$pgdata/pg_hba.conf"

# Single-user mode: no server, no port, no password needed to create the app's database.
echo "CREATE DATABASE finalysis;" | "$bin/postgres" --single -D "$pgdata" postgres >>"$mnt/pglog/initdb.log" 2>&1 \
    || fail "could not create the database (see pglog/initdb.log in the vault)"

echo "db-init: done"
