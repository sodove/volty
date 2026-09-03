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

Build the HTTPS catalog from signed manifests with a small metadata spec. Paths
inside the spec are relative to that spec file:

```json
{
  "regions": [
    {
      "regionId": "ekb-agglomeration",
      "displayName": "Екатеринбург и окрестности",
      "bounds": [59.10, 56.00, 61.90, 57.55],
      "manifest": "ekb-package-v0.1.1/manifest.json"
    }
  ]
}
```

```bash
python3 build-catalog.py --spec regions.json --output catalog.json \
  --public-key /secure/volty-regions-ed25519.public.b64 \
  --private-key /secure/volty-regions-ed25519.pem \
  --key-id volty-regions-2026 \
  --current-app-version-code 28
```

Only signed manifests are accepted. The publisher verifies every manifest
against the expected Ed25519 public key and key ID, then applies the same
schema, Valhalla engine/data version, app-version, artifact, HTTPS, map, and
search compatibility gates used by the Android package policy. It also rejects
duplicate regions, invalid bounds, mismatched IDs, and logical bounds outside
the signed release coverage. The resulting catalog is signed with the same
key, and the app verifies that signature before publishing catalog regions into
local state; each release manifest is then verified independently before
installation.

The current pilot bbox is the EKB agglomeration with a routing buffer:
`59.10,56.00,61.90,57.55` (west,south,east,north). `osmium extract --strategy=smart`
may include complete ways outside that bbox; the manifest coverage remains the
published logical region and must be checked before release.

The default Valhalla image, PMTiles converter, and Ubuntu tool image are pinned
by digest in `build-package.sh`/`Dockerfile`. A release may override them only
with an explicitly reviewed digest. Do not commit downloaded tiles or signing
keys to this repository.

The routing build also runs the pinned image's `valhalla_build_timezones` helper
and includes the generated `timezones.sqlite` in the routing archive. That one
helper uses the host network because the Docker bridge on the build host cannot
reach its upstream boundary archive; the rest of the Valhalla build remains on
the ordinary Docker network. `verify-package.py` rejects a routing archive that
is missing any required database/archive or that does not reference those files
from `valhalla.json`.

The app consumes the already-built Android `io.github.rallista:valhalla-mobile:0.6.3`
AAR. The pinned amd64 Valhalla 3.6.3 image below is only the host-side tile
compiler used to turn OSM data into the regional extract; it does not rebuild
the mobile runtime. If the mobile engine changes, pass an explicit
`--routing-data-version` and reviewed `VALHALLA_IMAGE` together so the manifest
cannot silently describe data built by another engine version.

For a production APK, pass the explicit Gradle gate together with the real
HTTPS catalog, the Base64 raw 32-byte Ed25519 public key, its key ID, and the
release keystore secrets:

```powershell
.\gradlew.bat :composeApp:assembleRelease `
  -PvoltyProductionRelease=true `
  -PvoltyOfflineCatalogUrl=https://cdn.example/volty/catalog.json `
  -PvoltyOfflineManifestKeyId=volty-regions-2026 `
  -PvoltyOfflineManifestPublicKey=<base64-public-key>
```

Without `voltyProductionRelease=true`, ordinary development builds may keep
the offline catalog inert; that mode must not be used for a production rollout.
