# Offline navigation package tooling

This directory contains the reproducible regional-artifact toolchain. It is
intentionally separate from the Android build: the APK does not contain a
large regional dataset.

The build host needs Docker and an OSM `.osm.pbf` source. The expected output
components are:

- `routing/valhalla-routing.tar.gz` — Valhalla tile extract, admin data, and
  engine config;
- `search/places.sqlite.gz` — compressed SQLite FTS4 offline geocoder;
- `map/ekb.pmtiles` — PMTiles vector map;
- `manifest.unsigned.json` — sizes and SHA-256 checksums for signing by the
  release pipeline.

The current pilot bbox is the EKB agglomeration with a routing buffer:
`59.10,56.00,61.90,57.55` (west,south,east,north). `osmium extract --strategy=smart`
may include complete ways outside that bbox; the manifest coverage remains the
published logical region and must be checked before release.

The default Valhalla image, PMTiles converter, and Ubuntu tool image are pinned
by digest in `build-package.sh`/`Dockerfile`. A release may override them only
with an explicitly reviewed digest. Do not commit downloaded tiles or signing
keys to this repository.
