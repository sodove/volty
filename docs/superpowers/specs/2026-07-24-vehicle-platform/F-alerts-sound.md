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
  §3 describes it and is **not** negotiable by settings — an unavailable alert is
  hidden, not merely defaulted off.
- **Preference — entirely the rider's.** Every *available* alert is offered on
  every vehicle, each independently switchable, each with its own editable
  threshold. No alert is withheld because of what kind of vehicle the app thinks
  this is.

**Defaults become a starting point, not a profile.** Ship one set of thresholds
(§3's numbers: duty 80/90 %, temps 90 °C, speed off until set) applied uniformly
wherever the metric is available. They are an opening position the rider edits,
not a decision keyed on vehicle shape.

**Consequences for the UI (Part G2 owns the screen):**
- the alert list shows every available alert for this vehicle, each with its own
  toggle and threshold field;
- an alert the hardware cannot supply is **absent**, not greyed out — a disabled
  row invites the question "why can't I turn this on?", an absent one does not;
- the two modality switches (tone, vibration) and the master switch stay global
  as §4 describes; per-alert preference is about *whether it fires*, not *how*.

**Consequence for §9.3:** the open question "confirm default thresholds per the
user's wheels and scooter" is narrower now — one set of numbers to confirm, not
a per-shape profile.

§7 stays above as the superseded reasoning; this section governs.
