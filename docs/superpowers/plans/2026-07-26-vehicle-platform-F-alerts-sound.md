# Plan — Part F: safety alerts with escalating tones + vibration

Spec: `docs/superpowers/specs/2026-07-24-vehicle-platform/F-alerts-sound.md`
(§10, §10.1, §10.2 govern; §7 is superseded.)

Branch: `feat/alerts-sound`, forked from `main` at Part C's merge.

---

## Why this part, now

`ControllerData.dutyPercent` has been live since Part B and VESC fills it from
`COMM_GET_VALUES`. The duty alarm has real data today — it does not wait on
Part D. This is also the only feature in the whole program that works while
nobody is looking at the screen, which is exactly when it matters.

## What exists today

- `AlertEngine` — one-shot battery notifications, debounce 3 s, arm/recover,
  keyed `(vehicleId, AlertKind)`. Untouched by this part except where noted.
- `AlertConfig` — flat nullable scalars on the vehicle row; `resolveAlertConfig`
  fills chemistry defaults.
- `DutyBands` (75/90) and `TempBands` (ESC 70/85, motor 85/100) — **dashboard
  colour only**.
- `MonitoringService` — foreground service, already keeps the BLE link alive
  with the screen off, already collects `activeData` + `activeVehicle`.
- `Notifier` (expect/actual) — `showLive`, `cancelLive`, `showAlert`.
- No CI, no `schemaOutputDirectory`, no committed schema snapshots, migrations
  at v6 (`1.sqm`…`5.sqm`).

---

## Pre-flight: three spec defects, resolved here rather than asked

### F1. `alarm ≥ band-red` cannot hold for duty as written

§10.1 states the invariant "alarm ≥ band-red, per metric" and in the same table
sets `dutyWarnPercent = 80` while claiming it "matches `DutyBands`' amber".
Both halves are wrong against the code:

- `DutyBands.DEFAULT_WARN_PERCENT` is **75**, not 80.
- duty's first alarm level (80) sits **below** `DutyBands`' red (90), so the
  alarm would sound while the dial is merely amber — the precise failure §10.1
  says the invariant exists to prevent.

**Resolution.** The invariant is restated over the *ends* of the level list,
which is what the reasoning actually requires and what holds for every metric:

```
first(levels).threshold >= band.warn      // the alarm never precedes the amber
last(levels).threshold  >= band.red       // the dial always reddens first
```

Checked against §10.1's numbers: duty 80 ≥ 75 ✓ and 90 ≥ 90 ✓; ESC 90 ≥ 70 ✓
and 90 ≥ 85 ✓; motor 110 ≥ 85 ✓ and 110 ≥ 100 ✓. Written next to the constants
and **tested** (Task 2), so a later edit to either set trips a red test rather
than silently inverting the design.

### F2. The editor ships in F, not G2

§10.2 closes with "the migration should land with the Part G2 composer work".
Taken literally, Part F ships an alarm whose thresholds cannot be changed —
directly against the product owner's "все алерты должны быть редактируемыми,
что вкл\выкл, что по лимитам". An unadjustable 110 °C motor alarm on a rider
who deliberately runs to 130 °C is the nagging-alarm failure §10 describes,
shipped on purpose.

**Resolution.** Part F owns the model, the migration, and the per-vehicle alert
**settings screen**. Part G2 links to that screen from the composer instead of
building it. G2's scope shrinks; nothing is duplicated.

### F3. Persistence shape

§10.2 leaves "a table or a serialised column" open. **A child table**,
`AlertLevelRow`, consistent with `PackRow`/`ControllerRow`: inspectable, and the
migration verifier (Task 1) can actually check its shape, which it cannot do
inside an opaque JSON blob.

### Also decided, not asked

- **Audio focus (§9.2).** `AudioAttributes` with `USAGE_ALARM` +
  `CONTENT_TYPE_SONIFICATION`, focus requested as
  `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` — music ducks and keeps playing. The
  alarm **never** stops on focus loss; loss is logged and the tone continues.
  Safety beats politeness, and a ducked podcast is not a real cost.
- **Tone design (§9.1).** Not answerable in the abstract and not worth a round
  trip: Task 7 ships a **"проверить сигнал"** button in the alert settings that
  plays each level on demand, so the owner hears all three on the actual phone
  and we tune from that.

---

## Global Constraints

1. **Non-vacuity is proven, not asserted.** Every task's tests must be shown to
   FAIL against the pre-change code (or against a deliberately reverted line),
   and the report must quote the failure. This project has shipped
   by-construction assertions repeatedly; a green suite is not evidence.
2. **`runTest` + unbounded delay loops wedge the build** rather than failing.
   Any test that starts a repeating timed loop must bound it — see the
   `@DelicateBmsRepositoryTestApi` precedent from Part C.
3. **Availability is a fact, not a preference** (§10). An alert whose data the
   hardware cannot supply must be impossible to arm — no setting, no default, no
   code path. It is still *shown*, greyed, **with its reason in words**.
4. **The engine never receives an unsorted level list.** Enforced in the domain
   type's `init`, not by convention.
5. **Dashboard bands stay independent of alert levels** (§10.2). Do not drive
   `DutyBands`/`TempBands` from rider config.
6. **No new hardcoded thresholds outside the two named objects** — `AlarmDefaults`
   (alerts) and `DutyBands`/`TempBands` (dashboard colour). Two sources of truth
   for "when is it hot" is the defect §10 warns about.
7. Russian UI strings follow the existing resource convention; Compose MP does
   **not** process Android backslash escapes.

---

## Tasks

### Task 1 — CI and a migration verifier that actually verifies

Standing debt: three specs require the migration verifier to run on CI; neither
exists. Migrations are at v6 and are checked only by repository tests, which
cannot see a `NOT NULL`, `DEFAULT` or type divergence.

- `sqldelight { … schemaOutputDirectory }` + `verifyMigrations.set(true)`.
- Generate and **commit** the schema snapshot(s) for the current version.
- `.github/workflows/ci.yml`: JDK 17, Gradle cache, running the test suite, the
  migration verification task, and `assembleDebug`.

**Proof of non-vacuity (mandatory).** Deliberately corrupt one column in
`5.sqm` (e.g. `INTEGER` → `TEXT`), show the verification task **fails**, restore
it, show it passes. Quote both outputs in the report. A verifier that passes
against a broken migration is worse than none.

### Task 2 — the level model (pure, commonMain)

- `AlertLevel(thresholdValue: Float, enabled: Boolean)`.
- `AlertRule(kind: MotionAlertKind, levels: List<AlertLevel>)` — `init` requires
  `levels.size <= 3` and non-decreasing thresholds. An empty list is "off", and
  is the *only* representation of off (§10.2 forbids two ways to say it).
- `MotionAlertKind`: `DUTY`, `SPEED`, `MOTOR_TEMP`, `ESC_TEMP`. Controller fault
  stays a one-shot with no levels (Task 6).
- `AlarmDefaults` (§10.2): duty 80/90; ESC temp 90; motor temp 110; speed empty.
- `sortedLevels(input)` — the editor's normaliser: a rider typing 90/80/100 gets
  80/90/100. Reorder, never refuse, never drop.
- The **F1 invariant test**: for each kind with a dashboard band, assert
  `first ≥ band.warn` and `last ≥ band.red` against the live `DutyBands`/
  `TempBands` constants — so editing either object fails this test.

### Task 3 — availability gating

`AlertAvailability.of(vehicle, controllerCapabilities)` → per kind, either
`Available` or `Unavailable(reason: String)` with rider-facing Russian text:

- no motion source at all → every motion kind unavailable, "у этого транспорта
  нет контроллера";
- controller reports no duty (Kelly, `H §7`) → `DUTY` unavailable;
- `!ControllerData.hasMotorTemp` → `MOTOR_TEMP` unavailable;
- `!hasEscTemp` → `ESC_TEMP` unavailable;
- `SpeedSource.NONE` → `SPEED` unavailable.

Tests must include the negative: an unavailable kind whose config *does* carry
enabled levels still produces no alarm and no notification.

### Task 4 — persistence + migration `6.sqm`

- `AlertLevelRow(vehicleId TEXT NOT NULL, kind TEXT NOT NULL, position INTEGER
  NOT NULL, threshold REAL NOT NULL, enabled INTEGER NOT NULL)`, PK
  `(vehicleId, kind, position)`, `ON DELETE CASCADE` semantics matching how
  `PackRow`/`ControllerRow` handle vehicle deletion (check what they actually
  do — match it, do not invent a third convention).
- Repository read/write folded into the existing vehicle upsert transaction.
- **A vehicle with no rows means "never configured" → defaults apply**, which is
  distinct from "the rider deleted every level" → off. Represent that difference
  explicitly; do not let a rider's deliberate silence read as a default.
- **Two hazards land here from Task 2's model** (found in its review):
  - **Sort on read.** `AlertRule.init` requires ascending thresholds. If the
    reader trusts stored `position` ordering and a row set's positions disagree
    with its thresholds, loading the vehicle throws `IllegalArgumentException` —
    a crash at startup, not a validation error. Map rows through
    `sortedLevels` on the way in.
  - **"Never configured" lives at the repository boundary, not in the model.**
    Represent it as absence of rows (or a nullable list returned by the
    repository) — never as a flag or nullable on `AlertRule`, which would give
    "off" a second representation and kill §10.2's single-representation rule.
- Migration test: a v6 database with existing vehicles opens at v7 and every
  vehicle reads back with defaults.
- **Regenerate the schema snapshot after adding `6.sqm`** — run
  `:composeApp:generateCommonMainVoltyDatabaseSchema` and commit the resulting
  `7.db`. Task 1 committed `1.db`…`6.db`; because `1.db` is present every
  migration replays on every verification run, so a missing `7.db` costs
  redundancy rather than coverage — but the chain should stay complete. See the
  comment in the `sqldelight` block of `composeApp/build.gradle.kts`.

### Task 5 — `AlarmController` (pure, commonMain)

Consumes `(ControllerData, resolved rules, availability)` per tick, emits
`AlarmState(level: Int 0..3, urgency: Float 0..1, contributors: List<...>)`.

- Level = max across contributing kinds. Urgency ramps within the active band so
  the tone can climb continuously between steps.
- **Hysteresis per level** (§10.2): a value must fall below
  `threshold - release` to drop a level. Release: duty 3 pp, temp 3 °C, speed
  2 km/h. A value parked exactly on a boundary must not oscillate — test with a
  sequence that crosses and recrosses.
- Disabled levels are skipped without shifting the ones above them.
- Deterministic and fully unit-tested; no clock, no coroutines.

### Task 6 — `AlertEngine`: motion one-shots

Extend the existing engine (do not fork it) with `CONTROLLER_FAULT` (always
CRITICAL) plus motion temp one-shots, reusing the existing debounce/arm/recover.
Gated on Task 3's availability. Fault text names the fault from
`ControllerData.faults`.

### Task 7 — `AudibleAlarm` (expect/actual) + prefs + the preview button

- `interface AudibleAlarm { fun update(state: AlarmState) }`, idempotent per
  level, silent at 0.
- Android: `AudioTrack` (USAGE_ALARM, per §9.2 above) generating an escalating
  tone — pitch and repetition rate step up per level so three steps are
  distinguishable **in a pocket**, not merely on paper; `Vibrator` pattern in
  parallel.
- Prefs on `AppPrefs`: `alarm_enabled`, `alarm_tone_enabled`,
  `alarm_vibration_enabled`, all default true.
- A `preview(level: Int)` entry point for the settings button.

### Task 8 — `MonitoringService` wiring

Drive `AlarmController` from `activeMotion` + `activeVehicle` inside the existing
FGS scope and push each state to `AudibleAlarm`. The alarm must stop on
disconnect and on service destroy — a tone that outlives the ride is the worst
possible bug here. Do **not** `sample()` the alarm path at 2 s like the live
notification: a duty spike must sound immediately.

### Task 9 — the alert settings screen (per vehicle)

- Every alert kind listed, always. Available ones get a switch and up to three
  level rows (threshold field + per-level enable) with add/remove.
- Unavailable ones are **greyed with the reason spelled out** (§10, Task 3).
- Master switch + tone switch + vibration switch (global, §4).
- "Проверить сигнал" plays each level through `AudibleAlarm.preview`.
- Editor commits through Task 2's normaliser, so out-of-order input is sorted,
  not rejected.

---

## Out of scope

TTS; battery alert logic; the composer screen itself (G2 links here); Begode and
Kelly availability wiring beyond the capability check (D and H own their own
`MotionSource` flags).
