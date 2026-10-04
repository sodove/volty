# Original direct-call admission into the shared voice runtime

Prerequisites: typed SQL call/session owners, authenticated ports, original cleanup capability,
shared cross-port lease sequence. Keep one immutable caller/callee operation; call UUID never
enters generic channel join. A caller can ring while VoiceRuntime waits: repeated create uses
the original payload, RING maps to Pending without credentials, ACCEPTED resumes the original
prepared call. A callee uses original explicit accept/revision, then original prepared resume.

Register call metadata before scheduling admission. VoiceRuntime chooses generation when its
serialized Join command is consumed, so permit metadata with an unbound generation and bind
that slot exactly once to the actual operation/generation. Never guess generation1 for cached
runtimes or replace an established binding. Negative original cancellation needs no generation
until voice admission is submitted. Incoming metadata requires an actual observed callee RING.

Share one request sequence between call and original participant journal control children.
Record validated prepared-call session identities in the original journal owner so heartbeat
and disconnect can use only actual returned session tags. No call media uses generic join.
Heartbeat reports transport/lease evidence only, never capture/rendering or heard speech.

Stopping requires authorized original parent terminal confirmation AND original journal Ended
evidence. A call snapshot alone cannot manufacture SFU cleanupPending=false. Attempt both
original negative requests even if one transport fails; retain unknown parent/journal results
for root-owned retry. Child disposal belongs to actual enclosing work drain, not auth cancellation.

Tests before source: pure metadata survives an unscheduled producer; generation binds once at
actual Join; mismatched target/op/gen gives zero HTTP; actual caller RING then accepted admission
uses original create/resume; actual callee accept uses original revision/op; no operations/join;
earned session tag heartbeat; parent-only terminal is not cleanup ACK; original negative calls
survive account retirement; late replies never allocate native audio or replace current intent.
Finite gates, exact issued requests, parent manual review, fresh Android suite before commit.
Product/root UI wiring, FCM acceptance and physical device proof follow separately.
