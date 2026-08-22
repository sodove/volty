#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd "$SCRIPT_DIR"

ENV_EXAMPLE_FILE=".env.example"
ENV_FILE=".env"

log() {
  printf '[deploy] %s\n' "$*"
}

fail() {
  printf '[deploy] ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || fail "Required command not found: $command_name"
}

detect_compose() {
  require_command docker
  docker info >/dev/null 2>&1 || fail "Docker daemon is not available"

  if docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD=(docker compose)
    return
  fi

  if command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD=(docker-compose)
    return
  fi

  fail "Docker Compose is not available. Install docker compose v2 or docker-compose."
}

ensure_env_file() {
  [ -f "$ENV_EXAMPLE_FILE" ] || fail "Missing $ENV_EXAMPLE_FILE"

  if [ -f "$ENV_FILE" ]; then
    log "Using existing $ENV_FILE"
    return
  fi

  cp "$ENV_EXAMPLE_FILE" "$ENV_FILE"
  log "Created $ENV_FILE from $ENV_EXAMPLE_FILE"
}

read_env_value() {
  local key="$1"
  if [ ! -f "$ENV_FILE" ]; then
    return 0
  fi

  awk -F= -v key="$key" '
    $0 !~ /^[[:space:]]*#/ && $1 == key {
      sub(/^[^=]*=/, "", $0)
      sub(/\r$/, "", $0)
      print $0
      exit
    }
  ' "$ENV_FILE"
}

write_env_value() {
  local key="$1"
  local value="$2"
  local temp_file
  temp_file="$(mktemp)"

  awk -v key="$key" -v value="$value" '
    BEGIN {
      updated = 0
    }
    $0 ~ "^[[:space:]]*" key "=" {
      print key "=" value
      updated = 1
      next
    }
    {
      print
    }
    END {
      if (!updated) {
        print key "=" value
      }
    }
  ' "$ENV_FILE" >"$temp_file"

  mv "$temp_file" "$ENV_FILE"
}

is_placeholder_value() {
  local value="${1-}"
  case "$value" in
    ""|"replace-with-"*|"203.0.113.10"|"unconfigured")
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

generate_base64url_secret() {
  local requested_length="$1"
  local generated=""
  local bytes
  bytes=$((requested_length + 8))

  while [ "${#generated}" -lt "$requested_length" ]; do
    local chunk
    if command -v openssl >/dev/null 2>&1; then
      chunk="$(openssl rand -base64 "$bytes")"
    elif command -v python3 >/dev/null 2>&1; then
      chunk="$(python3 - "$bytes" <<'PY'
import base64
import os
import sys

size = int(sys.argv[1])
print(base64.b64encode(os.urandom(size)).decode("ascii"))
PY
)"
    elif [ -r /dev/urandom ] && command -v base64 >/dev/null 2>&1; then
      chunk="$(head -c "$bytes" /dev/urandom | base64)"
    else
      fail "Unable to generate secrets: need openssl, python3, or base64 with /dev/urandom"
    fi

    chunk="${chunk//$'\n'/}"
    chunk="${chunk//+/-}"
    chunk="${chunk//\//_}"
    chunk="${chunk//=}"
    generated+="$chunk"
  done

  printf '%s' "${generated:0:requested_length}"
}

ensure_generated_secret() {
  local key="$1"
  local length="$2"
  local current_value
  current_value="$(read_env_value "$key")"

  if is_placeholder_value "$current_value"; then
    write_env_value "$key" "$(generate_base64url_secret "$length")"
    log "Generated $key"
  else
    log "Keeping existing $key"
  fi
}

is_valid_ipv4() {
  local ip="$1"
  [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1

  local octet
  IFS='.' read -r -a octets <<<"$ip"
  for octet in "${octets[@]}"; do
    if [ "$octet" -lt 0 ] || [ "$octet" -gt 255 ]; then
      return 1
    fi
  done
}

discover_public_ipv4() {
  require_command curl

  local endpoint
  local detected_ip
  for endpoint in \
    "https://api.ipify.org" \
    "https://ipv4.icanhazip.com" \
    "https://ifconfig.me/ip"; do
    detected_ip="$(curl --silent --show-error --fail --max-time 10 "$endpoint" 2>/dev/null || true)"
    detected_ip="${detected_ip//$'\r'/}"
    detected_ip="${detected_ip//$'\n'/}"
    if is_valid_ipv4 "$detected_ip"; then
      printf '%s\n' "$detected_ip"
      return 0
    fi
  done

  return 1
}

ensure_public_ip() {
  local configured_ip="${VOLTY_PUBLIC_IP-}"
  if [ -z "$configured_ip" ]; then
    configured_ip="$(read_env_value "VOLTY_PUBLIC_IP")"
  fi

  if ! is_placeholder_value "$configured_ip" && is_valid_ipv4 "$configured_ip"; then
    write_env_value "VOLTY_PUBLIC_IP" "$configured_ip"
    log "Using configured VOLTY_PUBLIC_IP"
    return
  fi

  local discovered_ip
  discovered_ip="$(discover_public_ipv4)" || fail "Unable to discover public IPv4. Set VOLTY_PUBLIC_IP and rerun."
  write_env_value "VOLTY_PUBLIC_IP" "$discovered_ip"
  log "Discovered VOLTY_PUBLIC_IP automatically"
}

ensure_non_secret_defaults() {
  local current_provider
  current_provider="$(read_env_value "VOLTY_VOICE_PROVIDER")"
  if [ "$current_provider" != "livekit" ]; then
    write_env_value "VOLTY_VOICE_PROVIDER" "livekit"
    log "Set VOLTY_VOICE_PROVIDER=livekit"
  else
    log "VOLTY_VOICE_PROVIDER already set to livekit"
  fi

  local current_cors
  current_cors="$(read_env_value "VOLTY_CORS_ORIGINS")"
  if is_placeholder_value "$current_cors"; then
    write_env_value "VOLTY_CORS_ORIGINS" "https://volty.sodove.ru"
  fi

  local current_livekit_url
  current_livekit_url="$(read_env_value "LIVEKIT_URL")"
  if is_placeholder_value "$current_livekit_url"; then
    write_env_value "LIVEKIT_URL" "wss://voice.sodove.ru"
  fi

  local current_ttl
  current_ttl="$(read_env_value "VOLTY_VOICE_TOKEN_TTL_SECONDS")"
  if [ -z "$current_ttl" ]; then
    write_env_value "VOLTY_VOICE_TOKEN_TTL_SECONDS" "300"
  fi
}

wait_for_service_status() {
  local service_name="$1"
  local expected_status="$2"
  local timeout_seconds="$3"
  local started_at="$SECONDS"
  local container_id=""
  local current_status=""

  while [ $((SECONDS - started_at)) -lt "$timeout_seconds" ]; do
    container_id="$("${COMPOSE_CMD[@]}" ps -q "$service_name" 2>/dev/null || true)"
    if [ -n "$container_id" ]; then
      current_status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id" 2>/dev/null || true)"
      if [ "$current_status" = "$expected_status" ]; then
        log "$service_name is $current_status"
        return 0
      fi
    fi
    sleep 2
  done

  "${COMPOSE_CMD[@]}" ps || true
  fail "Timed out waiting for $service_name to become $expected_status (last status: ${current_status:-unknown})"
}

main() {
  local -a COMPOSE_CMD=()

  detect_compose
  ensure_env_file
  ensure_generated_secret "POSTGRES_PASSWORD" 32
  ensure_generated_secret "VOLTY_JWT_SECRET" 64
  ensure_generated_secret "LIVEKIT_API_KEY" 24
  ensure_generated_secret "LIVEKIT_API_SECRET" 48
  ensure_public_ip
  ensure_non_secret_defaults

  log "Starting Docker Compose deployment"
  "${COMPOSE_CMD[@]}" up -d --remove-orphans --build

  wait_for_service_status "db" "healthy" 180
  wait_for_service_status "app" "healthy" 240
  wait_for_service_status "livekit" "running" 120

  log "Deployment status"
  "${COMPOSE_CMD[@]}" ps
}

main "$@"
