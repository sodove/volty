#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd -P)"
ENV_FILE="${1:-$ROOT/.env}"
SOURCE_ID="${2:-russia}"

fail() { printf '[offline-bootstrap] ERROR: %s\n' "$*" >&2; exit 1; }
[ -f "$ENV_FILE" ] || fail "missing env file: $ENV_FILE"
command -v python3 >/dev/null 2>&1 || fail 'python3 is required'

# shellcheck disable=SC1090
set -a
. "$ENV_FILE"
set +a

STAGING_ROOT="${VOLTY_OFFLINE_STAGING_HOST_DIR:-/home/sodovaya/volty/offline-production/staging}"
SOURCE_ROOT="${VOLTY_OFFLINE_SOURCE_HOST_DIR:-/home/sodovaya/volty/offline-production/sources}"
CONFIG_PATH="${VOLTY_OFFLINE_CONFIG_HOST:-/home/sodovaya/volty/offline-production/production.json}"
INVENTORY_PATH="${VOLTY_OFFLINE_INVENTORY_HOST:-/home/sodovaya/volty/offline-production/inventory.json}"
QUEUE_PATH="${VOLTY_OFFLINE_QUEUE_HOST:-$STAGING_ROOT/jobs.json}"

for path in "$STAGING_ROOT" "$SOURCE_ROOT" "$(dirname -- "$CONFIG_PATH")" "$(dirname -- "$INVENTORY_PATH")"; do
  case "$path" in
    /|/home|/root|/var|/srv|/opt) fail "dedicated child directory required: $path" ;;
    /*) ;;
    *) fail "absolute path required: $path" ;;
  esac
done
install -d -m 755 "$STAGING_ROOT" "$SOURCE_ROOT" "$(dirname -- "$CONFIG_PATH")" "$(dirname -- "$INVENTORY_PATH")"

cd "$ROOT/tools/offline-navigation"
python3 -m production.bootstrap plan \
  --source-id "$SOURCE_ID" \
  --output "$INVENTORY_PATH"
python3 -m production.bootstrap enqueue \
  --inventory "$INVENTORY_PATH" \
  --queue "$QUEUE_PATH" \
  --production-config "$CONFIG_PATH"
printf '[offline-bootstrap] inventory=%s queue=%s config=%s\n' \
  "$INVENTORY_PATH" "$QUEUE_PATH" "$CONFIG_PATH"
printf '%s\n' '[offline-bootstrap] Next: provision the trusted signing key/keyId and real source metadata; deploy-production.sh will refuse to start before that.'
