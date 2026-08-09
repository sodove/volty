# Part E — FarDriver controller

| Field | Value |
|---|---|
| Part | E (last — hardware-gated, scheduled right after the rest) |
| Depends on | A, B, C (AWD reuses C's independent-links path) |
| Blocks | — |
| Hardware | a FarDriver controller (ND72/84/96 / SIAECOSYS family) |
| Confidence | **low** — no official BLE protocol; community reverse-engineering + live capture required |

## 0. Research snapshot — 2026-08-09

The first web search found one substantial open-source reference, but it is not a
drop-in BLE decoder:

- [`jackhumbert/fardriver-controllers`](https://github.com/jackhumbert/fardriver-controllers)
  is MIT-licensed reverse-engineering work for Nanjing FarDriver controllers. It
  documents a 3.3 V serial connection, little-endian register-style status
  frames (usually 16 bytes, beginning with `0xAA`), 8-byte command frames with
  complemented command/CRC fields, and a CRC32 implementation. The same project
  also documents CAN configuration and controller CAN data/IDs. This is useful
  for field names, scaling hypotheses, and a possible wired fallback, but it
  does **not** identify the BLE service/characteristic UUIDs or prove that its
  serial frames are the payload used by the rider's Bluetooth adapter.
- [`rasyid-irsyadi/r-speedo.app`](https://github.com/rasyid-irsyadi/r-speedo.app)
  publicly claims FarDriver telemetry support, but its public repository is a
  documentation site. Its compatibility page says model, firmware, and adapter
  compatibility vary; the README describes an independent ESP32 Votol-CAN
  module for part of the vehicle telemetry path. It does not publish a usable
  FarDriver BLE register map or raw-frame decoder.

This retracts the earlier blanket wording in this spec that FarDriver “has no
CAN”. The FarDriver family has CAN-capable variants; whether the rider's exact
controller exposes CAN is still unknown. The AWD design must remain conditional
on the actual hardware: if the controller has no usable CAN gateway, use the
independent-BLE-link path; do not infer that from the brand name.

**Conclusion:** no public, verified BLE implementation was found that can be
ported safely into Volty. The open-source serial/CAN work is a reference, not a
substitute for the required capture of this controller and its official app.

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

---

## A vehicle of this type can be locked out of its own Ride dashboard, silently

Found in Part D Task 5's review (2026-07-27).

`RootComponent.homeConfigFor` is the single rule behind every landing decision
and the Ride tab's visibility, and it is **type-agnostic** —
`vehicle?.hasControllers == true`. Every test of it used a VESC until Part D
added one for Begode.

The reviewer ran a mutant that returns the battery `Dashboard` for a
`FARDRIVER` or `KELLY` controller: **1160 tests, 0 failures.** So a vehicle of
this type can be routed away from the Ride dashboard — with no Ride tab left to
escape by — and nothing in the suite objects. A green build says everything is
fine while the rider cannot reach their own instruments.

**This part must add the test for its own controller type**, in the same shape
Part D used: a mutant that sends this type to the battery dashboard has to fail
something. One test, and it closes the quarter of the hole this part owns.
