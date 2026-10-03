# Nearby community browser read ownership

A separate coroutine owner exposes membership pages, public catalog pages and
selected community detail. These reads cannot create membership, voice or sharing.
Not-loaded rows are null; a confirmed empty page is an empty list. Pagination
uses the last accepted ID, prevents concurrent duplicate requests and preserves
accepted rows/cursor after failure. Refresh retires earlier pagination.

Each read surface has its own request generation. Network waits occur outside
the state mutex. Search/selection/refresh cancel the prior job and reject its
late completion by generation. Close cancels the owned lifetime, clears all
exposed state and rejects future commands/completions. Selecting another server
clears previous details immediately; foreign detail IDs are rejected.

Seven tests first produced missing-API compile RED77629 and passed21303 after
implementation. An eighth tests safe initial failure and explicit retry. The
noncooperative fake responses use finite deferreds and mark returned AFTER the
NonCancellable await; race tests assert that cancelled responses actually return
to their request continuation. Fresh targeted8 PASS74030 and full Android2633
PASS2651, zero failures/errors/skips; all35 tasks executed with rerun-tasks,
no build cache and no configuration cache. No mutation-sweep claim.

Narrow Luna source review passed request-generation/lifetime fences. The returned
markers prove that the fake returns after cancellation; they do not separately
instrument arrival at the owner's generation comparison. The fence itself was
reviewed in source, without claiming mutation-control proof or running more tests.

This is read-state ownership, not Compose rendering, Decompose/root wiring,
authenticated account lifecycle integration, creation/join mutation handling,
realtime roster, SDK runtime or a device sound check. The future screen/root
adapter must explicitly close the owner on account retirement and own UI actions.
