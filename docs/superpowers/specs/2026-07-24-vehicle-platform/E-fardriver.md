# Part E — FarDriver controller

| Field | Value |
|---|---|
| Part | E (last — hardware-gated, scheduled right after the rest) |
| Depends on | A, B, C (AWD reuses C's independent-links path) |
| Blocks | — |
| Hardware | a FarDriver controller (ND72/84/96 / SIAECOSYS family) |
| Confidence | **medium for the decoded fields** — the official app's parser is verified statically; live capture is still required for firmware-wide compatibility |

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

**Conclusion:** no public, verified BLE implementation was found that could be
ported directly into Volty. The open-source serial/CAN work remains a reference,
not a substitute for a capture of this controller and its official app. Volty's
decoder below is instead pinned to the static parser in the official APK and is
deliberately read/notify-only until a live capture proves that the rider's
firmware follows the same stream.

### 0.1 Static reverse of the original Android app (2026-08-09)

The original Android package was obtained as `com.FarDriver.MotorNet`, version
`2.8.8`/build `288`, label `远驱电控`. Its APK is signed by a certificate whose
subject is `CN=GenzhongLiao, OU=Development, O=NanjingFarDriver`. Static analysis
only was used; the APK was not installed or executed.

The app is a Xamarin.Forms application. The protocol code is in the bundled
`MotorNet6.dll` assembly (inside Xamarin's `assemblies.blob`), not in the small
Java wrapper produced by the Android build. The decompiled C# gives us a real
BLE endpoint and frame parser:

- service `0000ffe0-0000-1000-8000-00805f9b34fb`;
- characteristic `0000ffec-0000-1000-8000-00805f9b34fb`, used for notifications
  and writes;
- notifications are subscribed with `StartUpdatesAsync()` and reassembled as
  fixed 16-byte frames, so a BLE notification boundary is not treated as a
  protocol boundary;
- current/new controller frames start with `0xAA`, use `0x80 | registerIndex`
  in byte 1, carry payload bytes 2–13 (commonly consumed as six big-endian
  `u16` slots), and end with a lookup-table CRC16 over bytes 0–13 (equivalent
  to CRC-16/MODBUS with polynomial `0xA001`, initial state `0x7F3C`, low byte
  first); the app maps 55 register indexes to controller addresses;
- an older frame family also starts with `0xAA`, but uses a command byte and a
  big-endian additive checksum in bytes 14–15.

Concrete live-value mappings present in the app include register `232`
(battery/line voltage `u16 / 10`, signed line current `i16 / 4`), register `238`
(phase currents), register `226` (RPM and status), register `214` (global status
  words), and register `244` (motor temperature plus a battery-capacity byte).
These are reverse-engineered app mappings. They are now the pinned Volty
contract for the read-only decoder, while the exact controller firmware variant
still needs a capture before any write or compatibility claim is made.

The original app is **not read-only overall**. It sends 8-byte command frames
(`0xAA`, command, complemented command, subcommand, two arguments, sum and
complement) for login/keepalive, password/time and other control operations;
larger parameter/flash writes use an address frame and 20-byte BLE chunks. That
does not mean Volty needs controller configuration writes for telemetry. The
Volty's telemetry decoder remains read/notify-only. A live capture is required
before considering any post-connect session/keepalive write; do not guess from
the configuration-writing paths.

> Read `00-overview.md`, `A-foundation.md`, `B-vesc-dashboard.md`,
> `C-multi-controller.md` first. FarDriver is the highest-uncertainty protocol:
> it has no public spec and CAN availability is controller-dependent (so AWD
> remains independent BLE links until the rider's hardware proves otherwise). It is
> deliberately last, but scheduled immediately after the rest so the "supports
> FarDriver" claim is real.

## 1. Scope
**In:** a `FarDriverProtocol` (`BmsProtocol + MotionSource`) decoding the BLE
telemetry into `ControllerData`, optional controller-derived battery evidence,
and both frame families; detection; AWD via independent links (`C §7`).
**Out:** controller writes/config (read-only); CAN integration (the family has
CAN-capable variants, but the rider's exact interface is unverified).

## 2. Approach — reverse-engineer, then pin
The static APK parser is now the first pinned source of truth. The implementation
must still:
1. **Capture** live BLE traffic from a real FarDriver + its app before enabling
   any writes. Record the advertised endpoint and raw frames across
   throttle/idle/brake.
2. **Cross-reference** community reverse-engineering (FarDriver/Sabvoton
   integrations for ESPHome/Home-Assistant and hobbyist repos exist; treat as
   hints, verify against capture).
3. **Pin** new firmware observations with recorded fixtures, exactly like
   `FarDriverProtocolTest`.

### 2.1 Implemented boundary (2026-08-09)

`FarDriverProtocol` is wired into the controller factory and picker coverage.
It subscribes to FFE0/FFEC notifications, reassembles arbitrary chunks, accepts
the new CRC16 frames and legacy additive-checksum frames, and publishes only
fields with frame evidence. It derives speed from RPM plus configured wheel
geometry, keeps duty/distance/energy counters unavailable, and creates the
optional derived pack only after voltage and line-current evidence both exist.
The controller's VCU SOC byte remains motion metadata; it is not promoted to a
known BMS SoC.

The protocol emits no handshake, poll, login, keepalive, configuration, time,
password, flash, or firmware writes. A live capture is still required before
changing that boundary or claiming support for every FarDriver firmware family.

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
1. **Frame format & register map** — statically resolved for the official APK;
   a live capture must confirm that the rider's firmware emits the same map.
2. **Speed provenance** — the app exposes RPM in these frames; Volty derives
   km/h from configured wheel geometry.
3. **Duty availability** — no verified PWM/duty field is exposed; the ШИМ alarm
   is disabled for FarDriver until a capture proves one.
4. **Current sign & scaling** — static mapping pins voltage/current scaling;
   live regen/discharge traffic should confirm the sign on the rider's unit.

---

## 7. Two contracts inherited from Part F's availability gate (2026-07-26)

Found while building `MotionAlertAvailability` (F Task 3). Both fail silently:
the alert renders as armed and never fires, and nothing throws.

**7.1 — no sensor means the sentinel, not zero.** `ControllerData.hasEscTemp` is
computed as `escTempC > -50f` — VESC's "no sensor wired" sentinel generalised
across protocols. Leaving `escTempC` at its `0f` default when FarDriver reports
no ESC temperature claims the sensor exists and arms `ESC_TEMP` against a
constant zero. Write a sub-−50 value instead, and set `hasMotorTemp` honestly.

**7.2 — `reportsDuty` for `FARDRIVER` is `false`.**
The static parser exposes no verified PWM/duty field. Volty therefore publishes
`hasDuty = false` and the availability table keeps the ШИМ alarm unavailable;
the decision is pinned by `MotionAlertAvailabilityTest` so it cannot silently
become an armed-but-dead alarm.

---

## A vehicle of this type can be locked out of its own Ride dashboard, silently

Found in Part D Task 5's review (2026-07-27).

`RootComponent.homeConfigFor` is the single rule behind every landing decision
and the Ride tab's visibility, and it is **type-agnostic** —
`vehicle?.hasControllers == true`. Every test of it used a VESC until Part D
added one for Begode.

The controller factory and picker coverage now include FarDriver, so a vehicle
of this type follows the same Ride-dashboard routing as the other motion
controllers. The full suite after the implementation is **1857 tests, 0
failures**; live emulator/hardware rendering remains a separate field check.
