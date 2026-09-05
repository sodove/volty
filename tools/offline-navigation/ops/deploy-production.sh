#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd -P)"
ENV_FILE="${1:-$ROOT/.env}"

fail() { printf '[offline-deploy] ERROR: %s\n' "$*" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || fail 'docker is required'
docker info >/dev/null 2>&1 || fail 'docker daemon is unavailable'
[ -f "$ENV_FILE" ] || fail "missing env file: $ENV_FILE"

# shellcheck disable=SC1090
set -a
. "$ENV_FILE"
set +a

OFFLINE_ROOT="${VOLTY_OFFLINE_HOST_DIR:-/srv/volty/offline}"
STAGING_ROOT="${VOLTY_OFFLINE_STAGING_HOST_DIR:-/srv/volty/offline-production/staging}"
SOURCE_ROOT="${VOLTY_OFFLINE_SOURCE_HOST_DIR:-/srv/volty/offline-production/sources}"
CONFIG_PATH="${VOLTY_OFFLINE_CONFIG_HOST:-/srv/volty/offline-production/production.json}"
KEY_PATH="${VOLTY_OFFLINE_SIGNING_KEY_HOST:-/srv/volty/offline-production/secrets/signing-key.pem}"

for root in "$OFFLINE_ROOT" "$STAGING_ROOT" "$SOURCE_ROOT"; do
  case "$root" in
    /|/srv|/var|/opt|/home|/root) fail 'offline roots must be dedicated child directories' ;;
    /*) ;;
    *) fail 'offline roots must be absolute paths' ;;
  esac
done
[ -f "$CONFIG_PATH" ] || fail "missing production config: $CONFIG_PATH"
[ -f "$KEY_PATH" ] || fail "missing signing key: $KEY_PATH"
mode="$(stat -c '%a' "$KEY_PATH" 2>/dev/null || stat -f '%Lp' "$KEY_PATH")"
case "$mode" in 600|400|640|440) ;; *) fail "signing key must be mode 0600/0400/0640/0440" ;; esac

install -d -m 755 "$OFFLINE_ROOT" "$STAGING_ROOT" "$SOURCE_ROOT"
compose=(docker compose --env-file "$ENV_FILE" -f "$ROOT/docker-compose.yml" --profile offline)
"${compose[@]}" config --quiet
"${compose[@]}" build app offline-worker offline-scheduler
"${compose[@]}" up -d --no-deps offline-worker offline-scheduler
"${compose[@]}" up -d --no-deps app
"${compose[@]}" ps
printf '[offline-deploy] services updated; verify with tools/offline-navigation/ops/status.sh\n'
