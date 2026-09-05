#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd -P)"
ENV_FILE="${1:-$ROOT/.env}"
DEST="${2:?usage: backup.sh ENV_FILE DEST_DIR}"
[ -f "$ENV_FILE" ] || { echo "missing env file: $ENV_FILE" >&2; exit 1; }
mkdir -p "$DEST"
chmod 700 "$DEST"
set -a
. "$ENV_FILE"
set +a
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
dump="$DEST/volty-$stamp.sql"
docker compose --env-file "$ENV_FILE" -f "$ROOT/docker-compose.yml" exec -T db pg_dump -U "${POSTGRES_USER:-volty}" "${POSTGRES_DB:-volty}" > "$dump"
chmod 600 "$dump"
offline_root="${VOLTY_OFFLINE_HOST_DIR:-/srv/volty/offline}"
tar -czf "$DEST/offline-catalog-$stamp.tgz" -C "$offline_root" catalog.json regions 2>/dev/null || true
chmod 600 "$DEST/offline-catalog-$stamp.tgz" 2>/dev/null || true
echo "database and catalog backup written to $DEST (local copy; independent disaster target still required)"
