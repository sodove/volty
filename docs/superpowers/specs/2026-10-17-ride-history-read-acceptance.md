# Recorded-history read acceptance — 2026-10-17

This is desktop SQLite/JDBC evidence for the existing read foundation. It is not
Android latency, memory, frame-rate, BLE acquisition or projected-writer acceptance.
The production recorder and history screens are not activated by these checks.

## Existing two-hour fixture, strengthened

`HistoryReadRepositorySqlTest.twoHourMultiSourceReadKeepsPeaksPartialCellsGapsAndRawWallJumpWithoutLiveData`
uses the actual SQLDelight store and bounded read repository:

| Contract | Fixture and verified result |
|---|---|
| Duration and identities | Two hours; 7,201 timestamps at 1 Hz, four physical sources, eight independent metric/cell keys |
| Stored detail | 57,608 rows; eight full-range reads return exactly 7,201 raw rows per key |
| Overview bound | At most 61 returned points per key, without read truncation |
| Brief extrema | One-second 64 km/h and −4,200 W peaks survive overview thinning |
| Incomplete contributor | Only pack B cell 1 is partial for 20 seconds; healthy series receive no partial facts |
| Earned zero | The same cell's measured zero remains a numeric value outside the outage |
| Continuity | Explicit BLE gap and a one-hour raw wall-clock jump remain visible as gaps |
| Clock | Monotonic range selection retains original raw timestamps and the recording origin |

The earlier fixture used constant, complete numeric contributors. Its identity,
gap and clock coverage already existed; the new assertions add brief extrema and
a deliberately incomplete contributor. This is acceptance coverage, not a claim
that a new production bug was reproduced or fixed.

Fresh h368: two tests / one XML, no failures, errors or skips. The strengthened
two-hour case took **1.022 seconds** on this desktop runner, including fixture
creation, inserts, reads and assertions. It is not an isolated query benchmark or
a device performance budget. Gradle compilation/run took 55 seconds.

The companion real equal-time page test reads 20,003 rows in pages of 20,000 and
3, retains extrema and legitimate zero within an 11-point overview, and refuses
to invent certified summary statistics from raw detail alone.

## Application wiring

`HistoryReadApplicationBindingTest` resolves the real AppModule interface and
concrete unified reader to one instance with only the database supplied. It reads
both v2 and legacy rides, preserves canonical legacy power sign, leaves an open
v2 recording open, and preserves legacy rows. It starts no BLE/location/recording
runtime. Fresh h367 full verification: 4,157 tests / 425 XML and actual migrations.

## Remaining acceptance

Long-run buffered/projected recording, Android storage latency and memory,
foreground/provider acknowledgement, real device reconnect/acquisition, and
rendered chart/map performance require their own evidence. This read fixture does
not qualify hosted gateway cache ages, automatic alias handover or whole-vehicle
aggregate validity. Those gates and the approved screen port remain open.
