# Owned roster runtime evidence

One account-bound subscription now runs actual cancellable roster reads on a caller-supplied
queued serial dispatcher and publishes the pure presence view through StateFlow. Initial
read is automatic; finite injected pulses age leases and poll at5s, coalescing an active read.
Even a hanging port has a10s cooperative request deadline. Failures are sanitized; explicit
refresh can recover transient unavailable state. Denial and broken clock retire the owner.

Explicit close and parent cancellation clear current presence and cancel scheduling/reads.
Cancellation cleanup publishes unknown without waiting for a delayed network child; a finite
NonCancellable deferred fixture proves a late reply cannot restore rows. Membership, voice,
sharing and the shared port lifetime are separate owners.

Seven initial tests observed missing API RED (20s33tasks), then full2735 PASS. Added actual
parent request-cancel and delayed-reply checks. Dispatcher rejection regression found an
orphan SupervisorJob allocated before validation:1test1failure25s35tasks. Validation now
precedes allocation. A test draft import error was corrected before scoring this RED.

Retraction: initially rethrowing all pulse/clock CancellationExceptions could abandon only
that supervised child while leaving published fresh rows frozen. Two additional regressions
actually failed (2tests2failures26s35tasks). Independently cancelled pulse source now closes
the owner, and active synchronous clock failure becomes CLOCK_RESET. Genuine lifetime
cancellation still propagates. Narrow Luna lifecycle/fix-loop source review PASS.

Final11test source: fresh full Android2739 tests, zero failures/errors/skips,29s35tasks.
Backend unchanged since fresh161 PASS9s6tasks. Ignored ledger contains exact logs.
No production Android foreground pulses, immutable account factory/DI, realtime server
publication, new UI, audibility or Xiaomi device validation claim. Device clock must include
sleep; cooperative deadlines do not forcibly interrupt an uncooperative port.
