# Public account removal — 2026-10-29

Published URL: https://volty.sodove.ru/remove_account

The requested Google Play link provides automatic web deletion without an
installed app. Email/password verification is followed by a separate irreversible
confirmation; the existing password-recovery flow handles forgotten passwords.
The page uses the existing login and DELETE account APIs, no new deletion API.

## Source and production provenance

- Local sources fast-forwarded from `7a80a7d6` to `origin/main` `743f8385`
  (app 0.7.59). Original dirty work and untracked documents were retained in stash
  `preserve local work before latest sources 2026-10-29`; newer tracked documents
  were retained. The lifecycle-test conflict was resolved with the newer upstream
  implementation (the stashed variant contained literal CR/LF escape text).
- Production does **not** run the old `/home/sodovaya/volty` checkout. The
  previously deployed image manifest identifies backend source
  `b9cac03f932ccd504b6a9783761d1f85e1109bc9`, identical to the backend at the fetched
  main revision before this change.
- Previous image:
  `sha256:ee36564a341ba6a2aca28c6b222f1f70ad1fdb00469c820915fb763c1f109a46`.
- Published image:
  `sha256:3f7ddf480cac0b511f431b2dcd2553fd9dd1972390ddbeeb21453af39b405226`.
- New image extends that exact previous image and replaces only the application
  JAR, adding a provenance manifest. Dependency JARs and entrypoint are inherited.
  There are no schema migrations or configuration changes.
- Release directory:
  `/home/sodovaya/volty-nearby-releases/2026-10-29-account-removal`.
  It contains source archive, artifact hashes, frozen private old/new Compose
  models, verified PostgreSQL backup, rollout script, and compatibility evidence.
  The previous Compose model permits image-only rollback without a database restore.
- Only `app` was recreated with `--no-deps --no-build --pull never`; the rollout
  asserts unchanged environment and unchanged database/SFU container identity and
  start time. No active SFU participants were present at switching.

## Deletion behavior

The earlier implementation retained profile credentials behind a soft-deletion
flag. Deletion now anonymizes that profile and erases credentials, friendships,
push bindings, and sharing payloads in the existing access-fenced transaction.
It preserves UUID tombstones and exact voice-cleanup operations. Names and
descriptions of previously deleted owned communities are scrubbed too, without
issuing a second deletion event. Backups are not rewritten; the public page says so.

## Verification

- Backend `test`: **286 tests, zero failures/errors/skips**; `installDist` passed.
- App `:composeApp:testDebugUnitTest`: **5925 tests, zero failures/errors/skips**;
  all 35 Gradle tasks executed with build cache disabled.
- PostgreSQL account deletion and ringing retirement: **13 tests passed**.
  Strengthened data-erasure test separately passed with GPS/push/token fixtures.
  Review found retained titles on previously deleted communities; an added fixture
  failed before the fix and passed after it. Scoped re-review cleared the finding.
- Browser tests under the actual nonce CSP: no request on page load, separate
  confirmation, cancel, password clearing, uncertain-network outcome, recovery,
  and no horizontal overflow at 320/390/1280 pixels.
- Real local database browser flow: deletion succeeded; old login, access token,
  and refresh token all received **401** afterwards.
- The same flow passed through the published HTTPS page using an explicitly
  created disposable QA account, then reloading the page without credentials.

## Production compatibility before and after

Synthetic clients used the public APIs used by the current app, with new private
QA community/friend accounts and the existing public SFU origin. Both clients
earned CONNECTED with participant SIDs and increasing received Opus RTP packets.

| Check | Before | After |
|---|---|---|
| Private channel received packets, both directions | 53 / 55 | 44 / 41 |
| Friend call received packets, both directions | 60 / 59 | 45 / 42 |
| Routing download | 94,903,891 bytes; expected SHA-256 | identical |
| Search download | 9,090,098 bytes; expected SHA-256 | identical |
| HTTP Range for both files | 206, exact bytes 1024–2047 | identical |
| Catalog/manifest/status | consistent, package ready | identical |

QA sessions were disconnected and QA accounts deleted. These measurements prove
actual synthetic media transport and map delivery, **not** audibility on Android.
No Android device was attached to ADB, so a physical last-APK check was not made.

One preparatory rollout check stopped before switching because empty SFU rooms
linger after clients leave. The guard was corrected to query actual participants
in every listed room; it never deletes rooms or ignores active participants.
The subsequent guarded rollout and all post-rollout checks passed.
