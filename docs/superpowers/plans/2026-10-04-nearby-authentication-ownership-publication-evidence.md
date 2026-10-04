# Authentication ownership — response publication stage

Authenticated responses and profile/sharing callbacks are validated against their
original authentication generation under the finite session lock. A response from
an older account returns Unauthorized and cannot publish into the current account.
401 retry keeps the original generation. Successful deletion retires only its own
generation after validation, preserving Success for normal current-account deletion.

Four tests cover held old profile, sharing and deletion responses after replacement,
plus successful current deletion. They assert actual transport token/response effects
and current session/storage/sharing, with noncooperative producers released in finally.

Initial fixture compile error (missing SocialTransport import) was corrected before
runtime RED. Actual RED: 4 tests, 3 failures, 39s, 35 executed tasks. Fresh full suite:
2826 tests, zero failures/errors/skips, 1m4s, 35 executed tasks, rerun and caches disabled.
Exact XML totals asserted. Narrow Luna review PASS. Ordinary tests, no mutation claim.

Legacy live streams remain the next stage: their token callback, refresh and events
must retain their original generation. Trusted Nearby account Job/factory wiring,
native audio, UI cutover and device verification remain unfinished.
