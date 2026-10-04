# Native producer drain evidence

Engine bridge counts owned start, stop and preference jobs before scheduling. Actual
Job completion removes a record, including cancellation before body entry. Cancellation,
Stopped events, map replacement/removal and isActive do not reduce the count. Runtime
and authenticated aggregate expose the same read-only nativeInFlight projection.

Missing API RED21s33executed. Signature-only zero projection RED2tests2fail27s35executed;
added pre-body cancellation driver and refined RED3tests3fail32s35executed, exact XML.
Fresh full2863 tests, zero failures/errors/skips36s35executed, rerun/no build/configuration
caches and exact XML. Actual issued fake SDK connect and mute hold NonCancellable gates:
successful close/Stopped precede producer return, count stays one, then becomes zero only
after post-gate marker and actual completion. Queued job never enters body but also clears
its record after stop; no native allocation. Held gates always released finally.

Parent manual review: atomic counter registered before launch; launch failure rolls back;
completion callback installed even for already completed jobs; all native effect producer
paths use it; long-lived event collector is not a native producer. No independent Luna
PASS (earlier quota), mutation sweep, SDK internal-thread or device release claim.

This projection alone cannot authorize replacement audio: require retired intent, actual
local cleanup completion and no failed cleanup too. HTTP producers/drain disposal and
global coordinator are following work; no parent cancellation is a resource ACK.
