# Compact Ride-History Storage — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Store a ride's detail samples in about 1/20 of today's space with the same information: about **1 MB instead of ~20 MB** for a 15-minute Begode T4 ride. Every reader keeps the output it has today. New rides use the compact layout first and are proven in the field. Only after that does a migration convert existing rides. A check against the rider's **real** `volty.db` gates the merge.

**Why (research `docs/superpowers/specs/2026-10-29-compact-telemetry-log-research.md`; numbers re-checked while planning, see "Size check"):**
- Today `HistorySampleV2` (`composeApp/src/commonMain/sqldelight/ru/sodovaya/volty/data/db/RideHistorySample.sq:1-16`) stores the 36-char ride UUID, a 76–115-char `sourceId` (`data/ble/NativeHistorySourcePlan.kt:57-63`, `native-history-v1:` + encoded identity), the metric id and the unknown reason **as text in every row**. The secondary index `HistorySampleV2_series_elapsed` repeats ride, source and metric. **The research missed one cost:** `PRIMARY KEY(rideId, sequence)` on a rowid table builds a *third* b-tree (`sqlite_autoindex`) that repeats the UUID once more.
- 35 % of a T4 ride's rows are unknowns for series the wheel never reports. Six of 17 series are dead, and each costs a row per frame (16,510 of 46,782 rows on the measured ride). The cause is that unknowns bypass the detail gate by design (`domain/history/HistoryDetailGate.kt:19`).
- At 20 Hz VESC (a later Part) the same layout costs ~100 KB/s. That rate cannot ship without this plan.

### Size check (done while planning; *estimated*, not a real DB)

Each layout was rebuilt in SQLite 3.49 (page 4096) with Python scripts in the planning scratchpad (`size\m.py`, `m2.py`, `mig.py`). The synthetic ride has the T4 shape: 17 series × 2,752 rows over 856 s, 6 dead series, inter-sample gaps of 279–323 ms, and readings as 2-decimal doubles. Source ids are 96/104/112 chars. Rows were inserted in time order in batches of 128, the way `BufferedHistoryWriter` commits (`FLUSH_EVENTS = 128`).

| layout | per 15-min T4 ride | note |
|---|---:|---|
| today, after `VACUUM` | **20.6 MB** | research said 19.5 MB; the gap is the id length. **Confirmed** to ±10 % |
| today, appended without `VACUUM` (3 rides) | 21.6 MB / ride | what a phone actually holds |
| compact, unknowns as rows, + `(ride, sequence)` index | 1.70 MB | |
| compact, unknowns as runs, + `(ride, sequence)` index | 1.20 MB (vacuumed) / 1.34 MB (fresh) | |
| compact, known rows only, **no sequence index**, REAL | **0.81 MB** | chosen layout (runs add ~20 rows) |
| same, value as scaled INTEGER ×100 | 0.62 MB | **rejected**: lossy (Begode speed is `raw × 0.036`; derived SoC/power are not decimal) |
| SQL conversion of 10 rides (470 k rows), unknowns as single-row runs, no sequence index | 1.12 MB / ride after `VACUUM` | migrated at **419 k rows/s on the PC**; WAL grew 13 MB for 215 MB in |

So the plan's figures are **~0.8 MB** for a new T4 ride with unknown runs, and **~1.1 MB** for a migrated ride before run compaction. Phone, real ids and real values are unmeasured until Task 1 gives us the rider's DB. Task 9 re-measures on it.

### Decisions (with numbers)

| question | decision | why |
|---|---|---|
| series table | `HistorySeriesV3(seriesId INTEGER PRIMARY KEY, rideNo, sourceId, metricId, cellIndex, UNIQUE(rideNo, sourceId, metricId, cellIndex))` | the long strings are stored once per series per ride: 17 rows, not 46,782 |
| ride number | `HistoryRideKeyV3(rideNo INTEGER PRIMARY KEY, rideId TEXT NOT NULL UNIQUE)` | a row here **is** the marker "this ride uses layout 3". No `ALTER` on `HistoryRideV2` |
| sample table | `HistorySampleV3(seriesId, elapsedMillis, sequence, epochSkew, clockDelta, reading REAL, unknownCode, runEndElapsed, runEndSequence, runCount, PRIMARY KEY(seriesId, elapsedMillis, sequence)) WITHOUT ROWID` | the PK *is* the existing series index's order (`pageSamples`, `rangeDetailStats`). `WITHOUT ROWID` stores the key once; a rowid table would add a second b-tree, as `HistorySampleV2`'s PK does today |
| elapsed | **absolute** `elapsedMillis` | a delta would break the PK range seek and make SQL `MIN/MAX` impossible. SQLite varints already make it 3–4 bytes |
| `epochMillis` per row | **no**: `epochSkew = epoch − (origin.epoch + elapsed − origin.elapsed)` | 0 in practice, and integer 0 costs 0 payload bytes. Exact, because any skew is stored |
| `clockGeneration` per row | **no**: `clockDelta = gen − ride.clockGeneration` | 0 in practice, so 0 bytes |
| value | `REAL` | bit-exact (SQLite already stores integral reals as integers). The scaled INTEGER saves 0.19 MB but is lossy |
| unknown reason | `unknownCode INTEGER`: 1 UNAVAILABLE, 2 PARTIAL_SOURCE, 3 INVALID_VALUE, 4 GAP, 5 LEGACY | fixed table in KDoc and never reordered. An unmapped code is `INVALID_DATA`. `PARTIAL_SOURCE` stays distinct for `HistoryPartialSourceBridge` |
| unknown runs | the same table: an unknown row has optional `runEndElapsed/runEndSequence/runCount` (NULL means a single unknown) | one PK order and one cursor. Known rows pay 3 header bytes for the NULLs (~90 KB per ride) |
| `(ride, sequence)` index | **dropped** (saves 0.43 MB/ride, 35 % of the result) | its three users are reworked: Task 5 (receipts), Task 6 (`lastElapsedOfOtherSources`), Task 7 (export order) |
| page size | keep 4096 | the Android default. A change needs a full `VACUUM` and gains nothing for 25-byte rows |
| migration shape | **two** migrations. `27.sqm` → `28.db` is additive (new tables; V2 untouched). `28.sqm` → `29.db` converts every V2 ride and drops V2 | new rides prove the new paths in the field before any old ride is touched |
| VACUUM | **not** inside a `.sqm`: `onUpgrade` runs in a transaction and `VACUUM` cannot. It is a separate post-migration step (Task 11) | after `28.sqm` the file keeps its old size (freelist) until it is vacuumed |

**Repo / branch:** `C:\Users\sodovaya\git\volty-nearby`, new branch `feat/compact-history` from `origin/main` @743f8385 (0.7.59). DB is at **v27**: `26.sqm` is the newest migration and `27.db` the newest snapshot (counted: 26 `.sqm`, `1.db`…`27.db`). This plan adds **`27.sqm` (→ `28.db`)** and **`28.sqm` (→ `29.db`)**. After each, run `generateCommonMainVoltyDatabaseSchema` once so the next snapshot lands, then `.\gradlew.bat :composeApp:verifyCommonMainVoltyDatabaseMigration`. On Windows this task needs `sqlitejdbc.dll` in `%USERPROFILE%\.gradle\workers\` (see `composeApp/build.gradle.kts:400-412`). Suite: `.\gradlew.bat :composeApp:testDebugUnitTest --rerun`. Keep `org.gradle.workers.max=4` and `-XX:ActiveProcessorCount=4` (AGENTS.md).

## Global Constraints
- **Read-only on every device.** Never write to a Begode's FFE1 (Global Constraint 5). Nothing in this plan sends a BLE byte.
- **SQLite 3.19 (minSdk 26):**
  - no `DROP COLUMN` and no `RENAME COLUMN`; schema removal is a table rebuild;
  - **no window functions** (3.25): run detection cannot be done in `.sqm` SQL with `LAG`;
  - no `VACUUM INTO` (3.27);
  - no `UPSERT` (3.24).

  Every `.sq`/`.sqm` statement must run on 3.19. Task 4 adds a test that greps the `.sq`/`.sqm` files for `OVER (`, `ON CONFLICT … DO`, `VACUUM INTO`, `DROP COLUMN` and `RENAME COLUMN`.
- **`N.sqm` migrates `N.db` to `(N+1).db`.** Count before writing one. Commit the regenerated snapshot with the migration.
- **"Without loss" means exactly this,** and every conversion test asserts it.
  1. Every **known** V2 row reappears once, with the same `(rideId, sequence, sourceId, metricId, cellIndex, epochMillis, elapsedMillis, clockGeneration)`, and its `reading` is bit-equal (`toRawBits`, with SQLite's −0.0 → 0.0 canonicalisation already accepted by `HistoryBatchReceipts.sameReading`).
  2. Every **unknown** V2 row is either a row with the same fields and reason, or lies inside a run. A run is a maximal chain of consecutive unknown rows (in `(elapsed, sequence)` order of one series) whose members all have the same reason, clock generation and epoch skew, with every step ≤ `HISTORY_MAX_INTERVAL_MILLIS`, and with no `HISTORY_GAP_MARKERS` marker (global or for that source) at an elapsed in `(prev, next]`. A run keeps its first row's fields, its last row's `(elapsed, sequence)` and its member count.
  3. Interior unknown ticks of a run are **not** kept. This is the only information dropped. Task 3 proves, reader by reader, that no output depends on it.
  4. GPS, markers, buckets, sources, receipts with a fingerprint, favourites and media are untouched.
- **Every reader keeps its output.** For the purposes of this plan, "reader" means:
  - `HistoryStore.samples` paging (with the run-expansion rule of Task 3), `stats`, `buckets`, `route`, `markers`, `recordedKeys`, `lastSampleElapsedOfOtherSources`;
  - `DefaultHistoryReadRepository`: the completeness walk with its ride-end tail rule and `walkCache`, energy repair and its cache, `hasKnownValue`;
  - `HistoryTotalsComponent` totals: ride distance with `CounterStepGate`, moving time, battery used;
  - robust peaks with `HistoryPeakCache`, card stats, the chart overview (`HistoryOverview`, `HistoryOverviewAccumulator`, `HistoryPartialSourceBridge`);
  - the logui CSV (`LoguiHistoryExportEncoder` via `SqlDelightHistoryExportSource`), GPX, deletion, legacy rides (`RideRowV1`/`RidePointRow`, untouched).

  Where an output may legitimately change, the task names it, says why, and records a retraction in KDoc.
- **Incoherent fixtures where data is absent:** a run whose reason changes mid-chain; a run that crosses a clock generation; a known value with `unknownCode` set; `runCount = 1` with `runEndSequence ≠ sequence`; a series whose only evidence of continuity is a run.
- Compose is not unit-testable. The migration progress screen and the debug share sheet are judged on device. Say so plainly; every decision behind them lives in a pure function or component with tests.
- Tests:
  - No unbounded `runTest` loops; the writer's `ticks` flow is always injected and finite.
  - Component tests inject test dispatchers for every IO context and destroy lifecycles before `Dispatchers.resetMain()`.
  - Assert on what was stored, published or returned, never on timing alone.
- Record retractions in KDoc ("an earlier revision …; that was wrong because …").
- Strings go in **both** `values/` and `values-ru/strings.xml`, with no backslash escapes.
- **Suite:** `.\gradlew.bat :composeApp:testDebugUnitTest --rerun`. Wipe `composeApp/build/test-results/testDebugUnitTest` first and count the `testcase` elements in the XML. The count must equal the previous count plus the tests the task adds. `BUILD SUCCESSFUL in 1s` proves nothing.
- **The rider's DB is private data** (GPS tracks). It never enters the repo, a fixture or a commit. Tools read it from a path passed as a system property and write reports to the session scratchpad.

## Task 1: Debug "share database" action (gets the rider's real DB off the phone)

**Why:** No real `volty.db` has ever been measured. The research rebuilt its 19.5 MB from code, and so did this plan. The app has **no** DB backup or export: grep finds no `getDatabasePath`, and `AndroidSqlDriverFactory.kt:9` opens `volty.db` with no backup hook. Release builds are not debuggable, so `adb run-as` is closed, and `adb backup` is disabled for apps that target API 31+. A share action next to «Отправить логи» (`SettingsComponent.onSendLogs`, `SettingsComponent.kt:255`, `SettingsScreen.kt:920`) is the only route. It comes first so the rider can send the DB while the rest is built. Task 2's baseline needs it too.

**Files:** `diagnostics/DatabaseSnapshotExporter.kt` (expect) + `androidMain/.../diagnostics/DatabaseSnapshotExporter.android.kt`; `presentation/settings/SettingsComponent.kt`, `SettingsScreen.kt`; `values/strings.xml`, `values-ru/strings.xml`; the FileProvider paths resource referenced at `AndroidManifest.xml:104` (`@xml/file_paths`; find where it lives before editing); `di/AndroidModule.kt`.

**Rules:**
- The pure decision `databaseSnapshotPlan(recording: Boolean, dbBytes: Long, freeBytes: Long): SnapshotPlan` returns one of:
  - `Refuse(RECORDING)` while a ride is recording;
  - `Refuse(NO_SPACE)` when `freeBytes < dbBytes + 16 MiB`;
  - else `Copy`.
- The copy runs on IO:
  1. `PRAGMA wal_checkpoint(TRUNCATE)` through the app's own driver.
  2. Inside one `BEGIN IMMEDIATE` transaction, so no writer can commit mid-copy, copy `volty.db` to `cacheDir/db-share/volty-<versionName>-<yyyyMMdd-HHmm>.db`.
  3. Share it through the existing FileProvider with `application/vnd.sqlite3`.
  4. Delete older snapshots in that directory first.

  `VACUUM INTO` is not available (SQLite 3.19).
- **Read-only on the DB.** The only statements are the checkpoint and a transaction with no writes.
- Label: «Поделиться базой данных (отладка)» / "Share database (debug)". The secondary line says the file holds GPS tracks: «Файл содержит треки поездок».

**Tests (commonTest, `DatabaseSnapshotPlanTest`):**
- [ ] `refusesWhileRecording`: `recording = true, dbBytes = 1, freeBytes = Long.MAX_VALUE` → `Refuse(RECORDING)`. The fixture is incoherent on purpose: unlimited space, still refused.
- [ ] `refusesWithoutSpaceForCopyPlusMargin`: `dbBytes = 100 MiB, freeBytes = 100 MiB + 16 MiB − 1` → `Refuse(NO_SPACE)`; with `+ 16 MiB` → `Copy`.
- [ ] `settingsDelegatesShareToExporter`: build `SettingsComponent` with a fake exporter; `onShareDatabase()` records exactly one call. Inject test dispatchers and destroy the lifecycle before `resetMain`.
- [ ] Device-only, stated plainly: the share sheet and that the copied file opens in `sqlite3` with `PRAGMA integrity_check` = `ok`. The rider runs this once.

- [ ] Implement, run the suite (wiped results, XML count = previous + 3), commit `feat(debug): share a consistent copy of the database`. Ship it in the next APK, **before** any schema change.

## Task 2: Reader fingerprint tool and baseline on v27 code

**Why:** "Every reader keeps its output" has to be checked, not argued. The tool runs every reader over every ride of a DB file and writes a canonical text fingerprint. It is run on v27 code now (baseline) and on v29 code after the conversion (Task 9). The diff must be empty, except for lines a task names in advance.

**Files:** `androidUnitTest/kotlin/ru/sodovaya/volty/data/history/HistoryReaderFingerprint.kt` (tool) and `HistoryReaderFingerprintTest.kt`. JVM only: it uses `JdbcSqliteDriver` on a file, as `HistoryPreviewPurgeRecoveryTest.kt:121` already does.

**Rules:**
- Input is `-Dvolty.fingerprintDb=<path>`, always a **copy**: the tool copies to a temp file first and never opens the original. Output is `-Dvolty.fingerprintOut=<path>`. Without the property the test is skipped with a printed reason; it does not pass silently.
- For each ride in `HistoryRideV2` (ordered by id) and each key from `recordedKeys` (limit 20000), emit lines `ride|key|reader|value`:
  - `samplesFull`: every page at `HISTORY_DETAIL_PAGE_SIZE`; each sample as `elapsed,epoch,gen,rawBits|reason`. **Collapsed form:** a maximal unknown chain under rule 2 of Global Constraints is reduced to its first and last member plus `×count`. The v27 baseline must be written in this form too, so the two sides compare like with like.
  - `stats` (all fields, including `pendingWalk` and `hasGap`), `buckets` (all pages), `walk` verdict, `hasKnownValue`, `lastSampleElapsedOfOtherSources(ride, source, ride.end)`, `overview` points (600 px).
  - Totals (distance, moving time, battery used), energy repair, robust peak, card stats, through the same pure functions the components call.
  - Per ride: `markers`, `route`, the logui CSV bytes as a SHA-256 plus `droppedLate`, and the GPX SHA-256.
- Per ride, the counts: known rows, unknown rows, maximal chains.
- The output is deterministic: no wall clock, no hash-map order.

**Tests:**
- [ ] `fingerprintIsDeterministic`: build a fixture DB from `SqlDelightHistoryStoreTest`'s helpers with 2 rides. Ride A has a dead series of 40 UNAVAILABLE rows. Ride B has controller power alternating value/PARTIAL_SOURCE plus a BLE_GAP marker inside an unknown chain. Two runs give byte-identical output.
- [ ] `collapsedFormSplitsAtEveryRule2Boundary`: one series of unknowns at elapsed 0, 300, 600 (UNAVAILABLE), 900 (PARTIAL_SOURCE), 1200, 3300 (gap > 2000), a `BLE_GAP` marker at 3400, then 3600, and a clock generation +1 at 3900 → chains `[0..600 ×3] [900] [1200] [3300] [3600] [3900]`.
- [ ] Baseline step (not a test): when Task 1's file arrives, run the tool on it at the branch tip **before Task 4**. Store the output under the scratchpad, never in the repo, and record in the ledger its row counts, file size, `page_count`, `freelist_count` and the per-ride bytes (`dbstat` if the JDBC sqlite has it, else `page_count` deltas).

- [ ] Implement, suite, commit `test(history): reader fingerprint tool for real-database checks`.

## Task 3: Unknown runs: the pure model and a proof for each reader

**Why:** The research flagged that unknown rows act as evidence that the ride went on, in `HistoryBrokenAccumulator`, the walk's tail rule (`DefaultHistoryReadRepository.kt:126-148`, where `broken.lastElapsed` includes unknowns), `lastElapsedOfOtherSources` and `bridgePartialSourceArtefact`. Collapsing a run is only allowed once every reader is shown invariant. This task has **no storage**: pure functions and property tests.

**Files:** `domain/history/HistoryUnknownRun.kt` (new):
- `HistoryStoredSample(sample, sequence, run: HistoryUnknownRunEnd?)` with `HistoryUnknownRunEnd(tick, sequence, count)`;
- `historyRunContinues(prev, next, markers): Boolean` (rule 2);
- `collapseUnknownRuns(samples, markers)`;
- `expandForReaders(stored): List<HistorySample>`: a run expands to its first sample, plus its end sample when `count ≥ 2`.

Test: `HistoryUnknownRunEquivalenceTest.kt`.

**Rules (each with its proof, written in KDoc):**
- `HistoryBrokenAccumulator.offer`: inside a run, every step is unknown, ≤ max interval, with no marker and no clock boundary, so each step adds `delta` to `brokenMillis` and 0 to `gapMillis`. The interior steps sum to `end − first`, which the expanded pair adds in one step. `lastElapsed` is the run end in both forms, so the tail rule (`partial(rideEnd)`) sees the same last tick.
- `HistoryContinuityIndex.breakTick` treats any unknown as a break. Interior unknowns add no drawn point.
- **`HistoryOverview` is the one predicted difference.** Point selection marks unknowns and their neighbours as mandatory (`HistoryOverview.kt:37`), so removing interior unknowns frees budget, and a series over the 600-point budget may choose different points.
  - The test asserts that drawn known points and break positions are equal whenever the series fits the budget.
  - KDoc states the over-budget case as an accepted change: more real points drawn, none invented.
  - Task 2's fingerprint lists `overview` lines and marks them "may differ only above budget".
- `HistoryPartialSourceBridge`: a PARTIAL_SOURCE run in `controller_power_w` between two known values ≤ 2000 ms apart is bridged in both forms. If the known value after the run is > 2000 ms after the previous known value, both forms break.
- `rangeDetailStats.unknownCount` is only compared with 0 (`HistoryStoreReader.kt:52`). The V3 SQL is `SUM(COALESCE(runCount, 1))` over unknown rows, so the result is equal in every case.
- `nearestHistorySample` (chart cursor): at a tick inside a run, the old nearest sample was an interior unknown (→ null). The new nearest could be a known value up to `maxAgeMillis` away. **New rule:** a cursor tick inside `[first, end]` of a run → null. It is implemented by the read path in Task 6 through a `runCovers(tick)` probe.
- `CounterStepGate` distance, moving time, battery used, robust peaks and `hasKnownValue` read known values only. Known rows are unchanged, so these are invariant by construction. The test still feeds `[unknown, known, unknown]` and asserts that no run spans the known row.

**Tests (each compares reader(full) with reader(expandForReaders(collapse(full)))):**
- [ ] `brokenAccumulatorInvariantOverDeadSeries`: 2,752 UNAVAILABLE samples at the measured 279–323 ms cadence over 856 s. `partial(856_000)`, `brokenMillis` and `gapMillis` are equal: all broken, no gap.
- [ ] `brokenAccumulatorKeepsGapInsideUnknowns`: unknowns at 0, 300, then 2600 (a 2300 ms step) → two runs; `gapMillis = 2300` in both forms.
- [ ] `tailRuleSeesRunEnd`: a BMS series known over 0–60 s, unknown over 60–1200 s (one run), BLE_GAP at 1200 s, ride end 3600 s → `lastElapsed = 1_200_000` and the same `partial` in both forms.
- [ ] `markerInsideChainSplitsRun`: a source-scoped BLE_GAP at 450 between unknowns at 300 and 600 → two runs; `breakTick` results are equal.
- [ ] `bridgeInvariantForPartialSourceRun`: power 1.0@0, PARTIAL_SOURCE@300 and @600, 2.0@900 → bridged identically. With 2.0@2400 instead → not bridged in either form.
- [ ] `incoherentRunAcrossGenerationIsSplit`: an unknown at gen 3 @0, then gen 4 @300 → two runs; a clock boundary is never folded.
- [ ] `incoherentReasonChangeSplits`: UNAVAILABLE@0, PARTIAL_SOURCE@300 → two runs, and both reasons survive expansion.
- [ ] `overviewEqualWithinBudget`: 500 known + 1,000 unknown samples in 3 chains → identical drawn points and breaks.
- [ ] `cursorInsideRunReadsUnknown`: known 5.0@0, run 300..3000, known 6.0@3300, `maxAge` 5000; the cursor at 1500 → null.

- [ ] Implement, suite, commit `feat(history): unknown runs with per-reader equivalence proofs`.

## Task 4: `27.sqm` → `28.db`: additive compact tables (V2 untouched)

**Why:** New rides need somewhere to go before old rides are converted. An additive migration is cheap: it does no data work, cannot lose a row, and runs in milliseconds inside `onUpgrade`.

**Files:** `sqldelight/ru/sodovaya/volty/data/db/RideHistoryCompact.sq` (new: the three tables and their queries), `27.sqm` (new), `databases/28.db` (generated), `MigrationSqlCompatibilityTest.kt` (new, commonTest).

**Rules:**
- `27.sqm` contains only these `CREATE`s, and **counted**: it is the 27th migration file, and `27.db` already exists.
  ```sql
  CREATE TABLE HistoryRideKeyV3 (rideNo INTEGER NOT NULL PRIMARY KEY, rideId TEXT NOT NULL UNIQUE);
  CREATE TABLE HistorySeriesV3 (seriesId INTEGER NOT NULL PRIMARY KEY, rideNo INTEGER NOT NULL,
    sourceId TEXT NOT NULL, metricId TEXT NOT NULL, cellIndex INTEGER NOT NULL DEFAULT -1 CHECK(cellIndex >= -1),
    UNIQUE(rideNo, sourceId, metricId, cellIndex));
  CREATE TABLE HistorySampleV3 (seriesId INTEGER NOT NULL, elapsedMillis INTEGER NOT NULL, sequence INTEGER NOT NULL,
    epochSkew INTEGER NOT NULL DEFAULT 0, clockDelta INTEGER NOT NULL DEFAULT 0, reading REAL, unknownCode INTEGER,
    runEndElapsed INTEGER, runEndSequence INTEGER, runCount INTEGER,
    PRIMARY KEY(seriesId, elapsedMillis, sequence),
    CHECK((reading IS NULL AND unknownCode BETWEEN 1 AND 5) OR (reading IS NOT NULL AND unknownCode IS NULL)),
    CHECK((runCount IS NULL AND runEndElapsed IS NULL AND runEndSequence IS NULL) OR
      (unknownCode IS NOT NULL AND runCount >= 2 AND runEndElapsed >= elapsedMillis AND runEndSequence > sequence))
  ) WITHOUT ROWID;
  ```
- `HistorySeriesV3`'s `UNIQUE` index gives `seriesFor(rideNo, source, metric, cell)` and `seriesOfRide(rideNo)`. **No** sequence index (see Decisions). No FKs: application `foreign_keys` are OFF (`SqlDelightHistoryStore.kt:169`), and deletion is explicit.
- Regenerate so `28.db` lands, then `verifyCommonMainVoltyDatabaseMigration` must pass.

**Tests:**
- [ ] `migration27KeepsEveryV2RowAndAddsEmptyV3`: open a JDBC copy of `27.db`, insert 1 ride + 3 samples (V2), apply `27.sqm`, and assert that the V2 count is 3 and the V3 tables exist and are empty.
- [ ] `noSqlNewerThan3_19`: scan every `.sq`/`.sqm` for the forbidden constructs listed in Global Constraints. Fail with file:line.
- [ ] `checkRejectsIncoherentRows`: insert `reading = 1.0, unknownCode = 1` → constraint failure; `runCount = 1` → failure; `runEndSequence = sequence` → failure.
- [ ] `verifyCommonMainVoltyDatabaseMigration` passes (Gradle task output pasted in the ledger, not cached: run with `--rerun-tasks` on that task).

- [ ] Implement, suite, commit `feat(db): 27.sqm adds compact history tables`.

## Task 5: Write path: new rides record into V3

**Why:** New rides must prove the layout before the conversion touches old ones. The writer's contract (`HistoryStore.append`, receipts, `nextSequence`, `advanceSequence`) is unchanged. Only the rows differ.

**Files:** `data/history/SqlDelightHistoryStore.kt` (`begin`, `append`, `delete`), `HistoryBatchReceipts.kt`, new `data/history/HistoryCompactSampleWriter.kt`, `RideHistoryCompact.sq` (insert/extend queries).

**Rules:**
- `begin` inserts `HistoryRideKeyV3(rideId)` in the same transaction as `insertRide`, **for every new ride**. `HistoryLayout.of(rideId)` is "has a ride key", and readers dispatch on it (Task 6).
- `append`, for each sample, in batch order (sequence assignment unchanged: `admit(t)`):
  1. Resolve `seriesId`, inserting `HistorySeriesV3` on first sight. Use a per-transaction map, never a cross-transaction cache.
  2. Compute `epochSkew` and `clockDelta` against the ride origin.
  3. If the sample is unknown, and the series' newest V3 row is an unknown with `historyRunContinues(newest, sample, markersSince(newest))` true: on a single row, set `runCount = 2, runEnd… = sample`; on an existing run, set `runCount + 1` and `runEnd… = sample`. Else insert a row.
- "Newest row" is `ORDER BY elapsedMillis DESC, sequence DESC LIMIT 1` on the PK: one seek. `markersSince` comes from the **same batch's** markers plus `HistoryMarkerV2` rows with `elapsed > newest.elapsed`, all inside the transaction. A marker committed in an earlier batch after the newest row is therefore seen.
- Crash safety: run extension is an `UPDATE` in the batch's transaction. A killed process leaves either the old run or the extended one, never half. A retry with the same receipt is answered by the fingerprint (`HistoryBatchReceipts.verifyCommitted` line 12), not by re-reading rows.
- **Legacy receipts** (`fingerprint IS NULL`) cannot be re-verified against a run. For a V3 ride, `verifyCommitted` throws on a null fingerprint (it cannot exist: `record` always writes one). For V2 rides the old path stays until Task 9.
- `delete`: when the ride has a key, delete its `HistorySampleV3` rows (`WHERE seriesId IN (SELECT seriesId FROM HistorySeriesV3 WHERE rideNo = ?)`), its series and its key, alongside the existing V2 child deletes. Do the same in `SqlDelightHistoryRideDeletion.confirm` (lines 49-58).

**Tests (`SqlDelightHistoryStoreCompactTest`, in-memory JDBC):**
- [ ] `newRideGetsKeyAndNoV2Rows`: begin + append 10 known samples → 10 `HistorySampleV3` rows, 0 `HistorySampleV2` rows, and 1 series per key.
- [ ] `deadSeriesBecomesOneRun`: 2,752 UNAVAILABLE samples over 7 batches of ≤ 128 at 279–323 ms → **one** row, `runCount = 2752`, run end = the last tick and sequence.
- [ ] `markerInLaterBatchSplitsRun`: unknowns @0, @300 in batch 1; batch 2 holds only a `BLE_GAP` for that source at @450; batch 3 holds an unknown @600 → 2 rows.
- [ ] `knownValueBetweenUnknownsIsItsOwnRow`: U, U, K, U, U → 3 rows (run, known, run); the known row's `reading` is bit-equal.
- [ ] `incoherentSkewIsStoredExactly`: a sample whose `epochMillis` is 7 ms off the origin axis (`epochSkew = 7`) reads back with the exact epoch. A gen+1 sample gives `clockDelta = 1` and never extends a gen-0 run.
- [ ] `receiptRetryAfterRunExtensionIsIdempotent`: append batch B with receipt R, then append B again → row count and `runCount` unchanged. A different payload under R → `IllegalArgumentException` (existing message).
- [ ] `deleteRemovesV3Rows`: delete the ride → 0 rows in all three V3 tables.

- [ ] Implement, suite, commit `feat(history): record new rides in the compact layout`.

## Task 6: Read path: every `HistoryStore` reader on V3 rides

**Why:** Readers dispatch per ride: V3 rides read the compact tables, and rides without a key read V2 until Task 9. Three V2 queries rely on a sequence order that V3 no longer indexes. Each gets an exact replacement or a recorded retraction.

**Files:** `data/history/HistoryStoreReader.kt`, `SqlDelightHistoryStore.kt` (`lastSampleElapsedOfOtherSources`), `RideHistoryCompact.sq`, `RideHistorySeries.sq` (`recordedKeysForSource`).

**Rules:**
- `samples`:
  - Page on the PK with `(elapsed, sequence) > cursor`, map rows through `expandForReaders`, and keep `HistoryCursor.stableId = sequence`.
  - **A run is never split across pages:** if only one slot is left, the page ends before the run.
  - The cursor after an expanded run is its `(runEndElapsed, runEndSequence)`. The next page's predicate is `(elapsedMillis, sequence) > (runEndElapsed, runEndSequence)`. No row of the same series can lie inside a run (Task 5 rule 3), so the predicate skips nothing.
- `stats`:
  - V3 `rangeDetailStats` = `MIN(reading)`, `MAX(reading)`, `SUM(CASE WHEN unknownCode IS NULL THEN 0 ELSE COALESCE(runCount, 1) END)` and `MAX(COALESCE(runEndElapsed, elapsedMillis))`, restricted to `elapsedMillis` in range.
  - A run starting in range but ending after `toElapsed` contributes `toElapsed`, not its end. Clamp it in SQL with `MIN(runEndElapsed, :to)`; V2 would only have seen members ≤ `to`.
  - A run starting before `fromElapsed` that reaches into the range also counts: query `elapsedMillis <= :to AND COALESCE(runEndElapsed, elapsedMillis) >= :from`.
- `lastSampleElapsedOfOtherSources`: **retraction.** V2 took the max elapsed among the last 200 rows by sequence (`RideHistorySample.sq:37-41`), an approximation chosen because it was cheap. V3 computes the **exact** max over every other series of the ride: one PK seek per series, `MAX(COALESCE(runEndElapsed, elapsedMillis))` ≤ `to`, plus the run clamp. It can only be ≥ the V2 value, and equal whenever the true max lay in the last 200 rows. Record this in KDoc. The Task 9 fingerprint diff must show every ride equal, or list the rides where V2's window missed.
- `recordedKeys`: V3 reads `HistorySeriesV3` for `(rideNo, sourceId)` with the same metric/index admission, `UNION` buckets, then `ORDER BY` and `LIMIT` as now. A series that only holds a run is recorded, exactly as an all-unknown series is today.
- `nearestHistorySample` callers get `runCovers(tick)` through the expanded samples. Expansion marks the pair (add `runEnd: HistoryTick?` on an internal wrapper, not on `HistorySample`), and the reader-side helper returns null inside a run (Task 3 rule).

**Tests (`HistoryStoreReaderCompactTest`):** for each test, write the same stream into a V2 ride (via raw V2 inserts) and a V3 ride (via `append`), then compare.
- [ ] `samplesPagesEqualToCollapsedV2`: 3 series (known, dead, alternating PARTIAL_SOURCE) at page sizes 1, 2, 3, 7 and 20000 → the concatenation of pages equals `expandForReaders(collapse(v2))`, and no page ends between a run's two halves.
- [ ] `statsEqualV2`: the 856 s T4-shaped stream with ranges [0, end], [100 s, 200 s] (cutting a run on both sides) and a range inside one run → equal `HistoryStats`, including `pendingWalk` and `complete`.
- [ ] `otherSourcesExactMax`: source A ends at 60 s, source B is a run to 1200 s → 1_200_000 on V3. Also an incoherent fixture where V2's 200-row window misses an out-of-order row at 1300 s that lies outside the window: V3 returns 1300 s and V2 1200 s, the KDoc'd retraction.
- [ ] `recordedKeysIncludeRunOnlySeries`: the dead series is listed. The order and `truncated` flag equal V2's.
- [ ] `walkVerdictEqual`: `DefaultHistoryReadRepository` over both rides → the same `(complete, hasGap)` and the same cached answer. Use a fake clock-free test store and an injected dispatcher.

- [ ] Implement, suite, commit `feat(history): read compact rides through every store reader`.

## Task 7: Export, GPX and shape on V3 rides

**Why:** `exportSamples` pages the whole ride **by sequence** across series (`HistoryExportRead.sq:14-17`), and `exportV2Shape` counts rows. V3 has no sequence index.

**Files:** `data/history/SqlDelightHistoryExportSource.kt`, `HistoryExportRead.sq` (V3 queries), `LoguiHistoryExportEncoder.kt` (read only, unless a test proves a change is needed).

**Rules:**
- V3 sample stream: a k-way merge over the ride's series, each paged on its PK at `pageSize`, ordered by `(sequence)` of each row's first member. Within one series, PK order equals sequence order **iff** that series' elapsed never decreases in sequence order. The writer guarantees this for projected recordings (`HistoryDetailGate.kt:12` requires it). Task 9 counts violations on the real DB.
  - If there are none, the merge reproduces V2's sequence order exactly, and the logui bytes and `droppedLate` are equal.
  - If there are any, ordering by `(elapsed, sequence)` gives time order and `droppedLate` can only fall. Record that as a retraction, and list those rides in the fingerprint diff.
- A run is emitted as its expanded pair. `shape.sampleCount` for V3 = known rows + Σ(`runCount ≥ 2 ? 2 : 1`) over unknown rows, i.e. the number of records emitted. Count it in SQL in the same snapshot.
- Each page is still its own short transaction, and `unchanged(snapshot)` runs per page as today.
- GPX reads no samples. Assert that it is unaffected.

**Tests (`SqlDelightHistoryExportSourceCompactTest`):**
- [ ] `loguiBytesEqualBetweenV2AndV3`: the 856 s T4-shaped stream with dead series and GPS at 1 Hz, written to both layouts → the encoder outputs are byte-equal and `droppedLate` is equal (0).
- [ ] `mergeReproducesSequenceOrder`: 3 series with interleaved sequences 0…299 → the emitted order is the sequences ascending.
- [ ] `shapeCountMatchesEmitted`: the export completes without `SOURCE_CHANGED`. Appending one sample to the ride between two pages → `SOURCE_CHANGED`. The ride must be RECORDING-free, so emulate it by mutating rows directly and assert that the shape check catches it.
- [ ] `gpxEqual`.

- [ ] Implement, suite, commit `feat(export): read compact rides`.

## Task 8: Field gate: fresh rides on the compact layout

**Why:** Tasks 4–7 are proven on fixtures. The rider's wheel is the only source of real interleavings (several Begode frame types at ~3.2 Hz each, reconnects, clock changes). The conversion of old rides waits for this.

**Steps:**
- [ ] Release build `0.7.60` (`assembleRelease`, offline flags per the release memory). The rider records **at least two** rides: one T4 ≥ 10 min, and one with a BLE drop or the app backgrounded. They open each in history (summary, charts, card) and export logui CSV + GPX.
- [ ] The rider shares the DB (Task 1). Run the fingerprint tool. For each **new** (V3) ride:
  1. Its own readers are self-consistent: every page reads, the walk terminates, and the export succeeds.
  2. Per-ride bytes: `page_count` delta, or `dbstat` when available. Expected **≈ 0.8 MB per 15 min of T4**; a result above 1.5 MB stops the plan for a re-think.
  3. Count runs, `runCount` distribution and known rows.
- [ ] Old (V2) rides in the same file: the fingerprint equals the Task 2 baseline line for line (the read paths for V2 must not have moved).
- [ ] Record everything in the ledger. **No code; no commit** unless a defect is found (then: fix task, tests, suite, commit, and a new field round).

## Task 9: `28.sqm` → `29.db`: convert every V2 ride and drop V2

**Why:** Old rides keep ~20 MB each until converted. The conversion runs only after Task 8, and the real-DB check gates the merge.

**Files:** `28.sqm` (new; counted: the 28th migration, `28.db` exists after Task 4), `databases/29.db` (generated), `RideHistorySample.sq` (deleted: the V2 table is gone), `RideHistorySeries.sq`, `HistoryExportRead.sq`, `HistoryBatchReceipts.kt`, `HistoryStoreReader.kt`, `SqlDelightHistoryStore.kt`, `SqlDelightHistoryRideDeletion.kt` (V2 branches removed), `HistoryMigrationConversionTest.kt` (androidUnitTest, JDBC file DB).

**Rules for `28.sqm` (pure SQL, SQLite 3.19, runs inside `onUpgrade`'s single transaction):**
1. `INSERT INTO HistoryRideKeyV3(rideId) SELECT id FROM HistoryRideV2 WHERE id NOT IN (SELECT rideId FROM HistoryRideKeyV3) ORDER BY startedEpochMillis, id;`
2. Insert `HistorySeriesV3` with `SELECT DISTINCT` of `(rideNo, sourceId, metricId, cellIndex)` from V2 joined to the key.
3. `INSERT INTO HistorySampleV3 SELECT …` from V2 joined to the ride (origin for skew/delta), the key and the series:
   - `unknownCode` via `CASE unknownReason … END`;
   - run columns NULL: every unknown becomes a single row; **runs are not detected in SQL** (no window functions in 3.19);
   - an unmapped reason makes `CASE` yield NULL with `reading` NULL, which fails the `CHECK`, so the whole migration aborts rather than inventing a reason.
4. Delete legacy receipts that cannot be verified anymore: `DELETE FROM HistoryBatchReceiptV2 WHERE fingerprint IS NULL;`. They certify in-memory retries of a process that no longer exists; any ride still `RECORDING` is closed by `recoverInterrupted` on start.
5. `DROP INDEX HistorySampleV2_series_elapsed; DROP TABLE HistorySampleV2;`. This is a table rebuild in the 3.19 sense: no `DROP COLUMN` is needed.

- The `INSERT … SELECT` must read V2 through `HistorySampleV2_series_elapsed` so that **no temp B-tree sort** is needed: the order is already `(ride, source, metric, cell, elapsed, sequence)`, and the series ids are assigned in that same order in step 2. On a phone, a global sort of millions of rows would spill hundreds of MB to temp. A test asserts the `EXPLAIN QUERY PLAN` has no `USE TEMP B-TREE`.
- Transaction semantics: `AndroidSqliteDriver`'s open helper runs `onUpgrade` in one transaction. A kill mid-way rolls back to v27/v28 on the next open, and the migration re-runs from scratch. That is safe and idempotent, but not incremental. Free space needed: the new rows (~5 % of V2) plus journal/WAL. Measured: 13 MB of WAL for 215 MB of V2 on the PC run. Dropping V2 frees pages to the freelist without writing them.
- Time: the PC measured 419 k rows/s. Assume **5–10× slower on a phone (estimate)**. 200 T4 rides of 15 min (9.4 M rows, ~4 GB of V2) → ~2–4 min; a typical DB of tens of rides → seconds. Task 10 handles the UI for this.

**Tests:**
- [ ] `conversionIsLosslessRowByRow`: a JDBC file DB at `28.db` holds 3 V2 rides. Ride 1 is the T4-shaped 46,782 rows. Ride 2 has `epochSkew ≠ 0`, a clock generation +1 mid-ride, −0.0, 1e-300, and a derived non-float32 value. Ride 3 is an INTERRUPTED ride with no samples. Apply `28.sqm`, then join back:
  - every V2 row (captured before migration into a Kotlin list) has exactly one V3 row with equal `(ride, sequence, source, metric, cell, epoch, elapsed, gen, rawBits(reading), reason)`;
  - the counts are equal;
  - no V3 row is left without a V2 row.
- [ ] `incoherentUnknownReasonAbortsMigration`: a V2 row with `unknownReason = 'BOGUS'` → the migration throws and the DB is still at the old version with V2 intact (the transaction rolled back).
- [ ] `killMidMigrationLeavesV2Intact`: run `28.sqm` statements 1–3 in a transaction, then roll back as the kill → every V2 row and table still present, `HistoryRideKeyV3` without the old rides; re-running in full succeeds.
- [ ] `noTempSortInConversion`: `EXPLAIN QUERY PLAN` of step 3 contains no `USE TEMP B-TREE`.
- [ ] `legacyNullFingerprintReceiptsRemoved`, `fingerprintedReceiptsKept`.
- [ ] `verifyCommonMainVoltyDatabaseMigration` passes with `--rerun-tasks`, and `29.db` is committed.
- [ ] **Real-DB gate, required before merge:**
  1. Copy the rider's DB (Task 1, taken **after** Task 8). Measure the original's file size, `page_count` and `freelist_count`.
  2. Migrate the copy with the app's `Schema.migrate` through `JdbcSqliteDriver` (27/28 → 29) and time it.
  3. Run the fingerprint tool on the result with this branch's code, then diff against the baseline taken on the **same** file with v28 code. The diff must be empty, except:
     - `overview` above budget (Task 3);
     - `lastSampleElapsedOfOtherSources` where V3 is larger (Task 6);
     - export order or `droppedLate` for rides with per-series elapsed regressions (Task 7, counted here).

     Each listed difference must show **no** change in walk verdict, totals, peaks, card stats or the logui bytes. Otherwise stop.
  4. Then `VACUUM` the copy, and record the size before and after and the bytes per ride.

- [ ] Implement, suite, commit `feat(db): 28.sqm converts every ride to the compact layout`.

## Task 10: Open the database off the main thread, with a progress screen for long migrations

**Why:** `AndroidSqliteDriver` opens lazily on the first query, and `onUpgrade` runs on whichever thread asks first. A multi-minute conversion on the main thread is an ANR, and the rider would kill the app (safe, per Task 9, but it would never finish).

**Files:** `androidMain/.../data/db/AndroidSqlDriverFactory.kt`, new `data/db/DatabaseOpenGate.kt` (common: a state flow of `Opening`, `Migrating(fromVersion)`, `Ready`, `Failed`), `App.kt` root (shows a blocking «Обновляем историю поездок…» screen while `Migrating`), `di/AndroidModule.kt`, strings in both locales.

**Rules:**
- On IO at app start, open the DB before any repository touches it: `driver` construction plus one `SELECT 1`. Before opening, read the version with a separate read-only `SQLiteDatabase.openDatabase(..., OPEN_READONLY)` and `PRAGMA user_version`. If it is below `Schema.version`, publish `Migrating`.
- The pure decision `migrationNotice(storedVersion, targetVersion): Notice?` shows the screen only when `storedVersion in 1 until 29` and `targetVersion ≥ 29` (the conversion migration). A fresh install (`0`) shows nothing.
- Recording cannot start before `Ready`. BLE monitoring may run, since it does not touch the DB; check `RideRecordingController` for DB use first.
- `Failed` shows the error with «Отправить логи» and does **not** delete the DB.

**Tests:**
- [ ] `noticeOnlyForConversionMigration`: `(27, 29)` → shown; `(28, 29)` → shown; `(0, 29)` → none; `(29, 29)` → none; `(30, 29)` → none (a downgrade is not our screen).
- [ ] `gatePublishesReadyAfterOpen`: a fake opener that suspends until released; the state goes Opening → Migrating → Ready; recording start before Ready is refused (component test, injected dispatchers, lifecycle destroyed before `resetMain`).
- [ ] `failureKeepsDatabase`: the opener throws → `Failed`, and no delete call is issued on the fake file API.
- [ ] Device-only, stated plainly: the screen's look, and that a 1 GB test DB migrates without an ANR. The latter is measured once with a DB built by the Task 9 tool and pushed to a debug build.

- [ ] Implement, suite, commit `feat(db): open and migrate off the main thread`.

## Task 11: Post-migration VACUUM and background run compaction

**Why:** After `28.sqm` the file keeps its old size, because the V2 pages sit on the freelist. Migrated unknowns are single rows (~1.1 MB per ride, against ~0.8 MB for rides recorded with runs). Neither job may run inside a migration.

**Files:** new `data/history/HistoryStorageMaintenance.kt`, `RideHistoryCompact.sq` (compaction queries), a small `HistoryMaintenanceJournal` table **only if** it is needed. Prefer deriving "work left" from data (`freelist_count`, plus series with adjacent single unknown rows) so no migration is needed.

**Rules:**
- **Compaction:**
  - per ride, in one short transaction: read its unknown rows in PK order, apply `collapseUnknownRuns` with the ride's markers (Task 3), and rewrite each chain as one run row (delete the members, then update or insert the head);
  - idempotent: a compacted ride yields no chains of single rows;
  - resumable: each ride commits on its own, and a kill loses at most the current ride's work, with nothing half-written;
  - it never runs while recording, and it yields between rides.
- **VACUUM** runs only when every one of these holds:
  1. compaction is done;
  2. not recording;
  3. `freelist_count × page_size ≥ 32 MiB`;
  4. free space ≥ 2 × (`page_count − freelist_count`) × `page_size`. `VACUUM` writes a full copy to a temp file and then a journal or WAL of the same size, so it needs about twice the **final** size, which is small.

  It runs on IO and outside any transaction. A failure is logged and retried at the next app start.
- The pure decision `maintenancePlan(recording, freelistBytes, liveBytes, freeBytes, ridesLeft): List<Step>` holds all of the above.

**Tests:**
- [ ] `compactionMatchesFreshRecording`: write the T4-shaped stream to ride A via migration (single unknown rows) and to ride B via `append` (runs). After compaction, A's V3 rows equal B's row for row (except `seriesId`), and the Task 6 readers are equal.
- [ ] `compactionIsIdempotentAndResumable`: run it twice → no change the second time. Kill after ride 1 of 3 (throw inside ride 2's transaction) → ride 1 compacted, ride 2 untouched; a re-run completes.
- [ ] `incoherentMarkerBetweenSingletonsKeepsThemApart`: two unknowns 300 ms apart with a CONNECTED marker between → not merged.
- [ ] `vacuumPlanRequiresSpaceAndIdle`: `(recording = true, …)` → no vacuum; freelist 31 MiB → none; free space < 2 × live → none; all satisfied → `[Vacuum]` after `[Compact]` steps are done.

- [ ] Implement, suite, commit `feat(history): compact migrated runs and reclaim space`.

## Task 12: Whole-branch review, real-DB re-run, merge

- [ ] Whole-branch review (superpowers:requesting-code-review), focused on:
  - the KDoc retractions (Tasks 3, 6, 7);
  - that every `.sq`/`.sqm` statement is 3.19-safe;
  - that no V2 sample query remains.
- [ ] Re-run the Task 9 real-DB gate on the final tip, through compaction and VACUUM on the copy. Record in the ledger the final bytes per ride and the migration time on the PC.
- [ ] Rider install of the release APK: the progress screen shows (or the migration is instant), then old rides open with the same totals, the same «частичные данные» marks and the same exports as before, and the data size drops in Android settings.
- [ ] Merge `feat/compact-history` into `main`, bump the version.

## Not in this plan (deliberately)
- Raising the VESC poll rate or the 8 Hz gate cap (a later Part, research §3.3 R2), and rate-aware reader budgets.
- The export format (finished in 0.7.59).
- Chunked BLOB storage (research S3: ~0.21 MB per ride). Revisit only if ~0.8 MB is not enough at 20 Hz.
- Compressing `HistoryBucketV2` and `HistoryGpsV2`, which are negligible next to samples (research §1.3).

## Open questions for the rider
1. **How big is the data now?** Android Settings → Apps → Volty → Storage → «Данные». This one number, read today, checks the ~20 MB per 15-minute ride estimate against reality.
2. **Getting the DB off the phone:** nothing in the app does this today. Is a debug button «Поделиться базой данных» in Settings acceptable (Task 1)? The file contains GPS tracks of every ride, and it stays on the developer's PC and out of the repo. If the build is installed through Android Studio as a debug build, `adb exec-out run-as ru.sodovaya.volty cat databases/volty.db` works too; which build is on the phone?
3. **How many rides are stored, and how long are they?** Roughly: count and longest. This decides whether the one-time conversion takes seconds or minutes (estimate: 2–4 min for 200 × 15 min). Is a one-time «Обновляем историю…» screen on first launch acceptable?
4. **Free space** on the phone. Conversion needs ~5 % of the DB size extra, and the later cleanup needs ~2× the final (small) size.
5. **Unknown runs:** a stretch where a value is unknown is stored as "unknown from t1 to t2, N frames" instead of N separate rows. Every screen and export shows the same thing. Only the exact tick of each unknown frame inside the stretch is dropped. Is that acceptable? If not, the layout without runs costs ~1.1–1.7 MB per ride instead of ~0.8.

