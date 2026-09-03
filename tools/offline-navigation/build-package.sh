#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 INPUT.osm.pbf OUTPUT_DIR --release-version VERSION --min-app-version-code CODE --osm-sequence N --osm-timestamp ISO-8601 [options]" >&2
  echo "Options: --bbox west,south,east,north --region-id ID --routing-buffer-km N --routing-data-version VERSION --base-url URL" >&2
}

if [[ $# -lt 2 ]]; then usage; exit 2; fi

INPUT=$(readlink -f "$1")
OUTPUT=$(readlink -f "$2")
shift 2

BBOX="59.10,56.00,61.90,57.55"
REGION_ID="ekb-agglomeration"
MAP_FILE="${REGION_ID}.pmtiles"
ROUTING_BUFFER_KM=20
BASE_URL="https://cdn.example.invalid/volty/regions"
RELEASE_VERSION=""
MIN_APP_VERSION_CODE=""
OSM_SEQUENCE=""
OSM_TIMESTAMP=""
TOOLS_IMAGE="${TOOLS_IMAGE:-volty/offline-tools:20260903}"
# The app uses the already-built valhalla-mobile 0.6.3 Android AAR.
# This amd64 image is only the reproducible Linux-side OSM tile compiler;
# it must match the engine's tile format, but it does not build the AAR.
VALHALLA_IMAGE="${VALHALLA_IMAGE:-ghcr.io/valhalla/valhalla@sha256:0cf1520c6a38b8a7e13a1931541e0ab6e9e42b64b4ca014293b6b8373d493160}"
ROUTING_DATA_VERSION="${ROUTING_DATA_VERSION:-valhalla-3.6.3}"
PMTILES_IMAGE="${PMTILES_IMAGE:-protomaps/go-pmtiles@sha256:a52c195560a656b8309311a7d591b90eb2c5ae55ec9111f26049371d86a22a69}"
THREADS="${THREADS:-6}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bbox) BBOX="$2"; shift 2 ;;
    --region-id) REGION_ID="$2"; shift 2 ;;
    --routing-buffer-km) ROUTING_BUFFER_KM="$2"; shift 2 ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    --release-version) RELEASE_VERSION="$2"; shift 2 ;;
    --min-app-version-code) MIN_APP_VERSION_CODE="$2"; shift 2 ;;
    --routing-data-version) ROUTING_DATA_VERSION="$2"; shift 2 ;;
    --osm-sequence) OSM_SEQUENCE="$2"; shift 2 ;;
    --osm-timestamp) OSM_TIMESTAMP="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

MAP_FILE="${REGION_ID}.pmtiles"

[[ -f "$INPUT" ]] || { echo "Input PBF does not exist: $INPUT" >&2; exit 1; }
[[ -n "$RELEASE_VERSION" && -n "$MIN_APP_VERSION_CODE" && -n "$OSM_SEQUENCE" && -n "$OSM_TIMESTAMP" ]] || {
  echo "Release version, app version code, OSM sequence, and OSM timestamp are required" >&2; exit 2;
}
[[ "$ROUTING_BUFFER_KM" =~ ^[0-9]+$ ]] || {
  echo "Routing buffer must be a non-negative integer number of kilometres" >&2; exit 2;
}
[[ ! -e "$OUTPUT" ]] || { echo "Refusing to overwrite existing output: $OUTPUT" >&2; exit 1; }

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROUTING_BBOX=$(python3 "$SCRIPT_DIR/expand-bbox.py" "$BBOX" "$ROUTING_BUFFER_KM")
docker build -t "$TOOLS_IMAGE" "$SCRIPT_DIR"

PARENT=$(dirname "$INPUT")
INPUT_NAME=$(basename "$INPUT")
STAGING=$(mktemp -d)
cleanup() { rm -rf "$STAGING"; }
trap cleanup EXIT
mkdir -p "$STAGING/installed/routing/tiles" "$STAGING/installed/search" "$STAGING/installed/map" \
  "$STAGING/artifacts/routing" "$STAGING/artifacts/search" "$STAGING/artifacts/map" \
  "$STAGING/search"

tools_run() {
  docker run --rm --user "$(id -u):$(id -g)" \
    -v "$PARENT:/input:ro" -v "$STAGING:/work" -v "$SCRIPT_DIR:/tooling:ro" \
    "$TOOLS_IMAGE" "$@"
}

valhalla_run() {
  docker run --rm --user "$(id -u):$(id -g)" \
    -v "$STAGING:/work" -v "$PARENT:/input:ro" \
    "$VALHALLA_IMAGE" "$@"
}

valhalla_timezone_run() {
  docker run --rm --network host --workdir /work --user "$(id -u):$(id -g)" \
    -v "$STAGING:/work" -v "$PARENT:/input:ro" \
    "$VALHALLA_IMAGE" "$@"
}

echo "Extracting logical region for map and search"
tools_run osmium extract --bbox "$BBOX" --strategy=smart \
  "/input/$INPUT_NAME" -o /work/region.osm.pbf

echo "Extracting routing-only region with buffer"
echo "Logical bbox: $BBOX; routing bbox: $ROUTING_BBOX"
tools_run osmium tags-filter \
  "/input/$INPUT_NAME" nwr/highway route=ferry type=restriction \
  -o /work/routing-source.osm.pbf
tools_run osmium extract --bbox "$ROUTING_BBOX" --strategy=complete_ways \
  /work/routing-source.osm.pbf -o /work/routing.osm.pbf
rm -f "$STAGING/routing-source.osm.pbf"

echo "Building Valhalla routing component"
valhalla_timezone_run valhalla_build_timezones > "$STAGING/installed/routing/timezones.sqlite"
valhalla_run valhalla_build_config \
  --mjolnir-tile-dir /work/installed/routing/tiles \
  --mjolnir-tile-extract /work/installed/routing/tiles.tar \
  --mjolnir-admin /work/installed/routing/admins.sqlite \
  --mjolnir-timezone /work/installed/routing/timezones.sqlite > "$STAGING/installed/routing/valhalla.json"
# Administrative boundaries come from the logical extract. The routing-only
# input deliberately omits multipolygon/admin relations to keep its graph
# bounded, but the package still needs a useful admins.sqlite.
valhalla_run valhalla_build_admins -c /work/installed/routing/valhalla.json /work/region.osm.pbf
valhalla_run valhalla_build_tiles -c /work/installed/routing/valhalla.json -j "$THREADS" /work/routing.osm.pbf
valhalla_run valhalla_build_extract -c /work/installed/routing/valhalla.json -v
sed -i 's#/work/installed/routing#/work#g' "$STAGING/installed/routing/valhalla.json"
tar -czf "$STAGING/artifacts/routing/valhalla-routing.tar.gz" \
  -C "$STAGING/installed/routing" tiles.tar admins.sqlite timezones.sqlite valhalla.json
rm -rf "$STAGING/installed/routing/tiles"

echo "Building FTS4 search component"
tools_run env REGION_ID="$REGION_ID" bash -lc 'set -e; osmium tags-filter /work/region.osm.pbf nwr/name nwr/addr:street -o /work/search/named.osm.pbf; osmium export /work/search/named.osm.pbf --geometry-types=point,linestring,polygon -o /work/search/named.geojson; python3 /tooling/build-search.py /work/search/named.geojson /work/installed/search/places.sqlite --region-id "$REGION_ID"'
gzip -9 -c "$STAGING/installed/search/places.sqlite" > "$STAGING/artifacts/search/places.sqlite.gz"
rm -rf "$STAGING/search"

echo "Building PMTiles map component"
tools_run tilemaker --input /work/region.osm.pbf --output /work/map.mbtiles \
  --config /tooling/config.json --process /tooling/process.lua --threads "$THREADS"
tools_run sqlite3 /work/map.mbtiles \
  "UPDATE metadata SET value='$BBOX' WHERE name='bounds'; UPDATE metadata SET value='60.605,56.839,9' WHERE name='center';"
docker run --rm --user "$(id -u):$(id -g)" -v "$STAGING:/work" "$PMTILES_IMAGE" \
  convert /work/map.mbtiles "/work/artifacts/map/$MAP_FILE"
docker run --rm -v "$STAGING:/work:ro" "$PMTILES_IMAGE" verify "/work/artifacts/map/$MAP_FILE"
cp "$STAGING/artifacts/map/$MAP_FILE" "$STAGING/installed/map/$MAP_FILE"

python3 "$SCRIPT_DIR/build-manifest.py" \
  --output "$STAGING/manifest.unsigned.json" \
  --routing "$STAGING/artifacts/routing/valhalla-routing.tar.gz" \
  --routing-installed "$STAGING/installed/routing" \
  --search "$STAGING/artifacts/search/places.sqlite.gz" \
  --search-installed "$STAGING/installed/search" \
  --map "$STAGING/artifacts/map/$MAP_FILE" \
  --map-installed "$STAGING/installed/map" \
  --region-id "$REGION_ID" --release-version "$RELEASE_VERSION" \
  --min-app-version-code "$MIN_APP_VERSION_CODE" --osm-sequence "$OSM_SEQUENCE" \
  --routing-data-version "$ROUTING_DATA_VERSION" --osm-timestamp "$OSM_TIMESTAMP" \
  --bbox "$BBOX" --routing-buffer-km "$ROUTING_BUFFER_KM" \
  --base-url "$BASE_URL"

mkdir -p "$STAGING/routing" "$STAGING/search" "$STAGING/map"
mv "$STAGING/artifacts/routing/valhalla-routing.tar.gz" "$STAGING/routing/"
mv "$STAGING/artifacts/search/places.sqlite.gz" "$STAGING/search/"
mv "$STAGING/artifacts/map/$MAP_FILE" "$STAGING/map/"
rm -rf "$STAGING/artifacts" "$STAGING/installed" "$STAGING/region.osm.pbf" \
  "$STAGING/routing.osm.pbf" "$STAGING/map.mbtiles"
python3 "$SCRIPT_DIR/verify-package.py" "$STAGING"
mv "$STAGING" "$OUTPUT"
trap - EXIT
echo "Regional package written to $OUTPUT"
