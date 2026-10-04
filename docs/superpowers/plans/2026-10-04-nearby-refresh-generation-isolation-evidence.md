# Refresh generation isolation

Refresh serialization belongs to an authentication generation. A retired account's
noncooperative transport cannot hold the new account's refresh behind a repository-wide
mutex. Global credential persistence serialization is retained for write/clear order.

Fixture deliberately gives the new login a valid profile with locally expired credentials,
while the old account's actual refresh waits noncooperatively. It asserts new refresh is
issued and completes before old producer release; exact tokens and writes; old account Job
cancelled but incomplete; late old response returns Unauthorized and cannot persist.

Actual RED1test1fail42s35executed, exact XML: new refresh not issued while old held.
Fresh full2837 tests, zero failures/errors/skips46s35executed, rerun and build/configuration
caches disabled, exact totals. Parent manual narrow review; independent reviewer unavailable
after Luna quota failure, no independent PASS claimed. Ordinary tests, not mutation evidence.

Next is trusted account HTTP/community factory wiring. No UI/native audio/remote ACK proof.
