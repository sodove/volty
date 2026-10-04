# Account-bound direct-call client transport

Build the bounded HTTPS client for the authenticated call contract after Stage42. Preserve
original outgoing call/callee/key/operation across create and cancel retries. Accept/resume
carry original operation and generation. No actor, room or SFU node is submitted by clients.

Validate canonical UUIDs, actual actor participation, expected call/peer, nonnegative revision,
phase/outcome consistency, callee-only bounded sorted incoming pages and advancing scan cursor.
Keep ring deadline as server metadata, never microphone permission or phone-clock lease.

Reuse the existing strict voice response decoder for nested session evidence. Media credentials
only accepted successful accept/resume; terminal or failed parents cannot carry positive media.
Confirmed transport remains distinct from capture/playback. Request-start monotonic provenance,
fresh request ordinal and remaining credit apply equally to call credentials. Terminal snapshots
do not manufacture remote cleanup acknowledgements. Preserve explicit unknown cleanup evidence.

Use child HTTP ownership, no redirects, 10-second bounded calls, 64KiB bounded responses,
cancellation propagation and redacted credentials. Account facade guards before request and after
actual response; retirement never retires borrowed authentication or parent HttpClient.

Tests before implementation: original wire payload/auth; delayed expired credential; wrong parent,
actor, peer and operation; private incoming page validation; terminal/failure credential denial;
every action; actual in-flight response after account cancellation cannot publish. Existing voice
decoder tests protect channel behavior through extraction. Full fresh Android suite before commit.
No product/native activation, push-triggered capture or physical audibility claims in this stage.
