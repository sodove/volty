# Native incoming actions and lazy Android configuration

Use immutable PendingIntents with unique canonical binding/ticket/action data. Acceptance
targets MainActivity directly with an explicit action (no notification trampoline). Its
address will be consumed by the new visible UI at assembly; do not wire capture into the
existing legacy UI. Decline targets a nonexported receiver, requires matching action and
canonical DECLINE address, and submits through the original root registry with a fresh OP.
Finish the broadcast after submission; do not borrow receiver scope for network cleanup or
call the Deferred result a server acknowledgment. No FGS/audio from receiver.

Provide lazy Android registry and notification account factory. Configuration borrows actual
runtime/account, Main/elapsedRealtime/cold per-owner pulses and UUID generators, grants no
authority at construction and performs no push/HTTP until explicit hint on attached owner.
Factory binds the unique owner, disposes it on construction/binding failure and must be used
by the account/root coordinator once per captured account, not recreated for every push.

No FCM provisioning or automatic account restore; UI/backend rollout remains disabled.
Native notification/Activity/receiver behavior requires device acceptance. Fresh full Android
suite compiles native sources and manifest; no fake builder/receiver unit proof. Existing
common actual-HTTP action contracts are the behavior proof. Manual parent review only.
