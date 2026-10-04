# Authenticated roster owner evidence

VoiceRosterAccountSession borrows the actual SocialAuthenticatedAccount context and
Job. Actor/channel UUID, HTTPS root and queued dispatcher are checked before HTTP
adapter or roster/guardian children are allocated. The fixed account token source is
read at request time, with caller/account/local-retirement checks before and after.
Snapshot access is restricted to the subscription's exact channel/generation tag.

The existing roster runtime retains revision, ordinal and conservative lease evidence.
An undispatched cancellation guardian fences access and clears runtime state without
joining a held HTTP producer or account child. Explicit retirement also works from a
cancelled caller, closes only owned adapters/runtime, independently attempts both
cleanup steps and records failure. It does not cancel authentication or shared HTTP.

Missing API RED41s33executed. Signature-only runtime RED3tests3fail53s35executed,
exact XML checked; stub completely replaced. Fresh full2846 tests, zero failures,
errors or skips56s35executed, rerun/no build/configuration caches and exact totals.
Finite Channel pulses, held actual MockEngine response plus actual account Job child,
post-response return marker and actual token headers cover logout before completion,
same-actor relogin, late response isolation, auth refresh and cancelled-caller cleanup.
Invalid root/channel/dispatcher allocate no account children and issue no HTTP.

Parent manual narrow review: validation precedes allocation; guardian starts before
cancellation; retirement precedes suspension; late successful and failed replies are
guarded; cleanup has no parent join or parent/client cancellation; virtual fixtures
release held work in finally. No independent agent PASS: Luna quota was exhausted in
the preceding authentication stage. No mutation sweep or native audio proof claimed.

This is a read-only account-bound owner, not application/UI cutover. Native factory
binding follows. Account cancellation does not acknowledge microphone, LiveKit or
remote session stop; retained full voice cleanup remains separate integration work.
