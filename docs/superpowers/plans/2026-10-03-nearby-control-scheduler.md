# Nearby owned control scheduler

Continue afteraa22cd19 with one scope-owned pulse collector. Pulse source and
monotonic including-sleep clock are injected, never a phone/server wall clock.
The future Android foreground runtime owns a finite-interval pulse source and
its lifetime. Tests use explicitly issued channel pulses, not delayed runTest
loops. Cancelling this scheduler is not voice/resource cleanup acknowledgement.

- Tick on every pulse. At inclusive expiry issue no heartbeat on that pulse:
  credit cannot be renewed locally after it has already expired.
- Heartbeat only a currently authorized exact tag with earned credit, every5s;
  owner still prevents overlapping HTTP work and fences replies by provenance.
  New tag resets cadence. Failure preserves existing credit until Tick expires it.
- Pending join retry every1s reuses the owner's original operation/destination,
  only while wanted JOINING without a session. Stop/ended/failed intent never
  triggers automatic new operation or reconnect.
- Retry remote intent/cleanup and failed local engine generations every3s while
  any remain. A changed pending set must not indefinitely postpone old targets.
  Retry exact original targets through owner.retryStops and explicit local retry
  callback. Do not replace old cleanup identity with the active new tag.
- Inject failed-local-generation supplier and local cleanup callback explicitly.
  No implicit local release, SFU ACK, lease grant or sharing permission in pulses.

RED/green checks issued heartbeat vs inclusive expiry/sleep jump, stable pending
retry/no resurrection, pending remote cleanup after replacement, local old-gen
retry and scheduler cancellation. Fresh Android exact counts before commit,
narrow source review. Runtime/foreground/DI/account retirement and process-death
durable persistence remain pending; timer policy is not device wakeup proof.
