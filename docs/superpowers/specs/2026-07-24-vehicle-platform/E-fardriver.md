# Part E — FarDriver controller

| Field | Value |
|---|---|
| Part | E (last — hardware-gated, scheduled right after the rest) |
| Depends on | A, B, C (AWD reuses C's independent-links path) |
| Blocks | — |
| Hardware | a FarDriver controller (ND72/84/96 / SIAECOSYS family) |
| Confidence | **low** — no official protocol; community reverse-engineering + live capture required |

> Read `00-overview.md`, `A-foundation.md`, `B-vesc-dashboard.md`,
> `C-multi-controller.md` first. FarDriver is the highest-uncertainty protocol:
> it has no public spec and **no CAN** (so AWD is independent BLE links). It is
> deliberately last, but scheduled immediately after the rest so the "supports
> FarDriver" claim is real.

## 1. Scope
**In:** a `FarDriverProtocol` (`BmsProtocol + MotionSource`) decoding the BLE
telemetry into `ControllerData`; detection; AWD via independent links (`C §7`).
**Out:** controller writes/config (read-only); CAN (FarDriver has none).

## 2. Approach — reverse-engineer, then pin
There is no authoritative field table to cite (unlike VESC). The implementation
must:
1. **Capture** live BLE traffic from a real FarDriver + its app (nRF Connect /
   the app's logs / community dumps). Record advertised service + notify/write
   UUIDs and raw frames across throttle/idle/brake.
2. **Cross-reference** community reverse-engineering (FarDriver/Sabvoton
   integrations for ESPHome/Home-Assistant and hobbyist repos exist; treat as
   hints, verify against capture). FarDriver streams fixed-length register-style
   frames (commonly a header byte then a register id + payload); the app decodes
   registers for voltage, line/phase current, RPM, controller & motor
   temperature, throttle, gear, and a fault/status word.
3. **Pin** a decoder against the capture with a recorded fixture, exactly like
   `VescProtocolTest`.

## 3. `FarDriverProtocol` → `ControllerData` (expected fields)
Subject to capture confirmation:
- `inputVoltageV` (battery voltage register)
- `batteryCurrentA` (line current), `motorCurrentA` (phase current)
- `powerW = V × line current`
- `eRpm` / motor RPM → `speedKmh`: if the controller reports km/h use it
  (REPORTED); else derive from RPM + `MotorConfig` (wheel diameter/gear — FarDriver
  builds are geared bikes/Sur-Rons, so gear ratio matters) → DERIVED.
- `escTempC` (controller temp), `motorTempC` (motor temp if wired),
  `hasMotorTemp` accordingly.
- `dutyPercent` — if a load/duty register exists, decode it; FarDriver may not
  expose true duty (then `dutyPercent` unavailable, no ШИМ alarm — like Kelly).
- `faults` from the status/fault word (build a code→label map from capture).
- odometer/trip if present.

## 4. Detection & AWD
- Detection: match FarDriver's BLE service UUID (from capture) →
  `ControllerType.FARDRIVER`; confirm by a valid frame.
- **AWD (dual FarDriver):** no CAN → two independent BLE links, each a
  single-controller `FarDriverProtocol`. Multi-link raises both; `MotionAggregator`
  (A) folds them (speed/duty max, current/power sum, temps max). No new transport
  — this is C §7 verbatim.

## 5. Testing
- `FarDriverProtocolTest` — decode captured fixtures across throttle/idle/brake →
  assert every `ControllerData` field; fault word → labels; speed provenance.
- Detection test; AWD aggregation (two FarDriver links fold correctly).

## 6. Open questions (mostly resolved only by hardware)
1. **Frame format & register map** — the crux; needs a real capture. Do not ship
   guesses; gate on a validated fixture.
2. **Speed provenance** — does the controller report km/h, or only RPM?
3. **Duty availability** — is a true PWM/duty exposed? If not, gate the ШИМ alarm
   off for FarDriver vehicles (F §3).
4. **Current sign & scaling** — confirm regen/discharge sign per capture.

---

## 7. Two contracts inherited from Part F's availability gate (2026-07-26)

Found while building `MotionAlertAvailability` (F Task 3). Both fail silently:
the alert renders as armed and never fires, and nothing throws.

**7.1 — no sensor means the sentinel, not zero.** `ControllerData.hasEscTemp` is
computed as `escTempC > -50f` — VESC's "no sensor wired" sentinel generalised
across protocols. Leaving `escTempC` at its `0f` default when FarDriver reports
no ESC temperature claims the sensor exists and arms `ESC_TEMP` against a
constant zero. Write a sub-−50 value instead, and set `hasMotorTemp` honestly.

**7.2 — `reportsDuty` for `FARDRIVER` is currently `true` and unverified.**
`MotionAlertAvailability`'s static table says FarDriver reports duty; that is a
placeholder standing on §6.3, which is open. If the capture shows no true
PWM/duty, **flip the table entry to `false`** — it is pinned by a test, so the
change cannot be inherited by accident. Leaving it `true` while writing `0f` into
`dutyPercent` gives the rider a ШИМ alarm shown as armed and permanently silent.
