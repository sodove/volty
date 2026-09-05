#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd -P)"
ENV_FILE="${1:-$ROOT/.env}"
[ -f "$ENV_FILE" ] || { echo "missing env file: $ENV_FILE" >&2; exit 1; }
docker compose --env-file "$ENV_FILE" -f "$ROOT/docker-compose.yml" --profile offline ps
set -a
. "$ENV_FILE"
set +a
curl --fail --silent --show-error --max-time 10 "http://127.0.0.1:${VOLTY_APP_HOST_PORT:-18080}/health" >/dev/null
echo 'app health: ok'
