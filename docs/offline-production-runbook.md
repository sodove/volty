# Volty offline production bundle

This bundle is copyable to the existing VPS, but it does not invent a catalog,
signing key, source metadata, or geographic coverage. It fails before starting
the worker until those inputs are present.

## Install

1. Copy the repository files (including `tools/offline-navigation`) to the
   existing deployment checkout. Do not copy `.env`, private keys, PBF files,
   packages, APKs, Gradle/build directories, or generated emulator captures.
2. Copy `tools/offline-navigation/production/config.example.json` to the host
   path in `VOLTY_OFFLINE_CONFIG_HOST`, edit it with the real canonical regions,
   HTTPS public source URLs, measured bbox values, existing `keyId`, and the
   installed app version. Keep the container paths in that JSON unchanged.
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

The scheduler writes durable queue entries to the staging volume. For each
region, place source metadata at `<sourceRoot>/<regionId>.source.json`:

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
bash tools/offline-navigation/ops/backup.sh /path/to/.env /srv/volty/backups
```

The backup is a local protected copy and is not disaster recovery until copied
to an independent approved target. `restore-check.sh` and `rollback.sh` refuse
to guess a production database or generation; use the documented separate temp
DB and v3 publisher workflow once those components are deployed.

## Current blockers

This bundle has not been deployed to the VPS in this session: SSH returned
`Permission denied (publickey,password)`. Full Russia coverage, real source
snapshots, four foreign cold builds, native offline APK smoke, restore, and
rollback therefore remain unverified. Do not call the system production-ready
until those gates have fresh evidence.
