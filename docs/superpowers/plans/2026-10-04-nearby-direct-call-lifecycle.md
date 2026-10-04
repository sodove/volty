# Direct-call lifecycle policy

Approved spec: 2026-10-03-nearby-voice-spaces-design.md, Direct calls and background.
Backend pure policy first, then transactional storage/claims and endpoints in subsequent tasks.
No route or credential issuance is enabled by this policy alone.

Immutable call/caller/callee/idempotency/caller-operation identity, revision and original server
ring deadline. RINGING expires inclusively after 45 seconds; accepted calls do not inherit ring
expiry. Terminal transitions irreversible. Callee alone accepts/declines, either participant
can stop; outsiders cannot expire or mutate another call. Creation requires accepted-friendship
evidence, reusing an idempotency key cannot replace target or caller operation/extend deadline.

Stop RINGING -> CANCELLED; stop ACCEPTED -> ENDED. Thus accept-first followed by stop must
close both eventual media sessions, rather than return an accepted no-op. Accept against stale
revision or terminal row cannot grant acceptance. Accepted retry is idempotent. Friendship
revocation ends accepted calls or cancels ringing; outcome retained for both parties.

Pure sequential decisions are not proof of committed concurrency, friendship SQL authorization,
claim reservation/replacement, SFU close, delivery or FCM. Repository must serialize call row and
both user claims in fixed order and persist expiry/outcome returned by a rejected decision.
No random room/credentials or wall-clock/network I/O under this policy.

Six policy tests first: create idempotency/identity/friendship; both accept/stop orders; exact
deadline and accepted independence; roles/stale revision; terminal idempotence; revocation.
Missing API/runtime RED, fresh backend suite with exact XML and fresh Android suite before
commit. Parent review only (Luna quota). No deploy or direct-call UI activation.
