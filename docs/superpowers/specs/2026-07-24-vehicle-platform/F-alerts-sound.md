# Part F — Safety alerts with escalating tones + vibration

| Field | Value |
|---|---|
| Part | F |
| Depends on | A, B |
| Blocks | — |
| Modality | **tones/beepers (escalating near threshold) + vibration. No TTS.** (user choice) |

> Read `00-overview.md`, `A-foundation.md`, `B-vesc-dashboard.md` first. Today's
> `AlertEngine` fires **one-shot** notifications on battery thresholds. Part F
> adds **motion** alerts and — critically — a **continuous, escalating audible
> alarm** for the safety numbers a rider cannot look down to read: duty/ШИМ
> (wheels), speed, and temperature. This is the feature EUC riders depend on.

## 1. Scope
**In:** motion alert kinds + per-vehicle thresholds; a new **continuous
audible-alarm** mechanism (escalating tone + vibration) distinct from one-shot
notifications; wiring it through the foreground service so it sounds with the
screen off; sensible defaults per vehicle type.
**Out:** TTS; battery alert logic (already exists, unchanged); the specific tone
sample design (tuned during implementation with the user).

## 2. Two kinds of alert, deliberately different
- **One-shot notifications** (existing `Notifier.showAlert`, keep as-is): a
  discrete event crossed a line — controller fault, temperature-high, SOC low.
  Debounced, arm/recover, posts a notification.
- **Continuous audible alarm** (new): a *live, graded* signal that a safety
  metric is approaching/over its limit — the duty/ШИМ beeper. It plays **while
  the condition holds**, escalating as the value climbs, and stops when it
  recovers. This is not a notification; it is a tone loop + haptics.

## 3. Motion alert kinds & thresholds
Extend `AlertKind`: `DUTY_WARN`, `DUTY_HIGH`, `SPEED_HIGH`, `MOTOR_TEMP_HIGH`,
`ESC_TEMP_HIGH`, `CONTROLLER_FAULT`. Extend `AlertConfig` (per vehicle, chemistry-
style defaults):
- `dutyWarnPercent` (default 80), `dutyHighPercent` (default 90) — **the ШИМ alarm**
- `speedLimitKmh` (nullable; user sets per vehicle)
- `motorTempHighC` (default 90), `escTempHighC` (default 90)
- controller fault → always CRITICAL one-shot
Availability-gated: a controller with **no duty** (Kelly KLS, `H §7`) must not arm
`DUTY_*`; a vehicle with no motion source keeps only battery alerts.

## 4. The audible alarm (`domain/usecase` + `notification` expect/actual)
Pure core + platform sound:
- **`AlarmController`** (commonMain, pure, tested): consumes the live motion
  sample + config each tick and outputs a graded **`alarmLevel`** — e.g. a
  0..1 urgency plus a discrete band `{ NONE, WARN, CRITICAL }`. Duty drives it
  primarily: `NONE` below `dutyWarn`, ramping `WARN` across `[dutyWarn,
  dutyHigh)`, `CRITICAL` at/above `dutyHigh`. Speed-over-limit and temp-high feed
  the same level (max of contributors). Deterministic — unit-tested.
- **`AudibleAlarm`** (expect/actual): `fun update(level: AlarmLevel)`. Android
  actual uses `AudioTrack`/`ToneGenerator` for an escalating tone (rising
  pitch/repetition-rate with urgency) and `Vibrator` for a parallel haptic
  pattern; both individually toggleable (user picked tones **and** vibration).
  Idempotent per level; silent at `NONE`.
- Modality prefs: `alarm_tone_enabled`, `alarm_vibration_enabled`
  (default both on), plus a master `alarm_enabled`.

## 5. Foreground-service integration (must sound backgrounded)
The alarm loop runs in the existing `MonitoringService` (it already keeps the BLE
link alive with the screen off):
- Subscribe to `activeMotion` + `activeVehicle`; drive `AlarmController` →
  `AudibleAlarm.update(level)` on each sample.
- **Audio focus:** for a *safety* alarm, play over other audio — use the alarm
  usage/stream and request focus that does not permanently pause music (duck, or
  play on the alarm channel so music continues). Never lose the alarm to focus
  loss; re-request on transient loss.
- **Doze/App-Standby:** the FGS keeps the loop alive; the alarm must survive the
  same background conditions the BLE link already handles (`onAppResumed`).
- Notification channels: keep the CRITICAL channel (sound+vibration) for one-shot
  events; the continuous alarm is its own audio path, not a notification.

## 6. Dashboard coupling (nice-to-have)
The Ride dashboard's duty gauge color should track the same `alarmLevel` (green →
amber at `dutyWarn` → red at `dutyHigh`), so the visual and the audible warning
agree. Reads `AlarmController`'s level — no duplicate thresholds.

## 7. Defaults by vehicle shape
- **EUC (has duty):** duty alarm is the headline; defaults 80/90 %; temp
  secondary.
- **Scooter (VESC/Kelly):** temperature (ESC/motor) + optional speed limit are the
  headline; duty (VESC has it) is a secondary alarm; Kelly has no duty.
- The composer (G) exposes these per vehicle with the above defaults.

## 8. Testing
- `AlarmControllerTest` (pure) — duty ramps NONE→WARN→CRITICAL across thresholds;
  temp/speed contribute via max; recovery clears; duty-unavailable vehicle never
  raises a duty level; hysteresis so it doesn't chatter at the boundary.
- `AlertEngine` (extend) — motion one-shots (fault, temp-high) fire with debounce
  and arm/recover, gated on availability.
- `AudibleAlarm` is platform (manual test with screen off, over music, phone in
  pocket); the *level computation* is fully covered by `AlarmControllerTest`.

## 9. Open questions
1. **Tone design** — pitch/rate curve for WARN vs CRITICAL; tune with the user
   (EUC riders have strong expectations from wheel beepers / WheelLog).
2. **Audio-focus policy** — duck music vs play alongside on the alarm stream.
   Safety argues for "always audible"; confirm the exact Android usage/flags.
3. **Default thresholds** — 80/90 % duty, 90 °C — confirm per the user's wheels
   and scooter.
4. **Speed alarm** — off by default (rider-set limit), or a soft chime at a
   configurable speed? Confirm.

---

## 10. §7 SUPERSEDED — every alert is offered to every vehicle (2026-07-26)

**Decision (product owner):** *"алерты могут быть для всех сразу, каждый сам
выберет что он хочет, втч с настройками."*

§7 above segmented the alerts by vehicle shape — duty as "the EUC headline",
temperature as "the scooter headline". That framing came from the opening
conversation and it is wrong. A wheel rider may well want a motor-temperature
alarm; a rider on a powerful scooter has every reason to watch duty. Choosing
for them by vehicle type is a guess dressed as a default.

**What replaces it.** Separate two things §7 conflated:

- **Availability — a fact, not a preference.** An alert exists only if the data
  exists. Kelly reports no duty (`H §7`), so `DUTY_*` cannot arm on it. A vehicle
  with no motion source keeps battery alerts only. A controller with no motor
  temperature sensor cannot raise `MOTOR_TEMP_HIGH`. This gating stays exactly as
  §3 describes it and is **not** negotiable by settings — an unavailable alert
  cannot be switched on at all. It is still *shown*, greyed with its reason (see
  the UI bullets below); what is fixed is that no setting can arm it.
- **Preference — entirely the rider's.** Every *available* alert is offered on
  every vehicle, each independently switchable, each with its own editable
  threshold. No alert is withheld because of what kind of vehicle the app thinks
  this is.

**Defaults become a starting point, not a profile.** Ship one set of thresholds
applied uniformly wherever the metric is available. They are an opening position
the rider edits, not a decision keyed on vehicle shape.

**§3's temperature defaults are wrong and must change.** It specifies 90 °C for
**both** `motorTempHighC` and `escTempHighC`. Two separate errors:
- **Too low for a motor.** The product owner runs his own motor to **130 °C** by
  choice. A 90 °C default would nag him continuously from the first ride — and an
  alarm a rider learns to ignore is worse than no alarm, because it trains them
  to dismiss the one that matters.
- **One number for two different parts.** Motor windings and ESC FETs do not share
  a thermal limit; the ESC's headroom is much smaller. They need separate defaults,
  not the same constant written twice.

Pick the two numbers with the product owner before implementing (§9.3), and treat
"the rider raises it" as the expected case, not an edge case. `TempBands`
(`B §11`) already holds ESC 70/85 and motor 85/100 for dashboard colour — **decide
deliberately whether the alarm reuses those or takes its own**, and if they
diverge, say why in the code. Two sources of truth for "when is it hot" is a
defect waiting to happen; that risk is already recorded for Part C's `l_temp_fet_start`
question (`C §10`).

**Consequences for the UI (Part G2 owns the screen):**
- the alert list shows every available alert for this vehicle, each with its own
  toggle and threshold field;
- an alert the hardware cannot supply is shown **greyed out with the reason
  stated** — e.g. "Kelly controllers do not report duty", "no motor temperature
  sensor on this controller". Product owner's call (2026-07-26), and the better
  one: a greyed row with an explanation teaches the rider what their hardware
  does and does not measure, where an absent row silently leaves them wondering.
  The reason text is the point — a grey row with no explanation is the version
  that invites "why can't I turn this on?";
- the two modality switches (tone, vibration) and the master switch stay global
  as §4 describes; per-alert preference is about *whether it fires*, not *how*.

**Consequence for §9.3:** narrower but sharper — one set of numbers to confirm
rather than a per-shape profile, but the temperature pair must actually be
re-picked rather than carried over.

§7 stays above as the superseded reasoning; this section governs.

### 10.1 Threshold defaults — decided, not to be asked (2026-07-26)

Product owner: *"смысл спрашивать сразу три числа, ставь дефолт для нормальных
людей, но дай возможность менять их."* Correct — the previous section left three
numbers "to confirm with the owner", which quietly hands the decision back to the
user for something we can reason about ourselves. Decide, ship, make it editable.

**The defaults:**

| Alert | Default | Why |
|---|---|---|
| `dutyWarnPercent` | 80 % | unchanged from §3 — matches `DutyBands`' amber and wheel-rider convention |
| `dutyHighPercent` | 90 % | unchanged from §3 — `DutyBands`' red |
| `escTempHighC` | **90 °C** | FET derating typically begins ~85 °C; alarm just past it |
| `motorTempHighC` | **110 °C** | windings tolerate far more than FETs; 90 would nag continuously on ordinary hardware |
| `speedLimitKmh` | off | meaningless without a rider-chosen number |

**The `TempBands` question is resolved, not deferred.** The dashboard keeps its
own bands (ESC 70/85, motor 85/100, `B §11`) and the alarm keeps the numbers
above. They differ **on purpose** and are not two answers to one question:

- the dial's colour is a *glanceable* warning — it should turn amber and red
  early, while the rider still has cheap options;
- the alarm *interrupts* — it must come later, or it fires while the dial is
  merely amber and the rider learns to dismiss it.

So the dial goes red **before** the alarm sounds, by design. Anyone changing
either set must keep that ordering: `alarm ≥ band-red`, per metric. Write that
invariant next to the constants, and test it.

**On the owner's own 130 °C:** he runs his motor there by choice, so even 110 °C
will nag him — and that is the correct outcome for a default. He raises it once,
per vehicle, and the setting exists precisely for that. A default tuned to the
most tolerant rider in the room would be silent for everyone who needed it.

Part C's `l_temp_fet_start` question (`C §10`) is **separate and still open**:
that is about whether to adopt the *controller's own* configured limit once
`GET_MCCONF` can read it, which would make the ESC default hardware-specific
rather than a constant. Deciding it does not change anything above.

### 10.2 Up to three rider-defined levels per alert (2026-07-26)

Product owner: *"все алерты должны быть редактируемыми, что вкл\выкл, что по
лимитам. и вообще надо бы добавить возможность сделать до трех уровней алертов
на каждый пункт по выбору юзера."*

**This changes the model, not just the defaults.** §3 bakes the levels into the
field names — `dutyWarnPercent` + `dutyHighPercent` is two hard-coded steps for
duty and exactly one for each temperature. That shape cannot express "I want
three motor-temp steps" or "one duty step is enough for me".

**New shape.** Each alert kind carries an ordered list of **0..3 levels**; each
level is `{ threshold, enabled }`. Position implies escalation: level 1 is the
mildest, level 3 the most urgent. An empty list means the rider turned that alert
off entirely — which is the same thing as all levels disabled, and the UI should
not present two ways to say it.

**Constraints the implementation must enforce, not merely hope for:**
- **Monotonic.** `t1 ≤ t2 ≤ t3` for a rising metric (duty, temperature, speed).
  A rider who types 90/80/100 has expressed something contradictory; the editor
  must either reorder or refuse, and the engine must never be handed an unsorted
  list. Decide which at implementation and test it.
- **Availability still gates the whole kind** (§10): an alert the hardware cannot
  supply has no levels to edit, greyed with its reason.
- **Hysteresis is per level** (§8 already asks for it) — with three steps the
  chatter risk multiplies, and a boundary sitting between two levels must not
  oscillate between them either.

**The alarm must sound different per level, or the levels are decoration.** §4's
`AlarmLevel { NONE, WARN, CRITICAL }` is two audible states. Three rider-defined
steps need three distinguishable signals — most likely one tone whose repetition
rate and pitch step up, rather than three unrelated sounds. This lands in §9.1's
tone-design question, which now has a concrete requirement attached: **three
steps a rider can tell apart with the phone in a pocket**, not three numbers that
all sound the same.

**Dashboard coupling (§6) needs a rule.** With a variable number of levels, "amber
at warn, red at high" no longer has fixed referents. Simplest rule that stays
honest: the dial's own `DutyBands`/`TempBands` colours are **independent** of the
alert levels — §10.1 already established the dial warns early and the alarm
interrupts later, and that separation survives this change unchanged. Do not try
to drive the dial from a rider-defined list.

**Cost, stated honestly.** This is not a small change:
- `AlertConfig` stops being a flat row of scalars and becomes list-shaped, so the
  persistence layer needs a table or a serialised column — **a schema migration**,
  and this project's migration verifier has never run green locally (`B §12.1`).
- the settings UI gains add/remove/reorder rows per alert, where today it has
  fixed fields.
- `AlarmController` moves from comparing against two constants to walking a list.

Worth it — it is the difference between "the app's opinion with an escape hatch"
and "the rider's own thresholds" — but it should be planned as its own task inside
Part F rather than folded into another one, and the migration should land with the
Part G2 composer work that already has to touch this screen.

**Defaults under the new shape** (§10.1's numbers, expressed as levels):
- duty: two levels — 80 %, 90 %
- ESC temp: one level — 90 °C
- motor temp: one level — 110 °C
- speed: no levels until the rider adds one

The rider adds a third duty step, or a second motor step, or deletes any of them.

---

## 11. Corrections found at plan time (2026-07-26)

Three defects surfaced while planning the implementation against the actual
code. Resolved here; the plan
(`plans/2026-07-26-vehicle-platform-F-alerts-sound.md`) carries the detail.

**11.1 — §10.1's invariant is unsatisfiable as stated, and one of its claims is
factually wrong.** It says `dutyWarnPercent = 80` "matches `DutyBands`' amber";
`DutyBands.DEFAULT_WARN_PERCENT` is **75**. And the invariant `alarm ≥ band-red`
fails immediately for duty: the first alarm level (80) is below the dial's red
(90), so the alarm would sound while the dial is merely amber — the exact
failure the invariant was written to prevent.

Restated over the ends of the level list, which is what the reasoning requires:

```
first(levels).threshold >= band.warn
last(levels).threshold  >= band.red
```

Holds for every metric under §10.1's numbers (duty 80 ≥ 75, 90 ≥ 90; ESC
90 ≥ 70, 90 ≥ 85; motor 110 ≥ 85, 110 ≥ 100). Lives next to the constants as a
test, so editing either set of numbers fails loudly.

**11.2 — the alert editor ships in Part F, not G2.** §10.2 defers the migration
to "the Part G2 composer work". Read literally that ships an alarm nobody can
adjust — against the product owner's explicit *"все алерты должны быть
редактируемыми, что вкл\выкл, что по лимитам"*, and it would put a fixed 110 °C
motor alarm on a rider who runs to 130 °C by choice. That is §10's
train-them-to-ignore-it failure, shipped deliberately. **F owns the model, the
migration and the settings screen; G2 links to it.** G2's scope shrinks.

**11.3 — persistence is a child table**, `AlertLevelRow`, not a serialised
column: consistent with `PackRow`/`ControllerRow`, and the migration verifier can
check its shape, which it cannot do inside a JSON blob.

**Also decided rather than asked** (§9.2, §9.1): audio uses `USAGE_ALARM` with
`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` — music ducks and continues, and the alarm
never stops on focus loss. Tone design is not settled on paper: the settings
screen ships a **"проверить сигнал"** button that plays each level on the real
phone, and the curve is tuned from hearing it.
