# Offline region delivery

The Ktor API fronts a private package service built from
`tools/offline-navigation/Dockerfile.service`. Existing repository scripts can build
one configured region's Valhalla 3.6.3 routing data, SQLite FTS4 search and PMTiles,
and sign its manifest and a catalog. They are not yet connected to an automatic
country-wide build/publish scheduler or a configured production artifact origin.
The package service verifies a supplied catalog and acquires already-built releases
on demand. It does not generate maps or hold a private signing key.

Correction to the initial implementation report: an earlier revision described
the release pipeline as already uploading artifacts to an origin. That integration
was assumed, not implemented or verified. Setting the upstream ENV variables alone
does not provide country-wide offline coverage; automated dataset acquisition,
region partitioning, build/sign/publish orchestration and initial coverage are still
missing. The delivered subsystem is distribution of prebuilt packages.

The catalog includes supported regions whose artifacts are not yet cached on the
server. Android can therefore choose a region using its existing coverage policy,
request that exact signed release, wait for readiness, and use its existing signed
download/install path. An area absent from the upstream catalog is unsupported;
the service cannot manufacture coverage for it.

## Public API (same origin as the Android catalog)

| Request | Result |
| --- | --- |
| `GET /offline/catalog.json` | Original, verified signed catalog bytes |
| `GET /offline/resolve?lat=56.8&lon=60.6` | Smallest covering region, release and availability |
| `POST /offline/regions/{id}/ensure?releaseVersion={version}` | Start/deduplicate acquisition of the requested release |
| `GET /offline/regions/{id}/status?releaseVersion={version}` | Acquisition state for that release |
| `GET /offline/regions/{id}/{version}/manifest.json` | Signed immutable manifest of a fully published release |
| `GET /offline/regions/{id}/{version}/routing/valhalla-routing.tar.gz` | Routing artifact |
| `GET /offline/regions/{id}/{version}/search/places.sqlite.gz` | Search artifact |
| `GET /offline/regions/{id}/{version}/map/{id}.pmtiles` | Map artifact |

Acquisition responses contain `status`, `regionId`, `releaseVersion` and, when
appropriate, `errorCode` and `retryAfterSeconds`. States are `queued`,
`downloading`, `ready`, `failed`, and `unavailable`. Resolve also uses `available`
and `unsupported`. Versioned requests never silently switch to another release.
Component responses preserve `Range`, `Content-Range`, `Content-Length`, `ETag`
and cache headers without buffering the package in Ktor memory.

Invalid coordinates/identifiers return 400. Ktor limits ensure requests to 20 per
minute per directly connected peer (behind nginx this is the proxy peer), returns
429 with Retry-After, and returns 503 `offline_service_unavailable` when the worker
cannot be reached. Internal `/refresh` and `/prune` are deliberately absent from
the public Ktor API. The worker has no host port in Compose.

## Configuration

| Environment variable | Purpose |
| --- | --- |
| `VOLTY_OFFLINE_MANAGER_URL` | Ktor worker origin; `http://offline:8091` in Compose |
| `VOLTY_OFFLINE_UPSTREAM_CATALOG_URL` | HTTPS URL of the pipeline's signed catalog |
| `VOLTY_OFFLINE_ARTIFACT_BASE_URL` | Trusted HTTPS artifact origin/prefix containing `{id}/{version}/...` |
| `VOLTY_OFFLINE_PUBLIC_BASE_URL` | Prefix signed into component URLs, usually `https://volty.sodove.ru/offline/regions` |
| `VOLTY_OFFLINE_PUBLIC_KEY` | Base64 raw 32-byte Ed25519 public key, matching the Android trust anchor |
| `VOLTY_OFFLINE_KEY_ID` | Expected catalog and manifest signing key ID |
| `VOLTY_OFFLINE_HOST_DIR` | Dedicated persistent host store, default `/home/sodovaya/volty/offline` |
| `VOLTY_OFFLINE_ROOT` | Legacy Ktor read-only store path; worker uses `/data/offline` in Compose |
| `VOLTY_OFFLINE_INGEST_HOST_DIR` | Optional read-only pipeline ingress, default `/home/sodovaya/volty/offline-ingest` |
| `VOLTY_OFFLINE_INGEST_ROOT` | Worker ingress path, `/ingest` in Compose |

Signed public URLs are not rewritten. The upstream origin serves the same artifact
bytes under the validated public URL suffix. Catalog refresh never re-signs data:
publishing a new region/version remains the release pipeline's responsibility.

With all trust/upstream settings present, `deploy.sh` enables the `offline` Compose
profile and sets the worker URL when it is empty. The manual equivalent is to set
`VOLTY_OFFLINE_MANAGER_URL=http://offline:8091` and run
`docker compose --profile offline up -d --build`. Deployment was not run as part of
this change. Keep the worker network private; its control endpoints are for the
operator, not the public internet.

Without a manager URL, Ktor retains an allowlisted legacy static distribution
mode. Android only attempts the acquisition API for a catalog ending in
`/offline/catalog.json`; external CDN catalog layouts retain direct downloads.
Legacy static distribution cannot acquire missing packages.

The release pipeline, initial real signed catalog/artifacts, production trust-key
configuration, actual deployment, native map/routing smoke tests and device UI
validation remain operational inputs outside this code change. No APK or large
regional artifact is generated by this backend implementation.

## Publication, recovery and retention

The worker permits one process per cache root through an OS file lock. It writes
components to private staging, verifies the signed download and installed sizes,
SHA-256, bounded safe routing archive, Valhalla config references, FTS4 table and
metadata, and PMTiles v3 header/ranges. Only then does it rename the complete
release into `releases/{id}/{version}`. Public URLs still use `/offline/regions`;
they never expose the disk layout, staging or metadata files. Failed acquisition
keeps the previous ready release and catalog. A repeated ensure can retry it.

The catalog is cached atomically after validating the envelope and every manifest.
Periodic refresh failure keeps the last verified catalog. Startup discards staging
and verifies published file hashes before restoring readiness. A client retries an
interrupted acquisition with ensure. Small permanent release fingerprints prevent
reusing a versioned URL for different bytes even after its package is pruned.

Pruning retains every current catalog release and gives retired ready releases a
seven-day grace interval after removal from the catalog. Files already opened for
streaming survive a POSIX unlink; Windows defers deletion of open files. Cached
catalogs older than that grace can encounter `release_unavailable`; refresh the
catalog before retrying. Active packages are never evicted to make space: exhausted
budgets produce `storage_limit` and leave the catalog intact.

Worker limits can be set directly in its environment. Byte limits are integers:

| `VOLTY_OFFLINE_` suffix | Default |
| --- | --- |
| `MAX_DOWNLOAD_BYTES` | 8 GiB per package |
| `MAX_EXPANDED_BYTES` | 24 GiB per package |
| `MAX_CACHE_BYTES` | 64 GiB, including reserved validation space |
| `MIN_FREE_BYTES` | 1 GiB |
| `WORKERS` / `MAX_PENDING` | 2 / 8 |
| `MAX_CATALOG_BYTES` | 4 MiB |
| `PRUNE_GRACE_SECONDS` | 604800 |
| `REFRESH_SECONDS` | 900 |
| `REQUEST_TIMEOUT_SECONDS` / `DOWNLOAD_TIMEOUT_SECONDS` | 30 / 1800 |
| `PORT` | 8091 |

The stock Compose file uses these defaults. Apply overrides to the offline
service environment in a Compose override when needed. Ktor connection/read
timeouts are 5/30 seconds; Android preparation polling is cancellable and bounded
at 30 minutes and rechecks network permission before artifact transfer.

Operator operations run inside the already-running private worker:

```sh
docker compose --profile offline exec -T offline python package-service.py --refresh
docker compose --profile offline exec -T offline python package-service.py --prune
bash deploy-offline.sh --region ekb-agglomeration --release 0.1.2
```

The last command reads `/ingest/ekb-agglomeration/0.1.2/{routing,search,map}` and
waits for publication. It uses the signed upstream catalog as manifest authority,
rejects symlinks and verifies all bytes through the same acquisition path.
The earlier `--package/--catalog` deploy wrapper only checked signature envelopes;
that unsafe publication path has been replaced, not retained as a fallback.
Ingest is optional: ordinary client requests fetch pipeline artifacts over HTTPS.

`ensure` returns 202 while queued/downloading, 200 when ready, 404 with a structured
`unavailable` state for unknown/stale releases, and 503 for queue/storage limits.
`status` returns the state with 200. Error codes distinguish `artifact_checksum`,
`artifact_size`, `installed_size_mismatch`, `unsafe_routing_archive`,
`search_schema`, `search_metadata`, `pmtiles_header`, `storage_limit`, `queue_full`,
`upstream_unavailable` and `release_unavailable`. No file paths or credentials are
returned in public failure messages.
