# Android roster binding

Supply a thin common roster factory through Android DI. Borrow the named Nearby
OkHttp root, exact authenticated account, channel/generation tag, queued Main dispatcher
(not Main.immediate), and SystemClock.elapsedRealtime (includes sleep). A cold one-second
tick source belongs to each subscription; runtime coalesces actual reads at its existing
five-second cadence and retires private state on account cancellation.

This is configuration-only delegation to the tested authenticated owner. No global
token supplier, root scope/Job, eager subscription or account singleton. No mirrored
unit test of constructor wiring; fresh full Android suite confirms actual source types.
Native container resolution and scheduling/lifecycle on device remain integration checks.

No UI cutover, foreground lifetime, microphone/audio proof or LiveKit stop acknowledgment.
Full voice cleanup needs a separate lifetime retaining original generation permissions.
