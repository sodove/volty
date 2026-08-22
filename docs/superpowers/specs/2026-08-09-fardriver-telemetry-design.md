# FarDriver telemetry decoder design

Date: 2026-08-09

## Goal

Add a safe, read/notify-only FarDriver controller protocol so a FarDriver
controller can be selected and connected, publish honest motion telemetry, and
back the existing derived-battery path for a controller-only vehicle. Do not
send configuration, login, password, time, firmware, or keepalive commands until
a live capture proves the required session sequence.

## Evidence and boundary

The design is based on the static reverse of the original `com.FarDriver.MotorNet`
2.8.8 APK. It uses service `0000ffe0-0000-1000-8000-00805f9b34fb` and
characteristic `0000ffec-0000-1000-8000-00805f9b34fb` for both notification and
write endpoints. The original application has two 16-byte inbound frame
families:

1. New/register frames: byte 0 is `0xAA`, byte 1 contains `0x80 | registerIndex`,
   bytes 2..13 are payload, and bytes 14..15 are CRC-16/MODBUS with polynomial
   `0xA001`, initial state `0x7F3C`, emitted low byte first. Register indexes map
   to controller addresses; the first implementation needs the app-confirmed
   addresses 226, 232, 238, 214, 244 and 250.
2. Legacy frames: byte 0 is `0xAA`, byte 1 is a command id, and bytes 14..15
   contain the big-endian sum of bytes 0..13.

This is an app-derived protocol description, not a firmware guarantee. Unknown
registers, malformed frames, and incomplete state remain absent rather than
becoming zero readings.

## Correction: scalar byte order (2026-08-20)

The first implementation and its fixtures incorrectly treated ordinary FarDriver
16-bit scalar fields as big-endian. That assumption came from the initial static
APK reading and was disproved by the public reverse-engineering work in
`jackhumbert/fardriver-controllers` and the independent
`bobecek79/ESP32-Fardriver-BLE-Reader`. Register addresses E2, E8, D6 and F4
encode their ordinary 16-bit values little-endian. The symptom was not a
calibration problem: for example, a little-endian 26 °C value was displayed as
6656 °C, and an 83.2 V value was displayed as 1638.7 V when decoded in the old
order.

The decoder and fixtures now use little-endian for those 16-bit fields. The
24-bit phase-current values in EE remain big-endian because the public register
description explicitly specifies that exception. This is a recorded correction
to the earlier BE assumption, not a change to the read-only boundary: the
protocol still emits no writes or commands to FFE1/FFEC.

## Architecture

Create `FarDriverProtocol.kt` beside the other `BmsProtocol` implementations.
It implements `BmsProtocol` and `MotionSource`, owns a `ByteArrayAccumulator`,
and consumes arbitrary BLE notification chunks. The parser scans for `0xAA`,
waits for a complete 16-byte frame, validates the matching checksum, and
advances by one byte after an invalid candidate so a header inside a chunk can
recover. Valid register/legacy frames update a small nullable state object; the
latest `ControllerData` is rebuilt from evidence after every accepted frame.

`handshakeCommands()` and `pollCommands()` return empty lists and
`pollIntervalMs` is irrelevant. This deliberately keeps the first implementation
from writing to a live controller. `uuids` exposes FFE0/FFEC. `reset()` clears
the accumulator, frame state, and derived-battery snapshot.

## Decoded data contract

The new-frame mappings copied from the original app are:

- address 232: input voltage = unsigned bytes 2..3 / 10; signed battery/line
  current = bytes 6..7 / 4;
- address 238: phase A and phase C current from the app's 24-bit square-root
  conversion;
- address 226: mechanical RPM from bytes 8..9 and controller status/fault bits
  from bytes 2..5;
- address 214: controller temperature from bytes 12..13 and global status words;
- address 244: motor temperature from bytes 2..3 and the controller-reported
  SOC byte from byte 5 (this is a percentage-like level, not a rated Ah
  capacity);
- address 250: motor stop/running state.

Legacy command ids 0, 1, 2 and 15 provide the corresponding speed, voltage /
line-current, phase-current and status values when the older firmware format is
used. New and legacy fields merge into one latest sample; a field is marked
available only after its source frame has been accepted.

Speed is derived from the reported mechanical RPM and `MotorConfig` wheel
diameter/gear ratio using the same mechanical-RPM formula as `KellyProtocol`.
Invalid geometry yields `SpeedSource.NONE` with
`SpeedUnknownReason.NO_WHEEL_GEOMETRY`. Duty, distance, energy counters and
rated Ah capacity are unavailable in this first slice. The byte from register
244 may seed `batteryLevelFraction` only as the controller's own reported level;
it is never presented as an amp-hour capacity.
Motor and controller temperatures use the existing below-`-50 °C` sentinel when
their frame has not been seen. Fault strings cover only the bits explicitly
decoded by the original app; unknown bits do not invent labels.

When `deriveBattery` is true, `latestData(0)` is created from a complete voltage
and line-current measurement, with power computed as `V × current` and all
availability flags set from evidence. It contains no fabricated cells or
temperature sensors. When either input is absent, the derived pack remains null
until both are observed.

## Wiring

Add `ProtocolKind.FARDRIVER -> FarDriverProtocol(deriveBattery, motor)` to the
single `controllerMotionProtocol` factory. This automatically makes the picker
and connection factory agree through the existing `controllerMotionSupported`
gate. Update the protocol coverage tests to assert FarDriver is supported and
that the factory returns both `BmsProtocol` and `MotionSource`.

## Tests

Add `FarDriverProtocolTest.kt` covering:

- exact UUIDs and empty command lists (no writes);
- a valid new register frame split across arbitrary BLE chunks;
- CRC rejection and one-byte resynchronisation after a bad candidate;
- valid legacy checksum frames and checksum rejection;
- voltage/current scaling, signed current, phase current, RPM-derived speed,
  temperature/SOC/status mappings;
- incomplete evidence leaves the corresponding `has*` flags false and does
  not create a derived pack;
- `reset()` drops buffered fragments and all latest samples.

Tests use a small fixture builder that computes the exact CRC/checksum rather
than hard-coding opaque bytes. The fixture deliberately omits selected frames
for availability tests, so a mutant that replaces missing data with zero cannot
pass.
