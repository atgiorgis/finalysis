#!/usr/bin/env bash
# Copies the data of the plain Docker dev database into the vault's cluster (docs/plans/phase-8.md §4.5). The owner
# runs it once, by hand, after setting up the vault.
#
#   migrate-plain-to-vault.sh <vault dir> <postgres bin dir> <port>
#
# Needs: the vault unlocked (image mounted, its cluster running, its schema migrated by the app), and the compose `db`
# service running. Both databases must be at the same Flyway version, and the vault must hold no accounts yet.
#
# pg_dump runs inside the Docker container (its own PostgreSQL 17 pg_dump, data only, triggers disabled, without
# Flyway's history) and streams straight into the vault's psql, in one transaction that first empties the vault's
# tables (the seed rows Flyway put there come back from the dump). Nothing is written to a file between them.
# Then it compares row counts per table and prints counts only, never rows.
#
# Tests point it at another container with FINALYSIS_MIGRATE_SOURCE_CONTAINER (a container id).
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
export PATH="$PATH:/usr/local/bin:/opt/homebrew/bin:/Applications/Docker.app/Contents/Resources/bin"

[[ $# -eq 3 ]] || refuse "usage: migrate-plain-to-vault.sh <vault dir> <postgres bin dir> <port>"
dir="$(vault_dir "$1")"
bin="$(pg_bin "$2")"
port="$(port_number "$3")"
mnt="$dir/mnt"

[[ "$(image_state "$dir")" == MOUNTED ]] || refuse "the vault is not unlocked (its image is not mounted)"
postmaster_running "$mnt/pgdata" || refuse "the vault's database is not running"
[[ -f "$mnt/secrets/db-password" ]] || refuse "the vault has no database password"
command -v docker >/dev/null || refuse "docker is not installed"

if [[ -n "${FINALYSIS_MIGRATE_SOURCE_CONTAINER:-}" ]]; then
    source_container="$FINALYSIS_MIGRATE_SOURCE_CONTAINER"
else
    source_container="$(cd "$repo" && docker compose ps -q db 2>/dev/null || true)"
fi
[[ "$source_container" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || refuse "the Docker dev database is not running"

# The vault's password reaches psql through a password file inside the vault, removed on exit.
export PGPASSFILE="$mnt/secrets/pgpass"
trap 'rm -f "$PGPASSFILE"' EXIT
printf '127.0.0.1:%s:finalysis:finalysis:%s\n' "$port" "$(cat "$mnt/secrets/db-password")" > "$PGPASSFILE"
chmod 600 "$PGPASSFILE"

vault_sql() {
    "$bin/psql" -X -w -q -h 127.0.0.1 -p "$port" -U finalysis -d finalysis -v ON_ERROR_STOP=1 "$@"
}
plain_sql() {
    docker exec -i "$source_container" sh -c 'exec psql -X -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 "$@"' \
        psql "$@"
}

# pg_dump's note on category's self-reference: harmless here, since the dump disables triggers. Other lines pass.
dump_warnings() {
    grep -vE '^pg_dump: (warning: there are circular foreign-key constraints|detail: category$|hint: (You might not be able to restore|Consider using a full dump))' || true
}

latest_version="SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1"
plain_version="$(plain_sql -tAc "$latest_version")" || fail "cannot read the dev database's Flyway version"
vault_version="$(vault_sql -tAc "$latest_version")" || fail "cannot read the vault's Flyway version"
[[ -n "$plain_version" && "$plain_version" == "$vault_version" ]] \
    || refuse "the schemas differ (dev database at V$plain_version, vault at V$vault_version); start the app on the vault so Flyway brings it to the same version, or update the dev database"
[[ "$(vault_sql -tAc "SELECT count(*) FROM account")" == 0 ]] || refuse "the vault already holds accounts; nothing was copied"

echo "Copying the dev database (V$plain_version) into the vault..."
{
    cat <<'SQL'
DO $$
BEGIN
    EXECUTE (SELECT 'TRUNCATE ' || string_agg(format('%I.%I', schemaname, tablename), ', ') || ' RESTART IDENTITY CASCADE'
             FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history');
END $$;
SQL
    docker exec "$source_container" sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --data-only --disable-triggers \
        --no-owner --no-privileges --exclude-table=flyway_schema_history' 2> >(dump_warnings >&2) \
        || echo 'SELECT 1/0;'  # pg_dump failed: make psql fail too, so the transaction rolls back
} | vault_sql --single-transaction >/dev/null || fail "the copy failed and was rolled back; the vault is unchanged"

count_rows="SELECT string_agg(t || ' ' || n, E'\n' ORDER BY t) FROM (
    SELECT c.relname AS t, (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM public.%I', c.relname), false, true, '')))[1]::text AS n
    FROM pg_class c JOIN pg_namespace s ON s.oid = c.relnamespace
    WHERE s.nspname = 'public' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history') x"
plain_counts="$(plain_sql -tAc "$count_rows")" || fail "cannot count the dev database's rows"
vault_counts="$(vault_sql -tAc "$count_rows")" || fail "cannot count the vault's rows"

printf '%-28s %12s %12s\n' table "dev database" vault
{ sed 's/^/P /' <<<"$plain_counts"; sed 's/^/V /' <<<"$vault_counts"; } | awk '
    $1 == "P" { plain[$2] = $3; seen[$2] = 1 }
    $1 == "V" { vault[$2] = $3; seen[$2] = 1 }
    END {
        for (t in seen) {
            p = (t in plain) ? plain[t] : "missing"; v = (t in vault) ? vault[t] : "missing"
            printf "%-28s %12s %12s%s\n", t, p, v, (p == v ? "" : "   DIFFERENT")
        }
    }' | sort

if [[ "$plain_counts" != "$vault_counts" ]]; then
    fail "the row counts differ (above)"
fi

cat <<'DONE'
Done: every table has the same number of rows in the vault.

The Docker dev database still holds a readable copy of this data. Once the app works on the vault, delete it with
scripts/reset-dev-db.sh (it removes the dev database's volume and starts an empty one); from then on, use the dev
database only with synthetic data. Deleting a Docker volume doesn't overwrite its blocks inside Docker's VM disk;
FileVault covers that disk.
DONE
