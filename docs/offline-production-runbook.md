# Volty offline production bundle

This bundle is copyable to the existing VPS, but it does not invent a catalog,
signing key, source metadata, or geographic coverage. It fails before starting
the worker until those inputs are present.

## Install

1. Copy the repository files (including `tools/offline-navigation`) to the
   existing deployment checkout. Do not copy `.env`, private keys, PBF files,
   packages, APKs, Gradle/build directories, or generated emulator captures.
2. Create the three dedicated host directories from `.env`. Generate the
   inventory from the public Geofabrik index; do not manually enumerate the
   Russian regions or copy a generated queue from a laptop. The command is
   shown below and produces the container-path `production.json` used by the
   worker and scheduler. Replace its explicit key placeholder only after the
   real Ed25519 key has been provisioned.
3. Place the existing Ed25519 signing key at
   `VOLTY_OFFLINE_SIGNING_KEY_HOST` with mode `0600` and verify its key id/public
   key matches the already installed client. Never rotate it silently.
4. Set `VOLTY_OFFLINE_HOST_DIR`, staging/source paths, config/key paths, and
   normal application secrets in `.env`. Keep the signing key outside every
   data root. The worker requires a Docker socket group id in
   `VOLTY_DOCKER_GID` when the host does not use the default `999`.

## Start and operate

From the checkout root (leave `VOLTY_OFFLINE_MANAGER_URL` empty for this local
publisher/static-catalog mode; the optional legacy relay service is a separate
deployment mode):

```sh
bash tools/offline-navigation/ops/deploy-production.sh /path/to/.env
bash tools/offline-navigation/ops/status.sh /path/to/.env
```

The deploy script validates Compose without printing resolved secrets, builds
the two builder services, and updates only `offline-worker`, `offline-scheduler`,
then `app`. It does not use `--remove-orphans` and does not
restart the database or voice service.

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
  --production-config /home/sodovaya/volty/offline-production/production.json
```

The scheduler writes durable queue entries to the staging volume. For each
planned region, source metadata must be recorded at
`<sourceRoot>/<regionId>.source.json`:

```json
{"osmSequence": 123, "osmTimestamp": "2026-09-05T00:00:00Z", "geometryHash": "sha256-of-the-accepted-source-geometry"}
```

The worker downloads the configured public PBF with HTTPS/SSRF checks, runs the
existing pinned build pipeline in a unique attempt directory, verifies every
component, signs the manifest with the external key, and atomically publishes
the release plus `catalog.json`. Missing metadata is a failed job, never a
fabricated timestamp. A failed attempt never becomes ready.

This bundle schedules only the canonical regions explicitly present in
`production.json`; it does not claim schema-3 anonymous discovery or generate
an arbitrary foreign region from an Android request.

```sh
bash tools/offline-navigation/ops/backup.sh /path/to/.env /home/sodovaya/volty/backups
```

The backup is a local protected copy and is not disaster recovery until copied
to an independent approved target. `restore-check.sh` and `rollback.sh` refuse
to guess a production database or generation; use the documented separate temp
DB and v3 publisher workflow once those components are deployed.

## Current blockers

The checkout is synchronized on the VPS at `/home/sodovaya/volty` and the
existing app health check passed. The server `.env` was preserved, but the
offline profile is intentionally not started yet: it still needs a provisioned
Ed25519 signing key/key id and one real source-metadata record per queued
region. Those values must come from the trusted release/source process; this
repository does not fabricate them.

Full Russia package builds, four foreign cold builds, schema-3 discovery,
native offline APK smoke, restore, and rollback remain unverified. Do not call
the system production-ready until those gates have fresh evidence.
