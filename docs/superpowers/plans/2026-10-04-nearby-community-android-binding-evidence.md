# Android community binding evidence

Android DI lazily supplies the trusted factory using its own named OkHttp client,
10-second connect/request/socket bounds, actor-specific AndroidInvitationDraftStore
and AndroidInvitationSecretGenerator. The named root client closes on Koin release;
account consumers only close their configured child adapters. No login tokens copied
into DI and no eager account/HTTP request/Keystore work at application registration.

Production v2 root matches the existing social HTTPS host and actual backend routes.
Koin 4.2 onClose API verified against primary documentation linked in the plan; actual
Android compilation confirms import and provider types. Manual narrow binding review;
fresh full2843 tests, zero failures/errors/skips34s35executed, rerun/no build/configuration
caches, exact XML totals. Configuration-only wiring; no mirrored tests or mutation claim.

This is compilation plus existing common factory/crypto/record tests. It does not prove
Android container resolution, native Context/preferences/Keystore/process-death behavior,
an active v2 backend deployment, signed APK UI cutover or voice/audio. These remain
integration/device work. No legacy group import, backend edits, deployment or push.
