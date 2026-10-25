# Rider Alerts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** What the app tells the rider is trustworthy and visible: no alert from one corrupted frame, our own alerts shown in the dashboard's top banner, BMS faults notified without spam, notifications in Russian, a per-kind "notify" switch, and a fault/alert linger setting with useful steps.

**Why (field reports, 2026-10-25):**
- A friend's Begode raised "controller 130 °C" once, walking away from the wheel in a crowd — the edge of BLE range. Begode frames carry no checksum (header `55 AA` + tail `5A5A5A5A` only), and both `AlertEngine` and `AlarmController` fire on the FIRST sample over a threshold (`AlertEngine.debounce` is only a minimum interval between notifications). The notification stayed; the sound (level-driven) barely blipped.
- ANT BMS repeats its fault flags in every frame; the banner's counter adds +1 per frame (`RideDashboardComponent.observeFaults`, the `else ->` branch) — the rider saw "discharge disabled ×9992". Controller faults have the same bug.
- BMS faults never reach a notification (`AlertEngine` never reads `bmsFaults`).
- Notification titles/texts are hard-coded English ("Controller fault", "Battery low", …) in a Russian UI.
- The linger setting is a 0..300 s slider in 30 s steps labelled in whole minutes (30 s reads "0 мин").
- Speed and duty raise no notification by design (they are the live sound alarm's metrics); the rider wants them visible in the banner, held a little after they end, and an opt-in notification switch per kind.

**Repo / branch:** `C:\Users\sodovaya\git\volty-nearby`, branch `feat/rider-alerts` from `main` @e683631b. Suite: `.\gradlew.bat :composeApp:testDebugUnitTest`.

## Global Constraints
- Never write to a Begode's FFE1 (CLAUDE.md Global Constraint 5); read-only work.
- Compose is not unit-testable here: decisions live in pure functions/components with tests; composables render.
- Strings in both `values/strings.xml` and `values-ru/strings.xml`; no Android backslash escapes.
- Record retractions in KDoc rather than silently rewriting (e.g. the AlertEngine KDoc that says duty/speed never notify).
- Fixtures for absent/garbage data are deliberately incoherent.
- No `runTest` with an unbounded delayed loop.
- Migrations: newest is `24.sqm` / `databases/25.db`, so a schema change adds `25.sqm` and `databases/26.db` (`N.sqm` migrates `N.db` → `(N+1).db`).
- Defaults must reproduce today's behaviour exactly, except the fixes themselves.

## Decisions (rider-approved, 2026-10-25)
1. Temperature alerts (motor, controller/ESC, battery temperature) need the condition in **3 consecutive samples**, and a sample that jumps more than **20 °C** from the previous accepted sample is discarded as corrupt (it neither triggers nor resets the streak; the next sample is compared to the last accepted one; a run of 3 consecutive "jumps" that agree within 20 °C of each other is accepted as real, so a genuine fast rise is never locked out). Applies to both the notification path (`AlertEngine`) and the sound path (`AlarmController`). Speed and duty: unchanged.
2. Banner: fault counter counts **episodes**, not frames. A fault is considered cleared only after it has been absent for **2 s** continuously; reappearing within that window continues the same episode.
3. Banner shows our own alerts too — every alert kind, including speed and duty: one row per kind, label with the episode's peak value ("ШИМ 87 %", "Контроллер 85 °C", "Заряд 14 %"), occurrence count, severity colour; held after recovery for the linger setting.
4. Linger setting becomes a choice: «Пока активно» (0), 5 с, 10 с, 30 с, 1 мин, 2 мин, 5 мин. Default 1 мин (60 s). Stored seconds values that are not in the list map to the nearest option for display.
5. BMS faults notify like controller faults (CRITICAL), once per episode under rule 2's debounce.
6. Per-vehicle, per-kind "notify" switch on the vehicle alerts screen, for: motor temp, controller temp, speed, duty, controller faults, BMS faults. Defaults = today: temps and faults ON, speed and duty OFF. Speed/duty notifications, when switched on, are one per arming episode exactly like temperatures (`fireLevelled`). Battery alerts already have their own enable switches (they are notification-only) — unchanged.
7. Notifications in the app language: the domain emits a structured message (kind + values); text is produced from string resources (en + ru) at the platform/presentation edge.

---

### Task 1: Fault episodes and the linger choice
**Files:** `presentation/ride/RideDashboardComponent.kt` (observeFaults), `presentation/settings/SettingsScreen.kt` + `SettingsComponent.kt` (DashboardControls), `data/prefs/AppPrefs.kt` (default stays 60), strings.
- [ ] Failing tests in `RideDashboardComponentTest`: `aFaultRepeatedInEveryFrameCountsOnce` (same BMS fault in 100 consecutive samples → occurrences 1); `aFaultThatFlickersWithinTwoSecondsIsOneEpisode`; `aFaultGoneForTwoSecondsThenBackCountsTwice`; same for a controller fault.
- [ ] Implement: occurrences +1 only when a fault starts a new episode; clearance needs 2 s of absence by sample timestamps (no timers).
- [ ] Linger: replace the slider with the redesign's choice row (`SettingsChoiceRow`, see `SettingsChoiceStyle.kt` for dropdown selection) over the options in Decision 4; pure `fun lingerOptionFor(seconds: Int): Int` with tests (exact match, nearest, 0).
- [ ] Suite, commit `fix(ride): fault rows count episodes; linger choice with useful steps`.

### Task 2: Temperature confirmation gate
**Files:** create `domain/alert/TemperatureGate.kt`; modify `domain/usecase/AlertEngine.kt` (MOTOR_TEMP, ESC_TEMP and battery TEMPERATURE_WARN/HIGH paths), `domain/alert/AlarmController.kt` (temperature kinds).
**Interface:** `class TemperatureGate { fun accept(value: Float): Float? }` — returns the value to evaluate once confirmed per Decision 1, else null (hold the previous decision: neither trigger nor recover). One gate per (vehicle, kind) in each consumer; reset on disconnect/reset where the consumer resets.
- [ ] Failing tests: `oneCorruptFrameRaisesNothing` (40,40,130,40 °C → no notification, no alarm level); `aRealRiseConfirmsAfterThreeFrames` (40→85 over 3 samples with threshold 80 → fires on the 3rd above); `aGenuineFastJumpIsAcceptedOnceItHolds` (40, 75, 75, 75 with 20 °C jump rule → accepted on the 3rd agreeing sample); `nanIsRejected`; AlarmController equivalents.
- [ ] Implement; mutant check (gate bypassed → `oneCorruptFrameRaisesNothing` fails), record output.
- [ ] Suite, commit `fix(alerts): temperature alerts need three agreeing frames`.

### Task 3: Notification content, BMS faults, per-kind notify switches
**Files:** `domain/usecase/AlertEngine.kt`, `notification/Notifier.kt` (+ Android impl), new `domain/alert/AlertNotifyPrefs.kt` or a field on `Vehicle`; `VehicleRow.sq` + `25.sqm` + `databases/26.db`; `SqlDelightVehicleRepository`; `presentation/alerts/VehicleAlertsComponent.kt` + `VehicleAlertsScreen.kt`; strings.
- [ ] Model: `enum class NotifyKind { MOTOR_TEMP, ESC_TEMP, SPEED, DUTY, CONTROLLER_FAULT, BMS_FAULT }`; `Vehicle.notifyKinds: Set<NotifyKind>? = null` (null = defaults: everything except SPEED and DUTY); persisted as a nullable TEXT column of enum names.
- [ ] Structured messages: `AlertEngine` hands the notifier `AlertMessage(kind: AlertKind, vehicleName: String, values: …)` instead of title/text; the Android notifier formats it from resources (en + ru). Add `AlertKind.BMS_FAULT`. Every existing notification keeps its meaning; tests assert on the structured message, plus one formatting test per kind for both locales where the formatter is pure.
- [ ] BMS faults: one CRITICAL notification per episode (Decision 2's 2 s rule), only when BMS_FAULT is enabled. Test: ANT fault in 50 consecutive samples → one notification.
- [ ] Speed/duty: when enabled, `fireLevelled` like temperatures; when disabled (default), nothing — retraction note on the KDoc that said they never notify.
- [ ] Alerts screen: a "Уведомлять" switch per kind; component tests for toggling and persistence; migration verified with `.\gradlew.bat :composeApp:verifyCommonMainVoltyDatabaseMigration`.
- [ ] Suite, commit `feat(alerts): notify switch per kind, BMS fault notifications, localized notifications`.

### Task 4: Our alerts in the dashboard banner
**Files:** `domain/usecase/AlertEngine.kt` and/or `domain/alert/AlarmDriver.kt` (expose live state), `presentation/ride/RideDashboardComponent.kt` (merge into `faults`), the banner renderer, strings.
**Interface:** a `StateFlow<List<ActiveAlert>>` from the alert side, `data class ActiveAlert(val kind: AlertKind or MotionAlertKind, val peakValue: Float, val severity: AlertSeverity)` — one entry per kind currently engaged (after Task 2's gate). The banner treats each kind as an episode-counted row like faults (Decision 2/3) and holds it for the linger setting after it clears. Labels from resources ("ШИМ 87 %", "Скорость 52 км/ч" in the rider's units, "Контроллер 85 °C", "Мотор 90 °C", "Заряд 14 %", "Банка 4.25 В" …).
- [ ] Failing tests: a duty episode shows one row with its peak and disappears after the linger; two episodes → occurrences 2; a SoC-low alert row; linger 0 removes on recovery; no row while disconnected.
- [ ] Implement; renderer shows severity colour (reuse the fault banner's styles).
- [ ] Suite, commit `feat(ride): our own alerts in the dashboard banner`.
