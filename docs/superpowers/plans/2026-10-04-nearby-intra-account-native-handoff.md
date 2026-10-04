# Native handoff inside one account

The account coordinator prevents different account runtimes overlapping, but it deliberately
does not block the current runtime against its own producers. Within one runtime, StopEngine
can report successful close while a cancelled old connect/mute still runs. Driver resource
release alone cannot prove that producer has returned, so switching channels can allocate a
second native stack too early.

Retain all application-owned engine producer Jobs until actual completion. At StartEngine
registration capture the preceding jobs; await that fixed snapshot cancellably before invoking
the driver. No waits in the serial state registration or mutex, no joining permanent collectors.
Cleanup/stop stays independent and must not wait for held connect. Job completion callbacks
remove records including cancelled-before-body tasks. Existing admission/lease checks run after
the wait. A second/third switch cannot forget the oldest still-running job.

Tests before source: old noncooperative connect and mute each block replacement after successful
close; cancel an intermediate waiting start and verify the third still waits for the oldest.
Finite gates, exact issued allocation/publication/return markers. Observe actual runtime RED,
implement, fresh full suite exact XML/manual review before commit. Counts cover app-owned jobs,
not LiveKit internal threads or physical close; foreground service integration follows.

Evidence: first attempt compiled neither new test because a two-argument factory lambda omitted its parameters (24s/33 tasks); corrected fixture, no runtime RED claim for that attempt. Actual RED 6 tests, 3 failures, zero errors in 31s/35 executed, exact XML: replacement and middle/newest stacks allocated while original connect/mute had not returned despite successful close. Source retains all producer Jobs through completion and awaits a fixed prior snapshot before driver admission. Fresh full rerun/no caches: 2886 tests, zero failures/errors/skips, 36s/35 executed. Parent manual review checked pre-launch predecessor capture, no self/permanent-collector wait, cancellation of intermediate wait, stop independence, callback removal and postwait driver admission. No independent review, mutation, physical/internal SDK proof.
