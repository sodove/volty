# Long projected-recording acceptance — 2026-10-17

This tests the actual BufferedHistoryWriter with projection enabled and the real
SQLDelight/SQLite store on the desktop. It does not simulate Android/BLE acquisition,
foreground service acknowledgement or phone storage performance.

## Reached workload

`HistoryLongRecordingAcceptanceTest` sends 72,001 two-metric receipts at synthetic
100 ms intervals over two hours. All **144,002 events** must be accepted and
committed with no pending events, drops or writer failure. Explicit finite flush
backpressure and an empty timer flow avoid unbounded virtual-time advancement.

The power series is −1,000 W with one 100 ms −4,200 W peak. An independent cell
series reports a genuine zero except for 20 seconds of typed partial data after
100 minutes. Both series pass through the real 500 ms detail projection and
full-stream summary projection before being read from SQLite.

Verified contracts:

- Power detail exceeds one 20,000-row page; the test asserts that precondition.
- A bounded 61-point graph retains the original peak and its exact timestamp.
- Minimum/maximum are −4,200/−1,000 W and integrated energy is 2,000.0888889 Wh.
- Every power summary bucket is complete; its statistics must remain complete.
- The partial cell remains incomplete and keeps its earned zero extrema.
- The ride finishes as COMPLETED; the writer is closed on every exit path.

## Bug found and corrected

h370 reached the full writer workload and failed only at power completeness.
h371 also reached an explicit numeric tail beyond one detail page and lost that
tail's extremum. HistoryStoreReader.stats used a single 20,000-row detail page;
it confused page truncation with acquisition quality and ignored tail values.

Statistics now reduce the full selected detail range with SQL MIN/MAX and
COUNT(unknownReason), returning one row. Ride/source/metric/cell/time filters use
the existing series index fields; summaries, gap markers and detail remain in the
existing read transaction. No all-detail Kotlin list or schema migration is added.
Unknown tail evidence still prevents completeness, even when a certified prefix
is complete. Separate deliberately incoherent prefix/tail fixtures protect this
contract from a superficial removal of the old truncation check.

h372: 56 tests / five fresh XML, no failures, errors or skips. The strengthened
writer test, store tail guards, unified-reader, application binding and original
writer integration cases pass. Desktop fixture durations are recorded in the
execution ledger; they include writes and assertions, not isolated query latency.

## Remaining native acceptance

Real two-hour Android acquisition, provider/foreground lifetime, device storage
latency and memory, background/reconnect behavior, and rendered UI performance
remain unverified. This does not activate the new recorder or qualify gateway
cache ages, automatic alias handover or whole-vehicle aggregate semantics.
