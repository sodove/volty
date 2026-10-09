# Nearby account entry and email recovery

The reported wrong-password response told a logged-out user to sign in again. Login now maps its own 401 to incorrect email/password; authenticated requests retain their expired-session message. Login and registration have a visible selected tab, the email field explains that a display name is insufficient, and form errors appear beside submission. Recovery validates email without requiring a password.

Retraction: recovery previously returned 202 and stored a token without sending any email. SMTP delivery now uses a protected runtime JSON file, required STARTTLS on port 587 (or TLS on 465), certificate/hostname verification, bounded network timeouts, and no mail debug logging. Neither private credentials nor raw tokens belong in Git, the image, the APK, or logs. Unconfigured/failing delivery returns 503. Known and unknown accounts receive the same accepted receipt; each normalized mailbox is limited to one request per minute in a bounded map.

The email opens a one-hour, single-use link with a fragment token. The reset page removes the fragment from history, has per-response CSP nonces, no external assets, no-store and no-referrer headers, and submits token/newPassword to the same origin. Validation failures preserve the token. A password change also revokes stored refresh tokens in the same database transaction and refuses deleted users. Both confirmation endpoints reject an unsuccessful account update instead of reporting success.

Validation for 0.7.18/code 41:

- Fresh common suite: 3460 tests / 342 suites, no failures/errors/skips; both signed APK variants and SQLDelight migration verification passed.
- Backend unit suite: 275 tests / 46 suites; SMTP integration delivered and received the exact issued fragment link through the provided account's own mailbox. The integration uses an isolated in-memory account and cannot change a production password.
- Disposable PostgreSQL: 375 tests, no failures/errors/skips. Two new tests first failed against the previous store: old refresh rotation remained possible and a deleted account's password could still change.
- Real Android emulator: selecting login is visible, a display name is rejected, and a submitted valid-format email with wrong credentials produces the dedicated incorrect-email/password message. Existing original emulator account retained.
- The ARM APK contains neither the supplied FCM private key nor the SMTP password; existing signing certificate retained.
- A narrow Luna review found no important or critical reset-page/CSP blockers. No Astra invocation.

Production rollout and browser end-to-end verification are recorded in the task ledger. Physical Xiaomi audibility and independent microphone/output routing remain deferred; this change does not claim whole-Nearby acceptance or fix the separately observed direct-call completion error.
