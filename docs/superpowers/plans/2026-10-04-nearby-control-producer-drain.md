# Voice control producer drain

Track actual completion of owner-launched join, heartbeat and stop producer jobs, including
reply delivery. Increment before launching, decrement through invokeOnCompletion (including
cancelled-before-body jobs), roll back launch failure. Cancellation, map replacement and a
separate successful stop reply must not erase still-running noncooperative port work.
Expose a read-only projection through VoiceRuntime and AuthenticatedVoiceRuntime.

Tests first: held noncooperative join remains counted after cancel intent receives confirmed
server stop; held heartbeat remains counted after session disconnect receives confirmed stop.
Both assert actual port entry/return and no late positive publication. Finite deferred gates;
also observe both launches when the queued join is cancelled before its body runs.
no delayed unbounded test loop. Missing API RED, zero signature runtime RED, then production
counter and fresh full suite with exact count before commit.

This counts application-owned producers, not independently running HTTP engine/socket tasks.
Zero does not prove mailbox idle, remote/SFU revocation, SDK closure or resource disposal.
No automatic disposal, HTTP close, parent cancellation or promotion of remote ACK is added.

Verified: missing-API RED in 24s (33 executed tasks); zero projection runtime RED:
3 tests, 3 failures, zero errors in 32s (35 executed tasks), exact XML asserted.
Final fresh full suite with rerun/no-build-cache/no-configuration-cache succeeded in
37s, all 35 tasks executed; exact XML 2871 tests, zero failures/errors/skips.
Parent manual review checked every finite producer launch, pre-launch increment,
failure rollback, actual completion callback and read-only forwarding. Permanent
mailbox and scheduler jobs are deliberately not producers. No independent review
or mutation-sweep claim; native/HTTP engine internals and disposal remain separate.
