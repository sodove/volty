# All negative paths retire the original call pair

Prerequisite fccb7a72. Accepted SQL is still not bound to public API/SFU. Integrate generic
session cancellation/expiry and account deletion with the pair owner before activation.
Never acquire another participant after holding a single actor/revision lock: identify an
immutable call operation first, then use UPDATE access fence and all pair actors in order.
The pair owner revalidates the original operation/participants under its transaction.

Generic cancel of either call operation performs full call stop and returns that original
operation's terminal state. Expiry checks original pair deadlines, alive accepted friendship
and both exact claims; fresh accepted PENDING remains PENDING, not channel-access denied.
An elapsed lease ends both with MEDIA_TIMEOUT; an original terminal/missing ownership
without deadline evidence ends with MEDIA_LOST. Explicit stop/outcomes remain idempotent.
Malformed internal call rows without a participant call cannot grant authority and retain
the existing fail-closed isolated-session cleanup. No caller-supplied actor is trusted.

Account deletion collects ACCEPTED peer actors together with ringing/channel actors before
any mutations. Retire accepted pairs in that same account transaction, preserving unrelated
claims and rollback semantics. Channel begin cannot replace an accepted call claim even
with replace=true; callers must first issue explicit full stop, then start the new channel.
Terminal old-operation cleanup must continue to work without touching a newer target.

V15 additively extends named outcome CHECK with MEDIA_LOST. Pure policy requires a trusted
pair evidence caller; it neither observes media nor infers a user stop. Tests before source:
generic cancel, fresh/elapsed expiry, account deletion, channel replacement guard, accepted
friend revocation, terminal old stop/new claim, account rollback and lost original claim.
Fresh real PostgreSQL + backend unit + Android suites, exact XML counts, parent review.
Typed positive call authorization/admission and all callback-driven terminal paths remain
the next integration before binding production call endpoints. No native/audible proof.
