# Invitation account lifetime — evidence

Immutable canonical actor+redacted credential snapshot, same-actor refresh with CAS,
terminal bearer retirement. Actual InvitationAccountSession constructs a bound HTTP port,
validates store actor before HTTP allocation, retires draft owner and closes its HTTP child.
Parent client remains usable; persisted draft intentionally remains for same-account retry.
Draft owner checks caller cancellation/retirement inside queued preparation and at every
store/generator/retain/HTTP boundary and typed success/failure return.

Verification: full Android2790 tests, exact zero failures/errors/skips, BUILD SUCCESSFUL31s,
35tasks executed, all caches disabled and rerun-tasks. Ordinary tests, not mutation evidence.
Binding/session MissingAPI RED21s33tasks; retirement API RED7s28tasks; first full attempt had
nullable-list inference compile error in parent test, corrected explicit String? expectation;
that attempt was not a passing or runtime failing test. Final full verification above.

Five deterministic owner regressions drive actual issued reads/retain/HTTP, terminal future
calls, queued requests, committed retain preservation, controlled late success/failure and
caller cancellation swallowed by a port. Seven binding/session tests cover credential refresh,
actor rejection, terminal independence, malformed input, actual HTTP auth and parent client,
foreign store, and retirement during an issued MockEngine request.

Luna source reviews PASS: binding/factory and worker owner lifecycle. Review caveat retained:
in-flight MockEngine test proves request reached handler before retirement and cannot publish;
it does not separately prove response decoding after HTTP close. Controlled owner-port tests
prove late success/failure handling. No native storage/media/device evidence added.

Ruling: preserve committed encrypted drafts across logout so ambiguous server commits can be
replayed with the original payload after same-account relogin. No native erasure or remote
rollback is claimed. Already issued commands may commit remotely. Guards check at processing/
return boundaries; eventual screen lifetime must still discard results already handed to a
caller before an account transition. No automatic legacy SocialRepository/production DI wiring.

Retraction recorded in older foundation/rebuild specs: legacy groups are not migrated;
accounts/friends preserved and source legacy data not destructively deleted, per Oct4 request.
Next: apply immutable account lifetime to other community/channel/invitation HTTP boundaries;
voice cleanup needs separate retiring/draining ownership and must not be reduced to HTTP close.
