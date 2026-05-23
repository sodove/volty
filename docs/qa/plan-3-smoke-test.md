# Plan 3 — Dashboard / Cells / Graph / Settings / Theme smoke test

Manual verification. Requires real Android device + at least one supported BMS.

## Setup

```bash
cd C:\Users\sodovaya\Desktop\volty
./gradlew :composeApp:installDebug
```

## Checklist

### 1. Theme

- [ ] Settings → theme set to **System**. UI follows system dark/light.
- [ ] Switch to **Light**. UI uses Volty light palette (indigo primary, green tertiary).
- [ ] Switch to **Dark**. UI uses dark palette.
- [ ] Toggle **Dynamic color** OFF (Android 12+). UI reverts to Volty palette.
- [ ] Toggle ON, change Android wallpaper, relaunch app. Accent pulled from wallpaper.

### 2. Dashboard

Connect to a BMS (cold flow if needed). On Dashboard verify:

- [ ] Hero card shows SOC% as a large number with "X.X / Y.Y Ah remaining" on the right.
- [ ] Progress bar matches SOC.
- [ ] "≈ time at avg" updates as moving average accumulates samples.
- [ ] "%+.1f A now" updates live.
- [ ] Voltage / Power / Temperature / Cells cards populate.
- [ ] Sparkline (power · last 5 min) appears once a few samples are collected.
- [ ] Power card range bar shows min..peak with a current-marker.
- [ ] When charging (current > 0.05 A) hero card switches to green tertiary container.
- [ ] Vehicle pill at top shows active BMS name + connection status.

### 3. Cells detail

- [ ] Tap "Cells" tab → Cells screen.
- [ ] Max / Min / Δ summary cards populate with cell numbers.
- [ ] 3-column grid lists all cells with 3-decimal voltage + range bar.
- [ ] Min and Max rows are highlighted.
- [ ] For a 13s pack, all 13 cells visible without scrolling. For a 24s pack, at least 21 visible without scrolling (24 / 3 = 8 rows ≈ 192 dp).
- [ ] Back arrow returns to Dashboard.

### 4. Graph detail

- [ ] Tap "Graph" tab → Graph screen.
- [ ] Default metric is Power, window 5m.
- [ ] Switch metric (SOC / Power / Current / Voltage / Temp). Line redraws and stats update.
- [ ] Switch window (1m / 5m / 15m / 1h / All). Line redraws.
- [ ] Stats row at bottom shows avg / peak / min / used. For Power → Wh; for Current → Ah; others "—".
- [ ] Dashed grid lines + dashed now-marker render correctly.
- [ ] Back arrow returns to Dashboard.

### 5. Settings + Vehicle CRUD

- [ ] Settings → vehicles list shows saved vehicles.
- [ ] Tap a row → VehicleEdit(id) prefilled.
- [ ] Edit name, save → returns; saved name reflected.
- [ ] Tap "+ Add new battery" → VehicleEdit(null) → can save a new vehicle.
- [ ] Long-press a vehicle row (or tap "Delete" button on the row) → confirm dialog → vehicle removed.
- [ ] Scan timeout slider 3–15 s persists across restart.
- [ ] Auto-connect countdown slider 0–10 s persists across restart.

### 6. Vehicle bottom sheet

- [ ] Tap the pill at top of Dashboard → ModalBottomSheet opens with saved vehicles.
- [ ] Active vehicle highlighted in primaryContainer.
- [ ] Tap another → disconnects current, connects new.
- [ ] "+ Add battery" → opens VehicleEdit(null).
- [ ] "Disconnect" → drops BLE connection, goes to Scanning.

## Known limitations

- Editing a saved vehicle from Settings currently `replaceAll(Dashboard)` on save (not `pop()` back to Settings). Cancel / Delete pop correctly. Track as a follow-up UX polish.
- Foreground service / persistent notification / alerts arrive in Plan 4. Closing the app still disconnects.
- No history persistence yet — graph "All" window currently shows last 6 h of in-memory ring buffer.
