#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd -P)"
OUTPUT="${1:?usage: make-bundle.sh OUTPUT.tgz}"
OUTPUT="$(readlink -m "$OUTPUT")"
mkdir -p "$(dirname -- "$OUTPUT")"

# Build contexts are intentionally explicit. This excludes credentials, keys,
# PBF/package/APK outputs, Gradle caches, generated emulator captures and git.
tar -czf "$OUTPUT" -C "$ROOT" \
  --exclude='backend/.gradle' --exclude='backend/.kotlin' --exclude='backend/build' \
  --exclude='tools/offline-navigation/__pycache__' --exclude='tools/offline-navigation/production/tests' \
  --exclude='*.pbf' --exclude='*.osm.pbf' --exclude='*.tar.gz' --exclude='*.pmtiles' \
  --exclude='.env' --exclude='*.jks' --exclude='*.keystore' --exclude='keystore.properties' \
  --exclude='*.apk' --exclude='*.aab' --exclude='.git' \
  backend tools/offline-navigation docker-compose.yml deploy.sh deploy-offline.sh \
  .env.example livekit.yaml nginx
chmod 600 "$OUTPUT"
echo "bundle written to $OUTPUT"
