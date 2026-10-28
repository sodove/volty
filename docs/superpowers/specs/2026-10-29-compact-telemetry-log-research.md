# Compact, higher-rate telemetry log: research

Date: 2026-10-29. Branch base: `main` @ d8a033be (0.7.58), DB at v27.
Status: research only. Nothing in the repo was changed.

## Кратко (для райдера)

- **Почему экспорт весит 18 МБ.** Сами показания занимают меньше 3 % файла. 73 % — это
  метаданные, которые повторяются в *каждой* строке: `dataQuality`, `rangeAxis`, `sourceLabel`,
  `unit`, `displayMultiplier` и т. п. Ещё 34 % файла — строки «нет значения» для шести рядов,
  которых у T4 вообще нет (ток/мощность/температура батареи, температура мотора, сборки).
  Одна запись стоит ~410 байт, а у logui одно значение — ~5,5 байта, то есть в 75 раз меньше.
- **База на телефоне устроена так же расточительно.** По нашей оценке, строка занимает ~418 байт
  на диске: в каждой строке и в индексе повторяются UUID поездки и длинный `sourceId`. Это около
  19,5 МБ на 15 минут T4.
- **Как это делает logui.** Пишет CSV: одна строка на тик, один общий timestamp, столбцы
  фиксированы, у каждого поля своя точность. Ваши логи: 30 полей, 5 Гц, ~0,7 МБ на 15 минут
  (в gzip ~0,16 МБ). Данные у logui локальные (CAN, 50 Гц), без Bluetooth.
- **Чаще писать T4 нельзя.** Volty уже сохраняет каждый кадр колеса: ~3,2 Гц на тип кадра, и
  фильтр 500 мс ничего не отбрасывает. Для VESC частота упирается в опрос (150 мс ≈ 6,7 Гц), а не
  в хранение.
- **Рекомендация.**
  1. Сразу и без изменения БД: новый компактный экспорт (столбцы на ряд + gzip). T4 за 15 минут:
     18 МБ → ~0,1 МБ. VESC на 20 Гц: ~0,5 МБ. Плюс экспорт в CSV, совместимый с VESC Tool.
  2. Потом: компактная таблица сэмплов в БД (миграция 27.sqm → 28.db), ~20 раз меньше на диске.
  3. Только после этого — поднимать частоту VESC до 10–20 Гц.

---

## 0. Method and trust

Everything marked *measured* was computed by scripts in the session scratchpad, run with
`python3 -I` against:

- the rider's export `volty-ride-84bdb496-…json`: 19,169,395 B, a 14.3-minute ride (856 s of
  samples), Begode "GotWay_59672" (T4);
- the rider's real logui CSVs in `vesc_tool_free_windows\logs\` (7 rides) and `log_178_…csv`.

Items marked *estimated* are labelled with the reason. The DB size is a reconstruction. No real
`volty.db` was available, so the `sourceId` strings were rebuilt from `NativeHistorySourcePlan.kt`
(76–115 chars). That figure is the weakest number in this document.

---

## 1. Where the bytes go today

### 1.1 Export (measured, real T4 ride)

| record type | count | bytes | avg B/record | share |
|---|---:|---:|---:|---:|
| `sample` | 46,782 | 18,606,339 | 398 | 97.1 % |
| `summary` (60 s buckets) | 255 | 300,838 | 1,180 | 1.6 % |
| `gps` | 841 (~1 Hz) | 260,521 | 310 | 1.4 % |
| `event` | 4 | 762 | 190 | 0 % |

Key names alone are 9.93 MB (52 %) and values are 9.15 MB (48 %). Per key (key + value, share of
the records' bytes):

| key | share | nature |
|---|---:|---|
| `dataQuality` | 11.5 % | constant for the whole file |
| `rangeAxis` | 8.5 % | constant for the whole file |
| `sourceLabel` | 7.1 % | constant per source (already in the header) |
| `consumptionMultiplier` | 7.1 % | constant per metric (registry) |
| `epochMillis` | 7.0 % | per sample |
| `elapsedMillis` | 6.5 % | per sample |
| `metric` | 6.4 % | constant per series |
| `sourceKind` | 6.3 % | constant per source (already in the header) |
| `unknownReason` | 5.9 % | mostly `null` |
| `displayMultiplier` | 5.9 % | constant per metric |
| `clockGeneration` | 5.2 % | constant unless the clock changes |
| `source` | 4.9 % | constant per series |
| **`value`** | **4.9 %** | **the measurement** |
| `type` | 4.0 % | constant per record kind |
| `unit` | 3.6 % | constant per metric |
| `cell` | 2.9 % | constant per series |

- **73 % of the file is per-series or per-file constants repeated on every row.** The reading
  itself, without its key, is 562 KB (2.9 %).
- **Float noise.** Readings are float32 widened to double and printed in full:
  `-6.960000038146973` where the wire said `-6.96`. Of 30,247 known values, 21,500 print as 17–21
  characters. 2,328 are not exactly float32: derived values such as the SoC estimate and power.
- **Dead series.** 6 of the 17 series never held a value in this ride. Every frame still wrote an
  explicit `UNAVAILABLE`/`PARTIAL_SOURCE` row: battery `current_a`, `power_w`,
  `battery_temperature_c`, assembly `voltage_v` and `battery_temperature_c`, and controller
  `motor_temperature_c`. That is 16,510 rows (35 %), or **6.6 MB (34 %)**. The cause is
  `HistoryDetailGate.kt:19` (`if (sample.value == null) return flush() + sample`): unknowns bypass
  the 500 ms gate by design, so each frame's unknown becomes a row.
- **Summaries embed two full sample objects** (`minimum`/`maximum`), each carrying all the
  constants. That is why they cost 1,180 B each.
- The existing CSV export is not much better. Emulating `csvRow` gives ~10.95 MB (gzip 0.50 MB):
  26 quoted columns per row, with `sourceLabel`, `unit` and the multipliers repeated.
- The existing JSON gzips from 19.17 MB to 0.53 MB (36×), and with xz to 0.35 MB. Nearly all of
  the file is redundancy.

### 1.2 Rows per second (measured)

All 17 series run at 3.21–3.22 rows/s (2,748–2,753 rows over 856 s). The inter-sample gap per
series is p10/p50/p90 = 279/314/323 ms. **Per 500 ms gate window there are only ever 1 or 2
samples** (673 windows with 1, 1,040 with 2, one with 3). The gate (max 4 per window) never
trims anything on a T4.

The controller source shows 8,253 distinct ticks carrying 2 or 4 metrics each. These are several
Begode frame types, each arriving at ~3.2 Hz, about 9.6 frames/s in total. Every decoded frame is
already recorded. `historySessionTelemetryCapture` feeds every observation into
`HistoryRecordingProjection`, and nothing upstream throttles it. **The T4 history rate is the
wheel's own rate. It cannot go higher.**

For comparison, from source:

- VESC direct: `VescProtocol.pollIntervalMs = 150` → ~6.7 Hz. That is ≤ 4 per 500 ms window, so
  the gate does not trim it either.
- Gateway: `VescGatewayProtocol.DEFAULT_POLL_INTERVAL_MS = 50`, but the cycle includes several
  requests.
- ANT BMS: `pollIntervalMs = 500` → 2 Hz.

The gate only starts trimming above 8 Hz per metric.

### 1.3 DB on-disk cost per sample (estimated)

Schema (`RideHistorySample.sq`):

- `HistorySampleV2(rideId TEXT, sequence, sourceId TEXT, metricId TEXT, cellIndex, epochMillis,
  elapsedMillis, clockGeneration, reading REAL, unknownReason TEXT, PK(rideId, sequence))`
- index `(rideId, sourceId, metricId, cellIndex, elapsedMillis, sequence)`

Each row stores the 36-char ride UUID, a 76–115-char `sourceId`, the metric id and the unknown
reason as text. The index repeats the ride UUID, `sourceId` and metric again.

The real ride's 46,782 samples were loaded into SQLite 3.49 (page 4096, then `VACUUM`, which gives
the best-case packing) with the reconstructed `sourceId`s:

| layout | bytes | B/sample | T4 15 min |
|---|---:|---:|---:|
| current table + index | 19,537,920 | **418** (table 242 + index 175) | **~19.5 MB** |
| current, dead series not written | 13,266,944 | — | 13.3 MB |
| A: normalized `seriesNo INTEGER`, narrow row, `WITHOUT ROWID` | 991,232 | 21.2 | 1.0 MB |
| B: per-series 10 s chunk BLOBs (varint Δt, zigzag fixed-point Δv) | 212,992 | 4.6 | 0.21 MB |

Two caveats. On a device the index fills worse than after `VACUUM`, because index order is
per-series while inserts are in time order. And `sourceId` length moves the result by about
±20 %. **The phone's DB costs about as much as the bloated export.** At 20 Hz it would grow by
roughly 100 KB/s (§3.3).

`HistoryBucketV2` (255 rows per ride) and `HistoryGpsV2` (~1 row/s) are negligible next to this.

---

## 2. How logui and VESC Express do it (read, then measured)

### 2.1 Sources read

- `vesc_pkg-main\vesc_pkg\logui\logger.lisp`
  - L39–66: the fields, local list.
  - L71–81: the CAN template.
  - L111–133: BMS fields, including per-cell.
  - Field tuple format `(key name unit precision isRel isTime value-fn)`, L35 and L149–154. The
    default precision is 2.
  - `log-thd`, L177–187: every `1/rate` s it evaluates **every** field and sends them all in one
    `log-send-f32`.
  - `start-log` L207–213: `log-start id fieldNum rate appendTime appendGnss`.
  - The default rate is 10 Hz (`restore-settings` L304). The rider's logs run at 5 Hz.
- `vesc_express-main\main\log.c`
  - L78–82: header `key:name:unit:precision:isRel:isTime` joined by `;`.
  - L151–163: one file per log, `log_can/log_NNN_YY-MM-DD_hh-mm-ss.csv`.
  - L241–243: values printed with `"%.*f"` at the field's precision.
  - L251: timestamp `t_day = (float)utils_ms_today()/1000` printed `%.3f`. That is time of day in
    float32. At ~72,000 s float32 resolves ~7.8 ms, so the third decimal is not real, and the
    value wraps at midnight. The date lives only in the file name.
  - L257–289: GNSS columns are written only when a new fix arrived; otherwise the cell is empty.
  - L298–301: `fsync` every 2 s.
  - L304–313: the task paces itself to `m_rate_hz`.
  - L548–563: `COMM_LOG_DATA_F32` sets `value` and `updated` for consecutive fields.
  - This checkout also snapshots values under a mutex, writes non-updated fields as empty and skips
    rows where nothing was updated (L222–239). Those lines look like a local patch. There is no git
    metadata to check them against upstream.
- `bldc\applications\appconf_default.h:67,70`: CAN status rate 1 = 50 Hz, rate 2 = 5 Hz by default.
  logui reads the cached CAN status, not a request/reply.
- `bldc\comm\commands.c:384ff` (`COMM_GET_VALUES`): the wire is fixed-point (temp ×10,
  currents ×100, duty ×1000, voltage ×10, rpm ×1, Ah/Wh ×1e4). Motor and input currents are
  `read_reset_avg_*`, averaged since the last request, so a slower poll still integrates current
  correctly.
- `vesc_tool-master\pages\pageloganalysis.cpp:1287–1360` (`openLog`): the VESC Tool reader.
  - It accepts exactly this CSV.
  - An empty cell **repeats the previous value** (L1338–1343).
  - Rows with the wrong column count are dropped.
  - `pageloganalysis.h:150–172`: special keys `t_day`, `gnss_lat`, `gnss_lon`, `gnss_alt`,
    `gnss_h_acc`, `trip_vesc`, `cnt_wh`, … drive the time axis, the map and the trip stats.

### 2.2 Format summary

- Format: CSV with `;` separators.
- Row shape: one row per tick with one shared timestamp. The column set is fixed when the log
  starts.
- Encoding: each column has its own decimal precision. There are no keys, units or source names
  per row; they appear once, in the header.
- Unknowns: there is no notion of unknown. A stale CAN value is re-logged every tick, because the
  lisp re-sends every field every tick. Only GNSS can be empty.
- Timing: a fixed-rate grid on the device itself. No radio sits between the sensor and the log.

### 2.3 Measured on the rider's logs

| log | rows | duration | rate | B/row | per 15 min raw | per 15 min gzip |
|---|---:|---:|---:|---:|---:|---:|
| log_046 | 4,329 | 890 s | 4.86 Hz | 166 | 0.73 MB | 0.18 MB |
| log_139 | 5,241 | 1,102 s | 4.76 Hz | 164 | 0.70 MB | 0.16 MB |
| log_142 | 4,887 | 1,006 s | 4.86 Hz | 165 | 0.72 MB | 0.17 MB |
| log_160 | 4,088 | 855 s | 4.78 Hz | 164 | 0.71 MB | 0.16 MB |
| log_178 | 35,736 | 7,407 s | 4.82 Hz | 163 | 0.71 MB | 0.09 MB (mostly parked) |

- All logs have 30 fields: 16 local, 7 CAN (`V117 …`), `t_day`, `t_day_pos` and 5 GNSS. The step
  is 200 ms at the median.
- That is ~46 KB/min and **~5.5 B per value**, against Volty's ~410 B per value: a factor of ~75.
- In log_139 the 6 GNSS columns are empty in 60 % of rows; nothing else is ever empty.

logui logs ~150 values/s (30 × 5) against Volty's 55 rows/s (17 × 3.2). It writes 2.7× more
values in a file about 27× smaller.

---

## 3. Options for Volty

### 3.1 Workload assumptions for the estimates

| workload | series × rate | samples per 15 min |
|---|---|---:|
| T4 (measured) | 17 series × 3.2 Hz, 6 of them dead | 46,782 (30,268 real) |
| VESC today | ~12 controller × 6.7 Hz + ANT 29 series (9 + 20 cells) × 2 Hz | ~124 k |
| VESC at 20 Hz, gate kept | controller capped at 8 Hz | ~139 k |
| VESC at 20 Hz, every frame | 12 × 20 Hz + ANT 29 × 2 Hz | ~268 k |

The VESC series counts are estimates taken from `TelemetryMetric`. VESC compressed sizes below
scale T4's measured per-real-sample cost, so they are **upper bounds**: denser sampling
compresses better. Cross-check: logui at ~5.5 B/value × 268 k ≈ 1.5 MB raw, which matches the CSV
row.

### 3.2 Export formats (no DB change)

All sizes were measured on the real T4 ride. The VESC 20 Hz column is extrapolated.

| # | format | T4 raw | T4 gzip | VESC 20 Hz raw / gzip | lossless? |
|---|---|---:|---:|---:|---|
| E0 | current JSON v1 | 19.17 MB | 0.53 MB | 57 MB / 1.6 MB (gated); 110 MB / 3 MB (ungated) | yes |
| E0′ | current CSV | ~10.95 MB | 0.50 MB | — | yes |
| E1 | JSON-lines, short keys, float32-shortest values | 3.09 MB | 0.26 MB | ~18–27 MB / 1.5–2.3 MB | yes* |
| E2 | **columnar JSON**: one object per series (`source`, `metric`, `unit` once) with `dt[]` and `v[]` arrays, float32-shortest | 0.51 MB | 0.095 MB | ~4.5 MB / 0.85 MB | yes* |
| E2′ | E2 + per-metric fixed precision + dead series collapsed to one `{unknown, from, to}` | 0.29 MB | 0.059 MB | ~2.5 MB / 0.52 MB | precision-bound |
| E3 | wide CSV per source, row per tick (logui shape), float32-shortest | 0.40 MB | 0.13 MB | — | yes* |
| E3′ | E3, fixed precision, dead dropped | 0.30 MB | 0.090 MB | ~2.7 MB / 0.8 MB | precision-bound |
| E4 | one wide CSV over all sources, row per distinct tick | 0.34 MB | 0.094 MB | — | precision-bound |
| E5 | fixed 10 Hz grid with hold-last (exactly logui's model) | 0.60 MB | 0.078 MB | — | **no**: fabricates samples |
| E6 | binary columnar: varint Δt + zigzag fixed-point Δv | 0.098 MB | 0.053 MB | ~0.9 MB / 0.47 MB | precision-bound |
| E6′ | binary, varint Δt + raw float64 | 0.33 MB | 0.115 MB | ~2.9 MB / 1.0 MB | yes |

`*` Lossless relative to float32. Each value is printed in the shortest form that round-trips its
float32, or in full when the stored double is not a float32, as for the 2,328 derived values.

Fixed precision used here: V/A/km/h 0.01, °C 0.1, % 0.1, km 0.001, W 1. Fixed-point is only
lossless when the scale equals the wire's. VESC's wire is decimal fixed-point (§2.1), so it would
be lossless there. Begode speed is `raw × 0.036`, which is not decimal, so a per-series scale
factor would be needed.

Notes:

- **Compatibility.** There is **no in-app importer**: grep finds no history import. The consumers
  are the rider, external scripts and the encoder/`FileHistoryExportRepositoryTest` tests. JSON v1
  declares `schemaVersion: 1`, so a new format should be `schemaVersion: 2` or a new
  `HistoryExportFormat` entry, while v1 stays available.
- **gzip** is in the JDK (`java.util.zip.GZIPOutputStream`, already used for reading in
  `AndroidOfflineRegionPackageStore`). Wrapping E0 alone gives 36× with zero format change. The
  catch is that the rider's tools must accept `.json.gz`.
- **E3/E4 as a VESC Tool-compatible CSV.** Use the header `key:name:unit:precision:0:0`, a `t_day`
  column (the time axis) and `gnss_lat`/`gnss_lon` columns. The file then opens in VESC Tool's Log
  Analysis with a map: a direct answer to "logui does it better", in the rider's own tool. Risks:
  - VESC Tool **fills an empty cell with the previous value**, so an unknown reads as a stale
    reading. That conflicts with "never show a number it has not earned". It is acceptable only as
    an explicit "for VESC Tool" export, never as the archival format.
  - `t_day` is time of day. Rides that cross midnight need monotonic seconds instead (VESC Tool
    only needs the column to be a timestamp).
- **E5 (grid with hold-last)** is rejected as a storage or archival format, for the same honesty
  reason. It is only acceptable inside the VESC Tool export, where the reader does it anyway.
- **E2 is the best general format.** It is human-readable JSON, keeps every unknown and every
  original timestamp, needs no precision decisions, is ~38× smaller raw and ~200× smaller gzipped.
  It streams per series, which matches how `SqlDelightHistoryExportSource` can page by series key.

### 3.3 Storage rate and layout (DB change)

| # | change | T4 DB per 15 min | VESC 20 Hz DB per 15 min | migration | risk |
|---|---|---:|---:|---|---|
| S0 | today | ~19.5 MB | ~58 MB gated / ~112 MB every frame (≈100 KB/s ≈ 360 MB/h) | — | DB growth already dominates storage |
| S1 | write unknowns as runs (a row on transition plus a periodic heartbeat, or an `unknownUntil` end) instead of one per frame | ~13 MB | — | none (behaviour only) or 1 column | read side uses unknown rows as continuity evidence: `HistoryBrokenAccumulator`, `bridgePartialSourceArtefact`, `lastElapsedOfOtherSources`. Must be re-proved |
| S2 | **normalized narrow table**: `HistorySeriesV3(rideId, seriesNo, sourceId, metricId, cellIndex)` + `HistorySampleV3(rideNo/seriesNo, sequence, elapsedOffset, epochSkew, clockGeneration, reading, unknownCode)` `WITHOUT ROWID`, PK `(series, elapsed, sequence)` | ~1.0 MB (21 B/row) | ~5.7 MB | `27.sqm` (27.db → 28.db): create V3 tables, `INSERT … SELECT` with series numbering, drop the V2 table and index. This is a table rebuild; no `DROP COLUMN` is needed or possible (SQLite 3.19) | long one-off migration on a big DB, which needs progress UI or chunking; every query in `RideHistorySample.sq` is rewritten; `samplesForReceipt` (batch receipts by `sequence`) must keep working |
| S3 | chunked BLOBs per series per 10–60 s (varint Δt, zigzag fixed-point Δv) | ~0.21 MB (4.6 B/sample) | ~1.2 MB | as S2, plus encoding in Kotlin | SQL `MIN/MAX` (`rangeDetailStats`) and paging by elapsed time become decode-then-filter; the open chunk must be crash-safe against the receipt/fingerprint machinery; much more code. Not now |
| R1 | higher capture rate on T4 | — | — | — | **not possible**: every frame is already kept (§1.2) |
| R2 | VESC to 10–20 Hz: lower `VescProtocol.pollIntervalMs` (Part O cadence work), then raise or remove the 8 Hz gate cap (`HISTORY_DETAIL_BUCKET_MILLIS`) | — | see S0 or S2 | none | BLE throughput, which is Part O's job; read budgets below |

In S2, `epochSkew = epochMillis − (origin.epoch + elapsed − origin.elapsed)` is almost always 0.
SQLite stores integer 0 and 1 in zero payload bytes, so wall time stays exact for free. The same
holds for `clockGeneration` stored as a delta from the ride's.

**Read-time features at a higher rate.** Energy and summaries are not affected: the 60 s bucket
accumulator sees every sample *before* the detail gate (`HistoryRecordingProjection.kt:75–76`).
The detail readers have page budgets with `HISTORY_DETAIL_PAGE_SIZE = 20,000`:

| reader | budget | full ride at 3.2 Hz | at 6.7 Hz | at 20 Hz |
|---|---|---:|---:|---:|
| card max-duty peak (`HistoryCardStatsComponent`, `maxPages = 1`) | 20 k | 104 min | 50 min | **16.7 min**, then "approximate" |
| chart series (`maxReadPages = 8`) | 160 k | 14 h | 6.6 h | 2.2 h, then truncated |
| repair walk (`maxWalkPages = 10`) | 200 k | 17 h | 8.3 h | 2.8 h |
| totals: distance, moving time, battery used (`maxDetailPages = 16`) | 320 k | 28 h | 13 h | 4.4 h, then null |

`readHistoryDetail` materializes the whole series as `HistorySample` objects, for several series
at once. At 20 Hz × 2 h that is ~144 k objects per series on the heap. Before raising the rate,
the detail readers should either take a decimated read (min/max per pixel, as
`HistoryOverviewAccumulator` already does) or the budgets need a rate-aware bound. That part is
pure code and unit-testable.

---

## 4. Recommendation

The two problems are separable. The export fix is cheap and immediate. The storage fix is what
makes a higher rate affordable.

### Track 1: export format (no DB change; do first)

1. **Add `JSON` schema v2, columnar (E2).**
   - The header carries the sources and series once: source alias, kind, label, metric, cell,
     unit, multipliers.
   - Each series carries `t0` plus `dt[]` (ms, elapsed axis), `v[]` (float32-shortest, `null` for
     unknown) and a sparse `unknown` map of `index → reason`. A series that is unknown throughout
     collapses to one `{reason, from, to}`.
   - Epoch is derived as origin + elapsed. A row's real `epochMillis` is emitted only where its
     `clockGeneration` changes, as a sparse override, so nothing is lost.
   - Summaries keep their peaks as `{t, v}`, not as full sample objects.
   - Expected: **T4 15 min ≈ 0.5 MB raw**, VESC 20 Hz ≈ 4.5 MB raw.
2. **gzip it by default** (`.json.gz`, `application/gzip`), with a plain option. Expected:
   **T4 ≈ 0.1 MB, VESC 20 Hz ≈ 0.5–0.9 MB.** Keep v1 JSON and the current CSV behind the existing
   choice for one release; nothing in the app imports them.
3. **Add a "VESC Tool CSV" export** (E3/E4 shape with logui's header and the keys `t_day`,
   `gnss_lat`, `gnss_lon`, `kmh_vesc`, `trip_vesc`, `cnt_wh` where an honest mapping exists). It
   opens in VESC Tool Log Analysis with a map. State in the UI and in the spec that VESC Tool shows
   gaps as the last value. ~0.3 MB raw for T4.
4. Tests (pure, commonTest): golden round-trip E2 → decode → equal samples, including a
   **deliberately incoherent fixture** (unknown runs, a clock-generation change mid-series, a
   non-float32 derived value, a dead series) so the collapsing paths are exercised. Also assert
   byte counts on a fixed fixture.

### Track 2: storage rate and layout (DB change; after Track 1)

5. **S2 normalized narrow sample table** via `27.sqm` → `28.db`. A table rebuild, ~20× smaller
   on disk (T4 ≈ 1 MB per 15 min instead of ~19.5 MB). Run
   `verifyCommonMainVoltyDatabaseMigration`. Measure the migration time on a large DB copy before
   shipping, and chunk it if needed.
6. **S1 unknown runs**, folded into the S2 schema (`unknownCode` plus run semantics). Only do this
   after re-proving `HistoryBrokenAccumulator`, `bridgePartialSourceArtefact` and
   `lastElapsedOfOtherSources` against run-encoded unknowns. Fixture rule: a series whose only
   evidence of continuity is a run of unknowns.
7. **Then raise the VESC rate** (Part O): poll at 50–100 ms, raise or remove the 8 Hz gate, and
   make the detail readers rate-aware (decimated reads, or budgets in time rather than rows).
   Expected VESC 20 Hz: ~5.7 MB DB per 15 min with S2, export ≈ 0.5 MB gzip. T4 stays at its native
   ~3.2 Hz per frame type; nothing changes for it.
8. Defer S3 (chunked BLOBs). It saves another ~5× on disk, but rewrites the SQL-side stats and
   paging and complicates crash-safety. Revisit only if S2 is not enough.

### Before building: one rider observation

Ask the rider which tool they open exports in: VESC Tool, a spreadsheet, or their own scripts.
That decides whether step 3 or step 2 matters more, and whether `.json.gz` is acceptable to them.
