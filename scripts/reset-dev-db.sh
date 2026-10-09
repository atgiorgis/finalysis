#!/usr/bin/env bash
# Deletes and recreates the local dev database (the compose `db` service and its own pgdata volume only).
# ALL LOCAL DATA IS LOST. Start the backend afterwards so Flyway re-applies the migrations.
#
# Takes no arguments and refuses to run if anything suggests a non-local target.
set -euo pipefail

refuse() {
    echo "reset-dev-db: refusing to run: $*" >&2
    exit 1
}

if [[ $# -gt 0 ]]; then
    refuse "this script takes no arguments (got: $*)"
fi

# Run from the repo root so compose uses this project's compose.yaml and .env.
cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Anything that could point compose, Docker, or the app at another project or host.
for var in DOCKER_CONTEXT COMPOSE_FILE COMPOSE_PROJECT_NAME SPRING_DATASOURCE_URL; do
    if [[ -n "${!var:-}" ]]; then
        refuse "$var is set (${!var}); unset it to reset the local dev database"
    fi
done

if [[ -n "${DOCKER_HOST:-}" && "$DOCKER_HOST" != unix://* ]]; then
    refuse "DOCKER_HOST is not a local socket ($DOCKER_HOST)"
fi

endpoint="$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)" \
    || refuse "cannot read the active Docker context (is Docker running?)"
if [[ "$endpoint" != unix://* ]]; then
    refuse "the active Docker context is not local ($endpoint)"
fi

# POSTGRES_HOST from the environment wins, as it does for the backend; otherwise read .env.
postgres_host="${POSTGRES_HOST:-}"
if [[ -z "$postgres_host" && -f .env ]]; then
    postgres_host="$(sed -n 's/^[[:space:]]*POSTGRES_HOST[[:space:]]*=[[:space:]]*//p' .env | tail -n 1 | tr -d "\"'\r")"
fi
case "${postgres_host:-localhost}" in
    localhost | 127.0.0.1 | ::1) ;;
    *) refuse "POSTGRES_HOST is not local ($postgres_host)" ;;
esac

if [[ ! -t 0 ]]; then
    refuse "stdin is not a terminal; run this interactively"
fi

# Resolve the db volume's real Docker name from the compose config (it depends on the project name),
# and only ever delete that one volume. db_volume_key is the compose key that db mounts.
db_volume_key=pgdata
config="$(docker compose config)" || refuse "docker compose config failed"
project="$(sed -n 's/^name:[[:space:]]*//p' <<<"$config" | tr -d "\"'")"
volume="$(awk -v key="$db_volume_key" '
    /^[^[:space:]]/ { top = $0; in_key = 0; next }
    top == "volumes:" && $0 ~ "^  " key ":[[:space:]]*$" { in_key = 1; next }
    in_key && /^  [^[:space:]]/ { exit }
    in_key && /^    name:/ { sub(/^    name:[[:space:]]*/, ""); gsub(/["\047]/, ""); print; exit }
' <<<"$config")"
[[ -n "$project" ]] || refuse "cannot read the compose project name"
[[ "$volume" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || refuse "cannot resolve the '$db_volume_key' volume name (got: '$volume')"

volume_exists=false
if docker volume inspect "$volume" >/dev/null 2>&1; then
    volume_exists=true
    labels="$(docker volume inspect \
        --format '{{index .Labels "com.docker.compose.project"}}/{{index .Labels "com.docker.compose.volume"}}' \
        "$volume")"
    if [[ "$labels" != "$project/$db_volume_key" ]]; then
        refuse "volume $volume is not compose volume '$db_volume_key' of project '$project' (labels: $labels)"
    fi
fi

echo "This deletes the local dev database: the compose 'db' container and the '$volume' volume."
echo "Every account, statement, and transaction in it is lost. There is no undo."
read -r -p 'Type "reset" to continue: ' answer || answer=""
if [[ "$answer" != "reset" ]]; then
    echo "Aborted; nothing was changed."
    exit 1
fi

# Remove only db's container and its own volume. NEVER use `docker compose down --volumes` or
# delete any other volume: other services keep large or precious data in their own volumes (from
# Phase 10, the ollama models volume), and a database reset must leave them untouched.
docker compose rm --stop --force db
if [[ "$volume_exists" == true ]]; then
    # Fails, and set -e stops the script, if any other container still uses the volume.
    docker volume rm "$volume"
fi
docker compose up -d --wait db

echo
echo "Dev database recreated and empty."
echo "Start the backend so Flyway re-applies the migrations:"
echo "  cd backend && ./mvnw spring-boot:run"
