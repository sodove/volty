# Client channel commands evidence

ChannelCommandsPort has separate creation/deletion receipts. Caller owns canonical stable
UUIDs; adapter normalizes and validates title before auth/network. HTTPS endpoint requires
nonempty host and no credentials/query/fragment. The derived HttpClient disables redirects
and expectSuccess; requests have10s whole deadline and responses16KiB bounds/strict UTF-8.

Creation requires exact server/channel/title, actual isDefault=false and positive numeric
revision. Deletion requires exact server/channel/operation, nonnegative numeric revision
and actual boolean cleanupPending. Errors are sanitized enums; cancellation propagates,
no automatic retries or membership/voice/sharing/DI side effects.

Ten MockEngine tests observed missingAPI RED before implementation. Full Android2690 fresh
PASS32s/all35 executed tasks; backend152 fresh PASS9s/all6 executed tasks. Exact XML totals
and narrow source review are recorded below after review. Cases assert issued methods/
paths/bearer/stable bodies, foreign identities, deliberately incoherent creation metadata,
authoritative cleanup despite ignored terminal shape, bounded bodies/malformed UTF-8,
typed conflict/cap/denial/redirect/network outcomes, finite issued timeout, issued cancellation
and pre-auth input rejection. These are ordinary unit tests, not device or production proof.

Private invitation HTTP/client, production wiring/roster/sharing, UI and device validation
remain pending. No deployment or legacy group transfer.
Exact fresh totals: Android2690/0/0/0 and backend152/0/0/0. Narrow Luna source review PASS. Adapter itself issues once; future production HttpClient factory must also exclude externally installed retry plugins. Production DI is not verified here.
