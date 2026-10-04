# Authenticated voice runtime evidence

AuthenticatedVoiceRuntime composes original account control and native permission with
the existing runtime on one queued serial dispatcher. A SupervisorJob is owned under
the original repository/application context, borrowed through an internal account helper;
it is separate from the real account Job. An undispatched account guardian fences local
and HTTP admission before suspension and explicitly requests retirement in NonCancellable.
Original native/remote retries survive cancelled-but-incomplete account lifetime. Explicit
retire and disconnect also survive an already cancelled caller without cancelling login.

Mandatory global native admission predicate is supplied by the caller, with no default in
this composition. Join and positive preferences/sharing check it plus account/local gates;
engine checks the same predicate at driver checkpoints. Positive intent is canonical before
mailbox entry. Disconnect/hide/retire remain available independently. Retirement submission
failure is exposed separately from native release, failed close and server stop projections.
Construction validates actor/root/actual root Job/queued dispatcher before children/adapters,
and independently cancels owned startup lifetime/closes control if construction throws.

Missing API RED23s33executed. Signature-only throwing factory runtime RED4tests4fail
33s35executed, exact XML. Stub replaced. Fresh full2860 tests, zero failures/errors/skips
37s35executed, rerun/no build/configuration caches and exact XML. Actual auth/MockEngine
plus issued fake SDK prove logout before actual auth child completion, failed close and
remote-pending retries with original token, cancelled-caller disconnect preserving login,
gate denial before HTTP/native creation and retirement despite denied positive permission.
First pulse explicitly arms cleanup cadence before clock advances; held gates release finally.

Parent manual narrow review: no auth Job parent for cleanup, guardian starts undispatched,
fences precede suspension, no joining old account children, no parent cancellation or adapter
close on retirement, positive SDK permission samples actual account, cleanup retries bypass
it. No independent agent PASS (earlier Luna quota), mutation sweep or physical SDK proof.

This aggregate deliberately has no dispose/automatic root job cancellation. Future global
coordinator must retain old owners and supply admission from confirmed old resource release;
drain disposal must also account for held network/native producers and independent stop ACKs.
Root parent cancellation/process death is not claimed as resource release. Foreground, durable
recovery, native factory binding/global handoff, UI, live backend and Xiaomi/headset proof remain.
