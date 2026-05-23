# Plan 2 — Persistence + Connection UX smoke test

Manual verification. Requires a real Android device (Android 10+) and at least one supported BMS.

## Setup

```bash
cd C:\Users\sodovaya\Desktop\volty
./gradlew :composeApp:installDebug
```

## Cold-start flows

### 1. First launch with no saved vehicles

1. (If needed) clear app data: `adb shell pm clear com.volty.app`.
2. Launch the app.
3. **Expected:** Permissions screen if any BLE/notification permission missing. Grant them.
4. **Expected after grant:** Welcome screen with logo, two buttons "+ Add my battery" and "⚡ Quick connect (guest)".

### 2. Add new battery (saved-vehicle creation)

1. From Welcome, tap **+ Add my battery**.
2. **Expected:** Picker (add mode) — section "BMS detected" lists nearby BMS.
3. Power on a BMS within range. Within ~5 s it appears in the list.
4. Tap its row.
5. **Expected:** Spinner on the row, then VehicleEdit screen with prefilled BMS type + address (Plan 2 leaves prefill behavior as TODO — confirm the screen at minimum reaches the New battery screen even if BMS info isn't populated).
6. Fill name (e.g. "Stealth Board"). Pick chemistry Li-ion. Save.
7. **Expected:** Dashboard (DebugScreen) with live BMS data.

### 3. Persistence — survive app restart

1. After step 2, force-stop the app.
2. Re-launch.
3. **Expected:** Goes to Scanning (not Welcome — because saved vehicles count > 0).
4. With the BMS in range, after 5 s → AutoConnect ring counts down 3 s → Dashboard. With the BMS off → Picker (cold) showing your saved Stealth Board greyed (because it's not in range yet).
5. Confirm the saved vehicle remains visible across restarts.

### 4. Auto-connect single known

1. Power on exactly one saved BMS.
2. Launch the app.
3. **Expected:** Scanning → AutoConnect with 3-sec countdown → Dashboard (live data).

### 5. Picker with multiple knowns

1. Have two or more saved BMS in range.
2. Launch.
3. **Expected:** Scanning briefly → Picker (cold) with both vehicles in "My batteries · in range". Tap one → connects → Dashboard.

### 6. None found

1. Power off all BMS.
2. Launch with permissions granted and ≥1 saved.
3. **Expected:** Scanning runs 5 s → Picker (cold) with empty "My batteries" section but possibly "Other BMS nearby" (other people's BMS may show). "+ Add new battery" still visible.

### 7. Guest mode (no save)

1. From Welcome, tap **⚡ Quick connect (guest)**.
2. **Expected:** Picker (guest mode) showing "BMS detected". Tap a row → connects → Dashboard. Disconnect, force-stop, relaunch. The guest BMS should NOT be in saved list.

### 8. Edit vehicle

1. Currently no entry point to VehicleEdit from saved-vehicles list in Plan 2 — confirm at least that VehicleEdit can be reached via the "+ Add" path (step 2).
2. Plan 3 will add a settings/vehicle-list screen for editing.

### 9. SQLDelight stress

1. Create three vehicles via repeated "Add" flow.
2. Force-stop / relaunch repeatedly.
3. **Expected:** All three persist across restarts.

## Record

```
[ ] Cold start with no saves → Permissions → Welcome (test 1)
[ ] Add battery flow ends in Dashboard with persisted Vehicle (test 2)
[ ] Saved vehicles survive force-stop / restart (test 3)
[ ] Auto-connect for single known triggers 3-sec ring then Dashboard (test 4)
[ ] Picker shows multiple knowns correctly (test 5)
[ ] None-found state usable (test 6)
[ ] Guest mode connects without saving (test 7)
[ ] SQLDelight stable over multiple restarts (test 9)
```

## Known limitations

- VehicleEdit prefill from picked device is not wired in Plan 2 (left as TODO for Plan 3). Save still works but the BMS type/address fields may be blank.
- No entry point to edit/delete an existing vehicle from within the app yet — Plan 3 adds a Settings screen.
- Background monitoring + alerts come in Plan 4. Closing the app currently disconnects the BMS.
- Foreground service notification not yet present.
