# Plan 1 — Foundation smoke test

Manual verification step. Requires a real Android device (Android 10+ with Bluetooth) and at least one supported BMS within ~10 m.

## Setup

```bash
cd C:\Users\sodovaya\Desktop\volty
./gradlew :composeApp:installDebug
```

## Checklist

For each BMS available, run through:

1. Launch Volty.
2. Grant the BLE permissions when prompted (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` on Android 12+, or `ACCESS_FINE_LOCATION` on Android 10/11).
3. Power the BMS within range.
4. Tap **Scan BMS**. The BMS should appear in the list within 5–10 s, labeled with its detected `BmsType` and current RSSI.
5. Tap the **Connect** button on the BMS row. Status transitions Idle → Scanning → Connecting → Connected. Live data starts updating within 2–5 s (SOC, voltage, current, power, cells, temps).
6. Tap **Disconnect**. State returns to Disconnected, live values clear.

## Record

Fill in per BMS tested:

```
- JK BMS    (model ___________): scan [ ]  connect [ ]  live data [ ]
- JBD BMS   (model ___________): scan [ ]  connect [ ]  live data [ ]
- ANT BMS   (model ___________): scan [ ]  connect [ ]  live data [ ]  (or: deferred — no hardware)
- Daly BMS  (model ___________): scan [ ]  connect [ ]  live data [ ]  (or: deferred — no hardware)
```

## Known limitations of Plan 1

- No persistent storage — vehicles are stored only in memory and lost on app restart. (Plan 2 adds SQLDelight.)
- UI is intentionally bare-bones — full M3 Expressive theme + dashboard + cells/graph screens come in Plan 3.
- No foreground service yet — closing the app disconnects the BMS. (Plan 4 adds background monitoring.)
- No alerts. (Plan 4.)
