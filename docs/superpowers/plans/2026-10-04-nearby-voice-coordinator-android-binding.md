# Android voice coordinator binding

Register the tested coordinator as one lazy Android Koin singleton using the existing
bounded Nearby OkHttp client, strict v2 HTTPS root, queued Main dispatcher and monotonic
elapsedRealtime clock. Provide a cold per-subscription one-second pulse source so multiple
retired/current owners do not steal each other's cleanup ticks.

Construction creates no account authority, child lifetime or SDK resource. Acquisition
still requires the actual authenticated account; no mirrored global token supplier.
Do not attach blocking Main shutdown to synchronous Koin onClose: retirement submission
and actual producer drain are separate. No SDK preference/diagnostics singleton or UI
cutover is implied by this configuration-only binding.

Validate with the fresh full Android unit suite and exact XML count before commit;
configuration-only change does not need a test mirroring its constructor arguments.

Verified: fresh full suite BUILD SUCCESSFUL in 38s, 35 tasks executed; exact XML 2868 tests, zero failures/errors/skips. Parent configuration review only. Native SDK/UI remains unbound.
