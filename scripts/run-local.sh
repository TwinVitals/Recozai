#!/usr/bin/env bash
# Starts a local PostgreSQL (Docker) and the backend with the `local` Spring profile.
# Usage: scripts/run-local.sh [down]
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f deploy/compose.local-db.yml --env-file .secrets/local/db.env)

if [[ "${1:-}" == "down" ]]; then "${COMPOSE[@]}" down; exit 0; fi

if [[ ! -f .secrets/local/db.env ]]; then
  umask 077; mkdir -p .secrets/local
  echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)" > .secrets/local/db.env
fi
set -a; . .secrets/local/db.env; set +a

# The project requires exactly Java 25; override any other JAVA_HOME (e.g. Java 8) from the shell.
JAVA25="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
[[ -n "$JAVA25" ]] && export JAVA_HOME="$JAVA25" && export PATH="$JAVA_HOME/bin:$PATH"

"${COMPOSE[@]}" up -d --wait
export DATABASE_PASSWORD="$POSTGRES_PASSWORD"
cd backend
exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
