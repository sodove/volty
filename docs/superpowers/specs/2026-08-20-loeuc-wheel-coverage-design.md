# loeuc-core wheel coverage in Volty

## Goal

Bring the confirmed read-only telemetry improvements from `loeuc-core` into Volty for the wheel families that matter to this project: Begode, KingSong, InMotion, Leaperkim/Veteran, Nosfet, and Ninebot. Solowheel Xtreme and SKAT/CAN-Control are explicitly out of scope.

## Scope

The work covers two kinds of change:

1. Compare Volty's existing decoders against the independently tested `loeuc-core` engines and port only confirmed wire/layout/validation behavior that Volty is missing or gets wrong.
2. Add the Nosfet identity where it shares the Leaperkim wire protocol, without inventing a second decoder or weakening the read-only boundary.

The work does not add charger protocols, settings writes, light/pedal-mode commands, gyroscope calibration, public device discovery, or the excluded Solowheel/SKAT protocols.

## Architecture

Keep Volty's existing `BmsProtocol` and `MotionSource` boundaries. Each family remains a Volty protocol adapter that owns framing, reassembly, decode state, and conversion to `ControllerData`/`BmsData`; it does not expose `loeuc-core` types to presentation or BLE transport code.

The neutral telemetry contract remains authoritative in Volty:

- an absent metric is represented by its existing known flag or `null`-equivalent contract;
- a real zero remains a real zero only when the source has earned it;
- every controller protocol stays read-only unless a separate, explicitly approved command task exists;
- a field is ported only when the loeuc implementation and its tests provide a concrete wire or fixture basis.

The audit must distinguish three outcomes per field: already equivalent, safe port, and intentionally deferred because the evidence is model-specific or unverified.

## Families and expected treatment

| Family | Current Volty shape | Treatment |
|---|---|---|
| Begode | `BegodeProtocol` | Control comparison; preserve the ET Max capture-backed behavior unless loeuc supplies a proven missing field or validation rule. |
| KingSong | `KingSongProtocol` | Compare framing, command cadence, live telemetry, BMS pages/cells, and unknown values; port confirmed gaps. |
| InMotion | `InMotionProtocol` | Compare V2 framing, model variants, BMS/diagnostic pages, and read-only polling; port confirmed gaps without enabling control commands. |
| Leaperkim/Veteran | `VeteranProtocol` | Compare legacy/co-stream/STR records, BMS pages, diagnostics, and identity data; port only fields that map cleanly to Volty. |
| Nosfet | no separate identity | Reuse the Leaperkim decoder and add explicit identity/detection only after the shared wire shape is proven. |
| Ninebot | `NinebotProtocol` + `NinebotLegacyProtocol` | Compare Protocol-2 framing/envelopes, legacy frames, BMS pages, and read-only diagnostics/settings reads. |
| Solowheel Xtreme | none | Explicitly excluded. |
| SKAT/CAN-Control | none | Explicitly excluded. |

## Safety and provenance

Volty must not write to wheel command channels as part of this work. In particular, Begode FFE1 remains forbidden and Veteran/Leaperkim command characteristics remain read-only. Read/poll behavior may only be enabled where the current Volty connection already has a verified, safe read path and the source does not mutate state.

Every non-trivial port records the loeuc source file and test/fixture that supports it. If substantial source code is copied rather than reimplemented, add an MIT attribution to `THIRD_PARTY_NOTICES.md` naming `cancelledbit/loeuc-core` and its authorship/license source.

## Verification

Each family gets focused protocol tests before production changes. Tests must cover fragmented/noisy input where the source supports it, malformed-frame rejection, a known non-zero reading, a known zero where the wire proves it, and absence of a field where the frame does not provide it. Integration tests then verify detector/connection wiring and that the command lists remain empty or read-only as intended.

The full Volty suite, migration verification, and release build remain required after all family changes. Hardware validation is a separate step: source fixtures prove decoding, not BLE behavior on every model/firmware.
