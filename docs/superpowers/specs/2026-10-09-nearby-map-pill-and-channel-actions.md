# Nearby map pill and channel actions — 2026-10-09

The user reported that the expanded voice dock resized the entire ride dashboard and duplicated the existing glass pill over the map. The agreed correction reuses that pill: it shows the current conversation name, connection state and earned voice count; tapping it opens a Material3 bottom sheet over the same native map. The Light ride has no second conversation dock. The planner and guidance summaries open the same sheet. Channel position markers continue to come from the existing observed sharing audience and render on the underlying ride map.

The sheet preserves tagged action gates, cancellation, permission/switch prompts, cleanup retries, microphone/output selection, participants and independent position hiding. It scrolls within the supplied maximum height. Sharing duration is explicitly selectable before starting: 30 minutes, 2 hours or 8 hours, as confirmed by the user. Selection survives the permission request; capture does not begin before its separate permission.

Owners can delete a voice channel through its row menu after a confirmation naming the original channel. The existing server delete endpoint is used. The client retains one operation ID per target across uncertain retries, validates receipt identities, and rejects stale ownership, selection, account and confirmation results. The deletion receipt's cleanupPending field is historical; the UI now says Channel deleted rather than falsely presenting it as current connection status. Membership is unaffected by channel deletion.

Animation covers the pill appearing/disappearing, native sheet motion and swipe dismissal, and Nearby tabs and internal server page crossfades. Main Decompose destinations already used fade transitions.

## Verification

- Deletion scaffold RED: six tests, five expected assertion failures. TTL regression against the old hard-coded duration: expected TWO_HOURS but received HALF_HOUR, proving the presentation action was reached.
- Final fresh unit run: 3,476 tests in 344 reports, zero failures/errors/skips; all 36 tasks executed with cache disabled (51 seconds). Both signed APKs and SQLDelight migration verification passed. Source compilation, minification and packaging executed for the changed release variants; unchanged build prerequisites were reused.
- Native signed emulator-5556: real LiveKit connection; existing glass pill opens the sheet, participants/audio/all duration choices visible; choosing two hours produced an ACTIVE consent with exactly 7,200,000 ms between start and deadline in the server database, scoped to the owned fixture. Hide Position preserves voice. Dismissing the sheet restores the same map bounds and HUD control positions.
- Native owner menu/confirmation deleted the actual fixture channel; API detail then contained no channels while the same server and owner remained. Media stopped after server cleanup. A replacement fixture channel was created for a retry check, the native session reached Connected, and Disconnect removed the dock normally. Synthetic server/account cleanup completed; original emulator-5554 account data retained. Both emulators updated with install -r.
- Two old tests failed on a repeated loaded build: a MockEngine handler raced virtual time and a repository's default background scope raced the restoration test timeout. Fixtures now share the test scheduler and assert issued requests/published state. Production navigation/authentication code was not changed.
- Narrow Luna UI and deletion reviews found no remaining blocker; no Astra invoked.

## Bounds

Portrait UI was visually inspected from native screenshots. A short-window resize probe recreated the Activity, put Light HUD lower content outside the short viewport, and coincided with one failed connection attempt; the subsequent normal-size retry connected. The cause of that failed attempt was not established, and this change does not claim landscape HUD acceptance or physical two-phone audibility. Xiaomi and headphone routing validation remains deferred.

Version 0.7.20 / code 43, APK 69,671,462 bytes; SHA-256 `f2cce6ecd7244af060bcc40119fdce18df1745432c123793e34098c00efb7fde`. Existing signing certificate retained. Firebase and SMTP private material absent from every APK archive entry. No backend deployment was required for this client change.
