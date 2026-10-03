# Owned roster runtime

Bind the approved pure presence owner to actual cancellable reads and finite injected pulses.
This remains an account-bound foundation before production DI/Android foreground ownership,
authorized realtime server publication and UI assembly.

1. Tests first: issued initial read exact tag, finite pulses expire rows and poll every5s,
   in-flight read coalescing, whole10s lifetime even for a hanging port, failure recovery,
   denial/rollback/explicit close and parent cancellation stop reads and clear state. Assert
   actual port calls and cancellation, not elapsed time alone. Use finite flows/channels.
2. Queued serial dispatcher owns projection and public StateFlow; one supervised child scope
   tied to parent, one current read, no credentials/log output. Network executes outside state
   mutation and returns through the captured read token. A private lifetime cleanup clears
   state even on parent cancellation. Close does not mutate membership or control voice/share.
3. Pulses use injected monotonic clock including sleep. No unbounded test loops or minted
   credit; poll at5s, coalesce active reads,10s transport deadline. Exceptions become safe
   unavailable state. Explicit close permanently retires and cancels children. Finite pulse
   source completion leaves explicit owner lifecycle intact; failed clock stops the owner.
4. Narrow Luna review, fresh full Android, evidence/commit. Production account binding,
   no-cache transport configuration, foreground pulses and integration remain subsequent work.
