# Volty offline production bundle

This bundle is copyable to the existing VPS, but it does not invent source
metadata or geographic coverage. The worker publishes navigation-only v3
releases: Valhalla routing and SQLite FTS4 search. OpenFreeMap (OFM) basemap
packs are a client-side MapLibre resource, downloaded by the Android app from
the canonical OFM style endpoint for the selected region; they are not built,
stored, or served by this backend. The signing key is an external secret and
is never stored in the checkout.

## Install

1. Copy the repository files (including `tools/offline-navigation`) to the
   existing deployment checkout. Do not copy `.env`, private keys, PBF files,
   packages, APKs, Gradle/build directories, or generated emulator captures.
2. Create the three dedicated host directories from `.env`. Generate the
   inventory from the public Geofabrik index; do not manually enumerate the
   Russian regions or copy a generated queue from a laptop. The command is
   shown below and produces a host-visible `production.json` so the worker's
   Docker-socket build can mount the same source, staging, and build paths as
   the VPS host. Replace its explicit key placeholder only after the real
   Ed25519 key has been provisioned.
3. Place the existing Ed25519 signing key at
   `VOLTY_OFFLINE_SIGNING_KEY_HOST` with mode `0600` and verify its key id/public
   key matches the already installed client. Never rotate it silently.
4. Set `VOLTY_OFFLINE_HOST_DIR`, staging/source paths, config/key paths, and
   normal application secrets in `.env`. Keep the signing key outside every
   data root. The worker requires a Docker socket group id in
   `VOLTY_DOCKER_GID` when the host does not use the default `999`, and
   `VOLTY_DOCKER_CLI_HOST` if the host Docker CLI is not `/usr/bin/docker`.
   Set the worker UID/GID to the owner of the dedicated directories.

## Start and operate

From the checkout root, set `VOLTY_OFFLINE_MANAGER_URL=http://offline:8091`
and run the on-demand deployment:

```sh
bash tools/offline-navigation/ops/deploy-production.sh /path/to/.env
bash tools/offline-navigation/ops/status.sh /path/to/.env
```

The deploy script validates Compose without printing resolved secrets, builds
the offline delivery and worker services, and updates only the package service,
on-demand worker, and application. It does not use `--remove-orphans`, does
not start the scheduler, and does not restart the database or voice service.

The worker exposes its build-control endpoint only on the internal Compose
network at `http://offline-worker:8092`. It starts in `--on-demand-only` mode
and ignores legacy queued jobs left by the old publisher. The package service
uses it when a catalog entry has `onDemand.enabled=true` and no
`latestRelease`. The endpoint accepts only a configured region id; source URLs,
paths, timestamps, and keys never come from the phone. The scheduler remains a
separate service and is intentionally not started by the manual deployment
command.

The bootstrap creates the region inventory from the public Geofabrik index; do
not hand-write thousands of regions. On the VPS, run it from the checkout
before enabling the offline profile (it needs only Python and HTTPS):

```sh
cd /home/sodovaya/volty/tools/offline-navigation
install -d -m 755 /home/sodovaya/volty/offline-production/{staging,sources,secrets}
python3 -m production.bootstrap plan \
  --source-id russia \
  --output /home/sodovaya/volty/offline-production/inventory.json
python3 -m production.bootstrap enqueue \
  --inventory /home/sodovaya/volty/offline-production/inventory.json \
  --queue /home/sodovaya/volty/offline-production/staging/jobs.json \
  --production-config /home/sodovaya/volty/offline-production/production.json \
  --runtime-root /home/sodovaya/volty
```

The scheduler writes durable queue entries to the staging volume. The queue
deduplicates the downloaded PBF by `sourceId`; record metadata once per
distinct public extract at `<sourceRoot>/<sourceId>.source.json`:

```json
{"osmSequence": 123, "osmTimestamp": "2026-09-05T00:00:00Z", "geometryHash": "sha256-of-the-accepted-source-geometry"}
```

The worker downloads the configured public Geofabrik PBF with HTTPS/SSRF
checks, runs the existing pinned navigation-only build pipeline in a
host-visible unique attempt directory, verifies routing and search, signs the
v3 manifest with the external key, and atomically publishes the release plus
`catalog.json`. Missing metadata is a failed job, never a fabricated
timestamp. A failed attempt never becomes ready. Geofabrik is an explicit raw
OSM input for navigation artifacts only; it is not the OFM basemap source.

This bundle schedules only the canonical regions explicitly present in
`production.json`; it does not claim schema-3 anonymous discovery or generate
an arbitrary foreign region from an Android request.

## Migration note

The v3 contract has no map component: only routing and search are published by
this backend. Legacy PMTiles packages and old client-side map assets are
ignored by the v3 installer and are not deleted by this migration. Physical
cleanup remains gated on the Task 9 Android/device acceptance, so an old
package directory must never be treated as evidence that the backend still
serves a map artifact.

```sh
bash tools/offline-navigation/ops/backup.sh /path/to/.env /home/sodovaya/volty/backups
```

The backup is a local protected copy and is not disaster recovery until copied
to an independent approved target. `restore-check.sh` and `rollback.sh` refuse
to guess a production database or generation; use the documented separate temp
DB and v3 publisher workflow once those components are deployed.

## Current blockers

The checkout is synchronized on the VPS at `/home/sodovaya/volty`, the existing
app health check passed, and the Ed25519 signing key is provisioned outside Git.
The remaining operational gate is one real source-metadata record per queued
source plus a successful regional build/publication. Those values must come
from the trusted release/source process; this repository does not fabricate
them.

Full Russia package builds, four foreign cold builds, schema-3 discovery,
native offline APK smoke, restore, and rollback remain unverified. Do not call
the system production-ready until those gates have fresh evidence.
