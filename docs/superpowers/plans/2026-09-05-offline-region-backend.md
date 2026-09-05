# Offline region distribution backend

The existing backend exposes a static directory, and deploy-offline.sh trusts a
signature envelope without verifying it and hashes against the unsigned manifest.
There is no request-driven server acquisition, publication lifecycle or readiness
contract. The Android catalog already supplies coverage-based region selection.

Implement the requested subsystem in the existing worktree, preserving dirty files:

1. Reuse Python pipeline schema/signature validators in a private package service.
   Relay a verified upstream catalog unchanged; acquire its already-built releases
   from one configured HTTPS artifact origin. Stage, validate signed sizes/hashes
   and archive/database/map structure, then atomically publish immutable releases.
   Bound concurrency/resources, persist ready state, retain active releases and
   apply a grace interval before removing unreferenced releases.
2. Add a narrow Ktor public API at /offline for catalog, coverage resolve,
   ensure/status, and immutable manifests/components with range forwarding.
   Keep worker administration unavailable through this public API.
3. Before downloading a backend package Android ensures the signed release is
   ready, polls with a deadline and cancellation, then uses its existing verified
   downloader. Preserve compatibility with external static catalogs.
4. Wire an optional Compose offline service and migrate the local publishing
   wrapper to the same verified ingest path. Document API, trust and environment.
5. Run Python package tests, all backend tests, relevant app unit tests and Compose
   config if Docker is installed. No APK, device, deployment or commit.

The service holds only public verification keys. The release pipeline remains the
authority that builds/signs catalogs and regional artifacts. No on-demand OSM build
is introduced; unsupported coverage is reported explicitly.
