# Offline navigation package tooling

This directory contains the reproducible regional-artifact toolchain. It is
intentionally separate from the Android build: the APK does not contain a
large regional dataset.

The build host needs Docker and an OSM `.osm.pbf` source. The expected output
components are:

- `routing/valhalla-routing.tar.gz` — Valhalla tile extract, admin data, and
  engine config;
- `search/places.sqlite.gz` — compressed SQLite FTS4 offline geocoder;
- `map/<region-id>.pmtiles` — PMTiles vector map;
- `manifest.unsigned.json` — sizes and SHA-256 checksums for signing by the
  release pipeline.

Sign a package outside the repository with an external unencrypted Ed25519 PEM
key (the APK's `123.jks` is a different key and is not used here):

```bash
python3 sign-manifest.py package/manifest.unsigned.json package/manifest.json \
  --private-key /secure/volty-regions-ed25519.pem \
  --key-id volty-regions-2026 \
  --public-key-output /secure/volty-regions-ed25519.public.b64
```

The signer removes the placeholder signature, signs the same compact UTF-8
payload used by the Android verifier, verifies the signature before publishing,
and writes only the raw public key when explicitly requested. Private keys and
signed package artifacts must stay outside git.

The current pilot bbox is the EKB agglomeration with a routing buffer:
`59.10,56.00,61.90,57.55` (west,south,east,north). `osmium extract --strategy=smart`
may include complete ways outside that bbox; the manifest coverage remains the
published logical region and must be checked before release.

The default Valhalla image, PMTiles converter, and Ubuntu tool image are pinned
by digest in `build-package.sh`/`Dockerfile`. A release may override them only
with an explicitly reviewed digest. Do not commit downloaded tiles or signing
keys to this repository.
