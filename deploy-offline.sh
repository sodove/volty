#!/usr/bin/env bash
# Publish pipeline artifacts through the same verifier/atomic manager as automatic acquisition.
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd "$SCRIPT_DIR"
REGION=""
RELEASE=""
usage() {
  cat <<'USAGE'
Usage: bash deploy-offline.sh --region ID --release VERSION

Ingest /ingest/ID/VERSION from the configured VOLTY_OFFLINE_INGEST_HOST_DIR
read-only mount. First refreshes the signed upstream catalog; that catalog is
the authority for manifest signatures, component hashes, sizes and public URLs.
Waits for verified atomic publication, exits nonzero on failure.

The offline service must already be running. This command does not deploy
containers, build maps, replace a catalog manually, or accept arbitrary paths.
Ordinary client requests acquire the same packages automatically from the
configured artifact origin, without this operator command.
USAGE
}
while [ "$#" -gt 0 ]; do
  case "$1" in
    --region) [ "$#" -ge 2 ] || exit 2; REGION="$2"; shift 2 ;;
    --release) [ "$#" -ge 2 ] || exit 2; RELEASE="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
[[ "$REGION" =~ ^[a-z0-9][a-z0-9._-]{0,63}$ && "$REGION" != *..* ]] || { usage >&2; exit 2; }
[[ "$RELEASE" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ && "$RELEASE" != *..* ]] || { usage >&2; exit 2; }
exec docker compose --profile offline exec -T offline python package-service.py --refresh --ingest "$REGION" --release "$RELEASE"