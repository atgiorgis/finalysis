#!/usr/bin/env bash
# Deletes and recreates the local dev database (the compose `db` service and its pgdata volume).
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

echo "This deletes the local dev database: the compose 'db' container and its 'pgdata' volume."
echo "Every account, statement, and transaction in it is lost. There is no undo."
read -r -p 'Type "reset" to continue: ' answer || answer=""
if [[ "$answer" != "reset" ]]; then
    echo "Aborted; nothing was changed."
    exit 1
fi

# db is the project's only service, so this removes exactly its container and the pgdata volume.
docker compose down --volumes
docker compose up -d --wait db

echo
echo "Dev database recreated and empty."
echo "Start the backend so Flyway re-applies the migrations:"
echo "  cd backend && ./mvnw spring-boot:run"
