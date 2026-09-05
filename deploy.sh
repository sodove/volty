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

remove_env_key() {
  local key="$1"
  local temp_file
  temp_file="$(mktemp)"
  awk -F= -v key="$key" '$1 != key { print }' "$ENV_FILE" >"$temp_file"
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

  local current_navigation_provider
  current_navigation_provider="$(read_env_value "VOLTY_NAV_PROVIDER")"
  if [ -z "$current_navigation_provider" ]; then
    write_env_value "VOLTY_NAV_PROVIDER" "disabled"
  elif [ "$current_navigation_provider" != "disabled" ]; then
    write_env_value "VOLTY_NAV_PROVIDER" "disabled"
    log "Reset unsupported online navigation provider to disabled"
  fi

  local retired_navigation_key
  for retired_navigation_key in \
    "GRAPHHOPPER_API_KEY" \
    "VOLTY_NAV_PROFILE" \
    "VOLTY_NAV_PROFILE_MOTORCYCLE" \
    "VOLTY_NAV_PROFILE_BICYCLE" \
    "VOLTY_NAV_PROFILE_PEDESTRIAN" \
    "VOLTY_NAV_CONNECT_TIMEOUT_MILLIS" \
    "VOLTY_NAV_REQUEST_TIMEOUT_MILLIS"; do
    if [ -n "$(read_env_value "$retired_navigation_key")" ]; then
      remove_env_key "$retired_navigation_key"
      log "Removed retired navigation setting $retired_navigation_key"
    fi
  done

  local current_navigation_enabled
  current_navigation_enabled="$(read_env_value "VOLTY_NAVIGATION_ENABLED")"
  if [ -z "$current_navigation_enabled" ] || [ "$current_navigation_enabled" != "false" ]; then
    write_env_value "VOLTY_NAVIGATION_ENABLED" "false"
    log "Set VOLTY_NAVIGATION_ENABLED=false"
  fi

  local current_offline_host_dir
  current_offline_host_dir="$(read_env_value "VOLTY_OFFLINE_HOST_DIR")"
  if [ -z "$current_offline_host_dir" ]; then
    write_env_value "VOLTY_OFFLINE_HOST_DIR" "/srv/volty/offline"
  fi

  local current_offline_root
  current_offline_root="$(read_env_value "VOLTY_OFFLINE_ROOT")"
  if [ -z "$current_offline_root" ]; then
    write_env_value "VOLTY_OFFLINE_ROOT" "/opt/volty/offline"
  fi
}

ensure_offline_storage() {
  local offline_host_dir
  offline_host_dir="${VOLTY_OFFLINE_HOST_DIR-}"
  if [ -z "$offline_host_dir" ]; then
    offline_host_dir="$(read_env_value "VOLTY_OFFLINE_HOST_DIR")"
  fi
  offline_host_dir="${offline_host_dir:-/srv/volty/offline}"
  case "$offline_host_dir" in
    ""|"/"|"/srv"|"/var"|"/opt"|"/home"|"/root") fail "VOLTY_OFFLINE_HOST_DIR must name a dedicated child directory" ;;
    /*) ;;
    *) fail "VOLTY_OFFLINE_HOST_DIR must be an absolute host path" ;;
  esac
  mkdir -p "$offline_host_dir/regions" "$offline_host_dir/releases"
  chmod 755 "$offline_host_dir" "$offline_host_dir/regions" "$offline_host_dir/releases"
  log "Offline storage ready at $offline_host_dir"
}

configure_offline_manager() {
  local upstream="${VOLTY_OFFLINE_UPSTREAM_CATALOG_URL:-$(read_env_value VOLTY_OFFLINE_UPSTREAM_CATALOG_URL)}"
  [ -n "$upstream" ] || return 0
  local key value
  for key in VOLTY_OFFLINE_ARTIFACT_BASE_URL VOLTY_OFFLINE_PUBLIC_KEY VOLTY_OFFLINE_KEY_ID; do
    value="${!key:-}"
    value="${value:-$(read_env_value "$key")}"
    [ -n "$value" ] || fail "$key is required when automatic offline acquisition is enabled"
  done
  if [ -z "${VOLTY_OFFLINE_MANAGER_URL:-$(read_env_value VOLTY_OFFLINE_MANAGER_URL)}" ]; then
    write_env_value VOLTY_OFFLINE_MANAGER_URL http://offline:8091
  fi
  COMPOSE_CMD+=(--profile offline)
  local offline_host_dir="${VOLTY_OFFLINE_HOST_DIR:-$(read_env_value VOLTY_OFFLINE_HOST_DIR)}"
  offline_host_dir="${offline_host_dir:-/srv/volty/offline}"
  # Dedicated cache directories only; the worker runs as UID/GID 10001.
  # ensure_offline_storage has already validated this absolute child directory.
  install -d -m 755 -o 10001 -g 10001 "$offline_host_dir" "$offline_host_dir/releases" "$offline_host_dir/.staging"
  log "Automatic offline acquisition enabled from the configured signed catalog"
}

verify_local_http() {
  require_command curl
  local app_host_port
  app_host_port="${VOLTY_APP_HOST_PORT-}"
  if [ -z "$app_host_port" ]; then
    app_host_port="$(read_env_value "VOLTY_APP_HOST_PORT")"
  fi
  app_host_port="${app_host_port:-18080}"
  curl --silent --show-error --fail --max-time 10 "http://127.0.0.1:${app_host_port}/health" >/dev/null \
    || fail "Ktor health endpoint is not reachable on 127.0.0.1:${app_host_port}"

  local offline_host_dir
  offline_host_dir="${VOLTY_OFFLINE_HOST_DIR-}"
  if [ -z "$offline_host_dir" ]; then
    offline_host_dir="$(read_env_value "VOLTY_OFFLINE_HOST_DIR")"
  fi
  offline_host_dir="${offline_host_dir:-/srv/volty/offline}"
  if [ -f "$offline_host_dir/catalog.json" ]; then
    curl --silent --show-error --fail --max-time 10 "http://127.0.0.1:${app_host_port}/offline/catalog.json" >/dev/null \
      || fail "Offline catalog is not reachable through Ktor"
    log "Ktor offline catalog is reachable"
  else
    log "Offline catalog is not cached yet; configure the signed upstream catalog for automatic acquisition"
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
  ensure_offline_storage
  configure_offline_manager

  log "Starting Docker Compose deployment"
  # Scope updates to this Compose file; unrelated services in the existing
  # project are not orphans owned by this deployment.
  "${COMPOSE_CMD[@]}" up -d --build

  wait_for_service_status "db" "healthy" 180
  wait_for_service_status "app" "healthy" 240
  wait_for_service_status "livekit" "running" 120
  verify_local_http

  log "Deployment status"
  "${COMPOSE_CMD[@]}" ps
}

main "$@"
