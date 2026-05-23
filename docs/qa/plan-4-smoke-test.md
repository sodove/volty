# Plan 4 — Foreground service + Alerts smoke test

Manual verification on real device.

## Setup

```bash
cd C:\Users\sodovaya\Desktop\volty
./gradlew :composeApp:installDebug
```

Grant notification permission when prompted (Android 13+).

## Foreground service

- [ ] Connect to a BMS via any flow (cold-start → Picker, "+ Add", etc.).
- [ ] After Dashboard appears, swipe down the notification shade. Persistent "Volty · {vehicle}" notification visible. Text format: `78% · 42.1 V · -4.2 A`.
- [ ] Background the app (Home button). Notification stays. Live values continue to update at most once every 2 s (visible by watching the text change).
- [ ] Tap "Disconnect" action in the notification. Connection drops; notification clears; app returns to Scanning state.
- [ ] Reconnect, then force-stop via Settings. Notification clears.

## Alerts — CRITICAL: cell voltage high

- [ ] In Settings → vehicle edit, lower the **Cell V high** threshold to a value below the current max cell voltage (e.g. 3.40 V if cells are around 3.45 V).
- [ ] Save and reconnect. Within ~3 s, a CRITICAL alert appears: "Cell voltage high · Max cell X.XX V on {name}".
- [ ] Same condition persists — no spam; only one alert fires.
- [ ] Raise the threshold back above the current max. After max < threshold − 0.05 V, the alert is "armed" again. Lower the threshold once more → new alert fires.

## Alerts — WARNING: SOC low

- [ ] Edit vehicle SOC low threshold to 99%.
- [ ] On a connected battery with SOC < 99%, an alert fires immediately with WARNING priority and vibrate.

## Alerts — INFO: charge complete

- [ ] Connect a battery that is on a charger near full (SOC ≥ 99.9% and current ≈ 0 A).
- [ ] Within 3 s, an INFO notification appears: "Charge complete · {name} reached 100%".
- [ ] If `chargeCompleteNotify = false` in Settings, no notification.

## Debounce + hysteresis

- [ ] Set a threshold that flickers around the current value. Verify alerts do not spam — at most once every 3 s and only after recovery.

## Channel separation

- [ ] On the Android Settings → Apps → Volty → Notifications screen, verify four channels are listed: "Live monitoring", "Critical alerts", "Warnings", "Info". The user can disable individual channels.

## Known limitations

- ETA text in the live notification is currently `null` (placeholder) — Plan 5+ can wire `MovingAverage` ETA into `LiveSummary.etaText`.
- AndroidNotifier uses `android.R.drawable.ic_dialog_info` and `ic_dialog_alert` as placeholders. Replace with a real Volty mono icon when one exists.
- POST_NOTIFICATIONS permission must be granted at runtime on Android 13+; the PermissionsGate handles this on cold-start, but if the user denies it, alerts will not appear — surface a recovery option in Settings.
