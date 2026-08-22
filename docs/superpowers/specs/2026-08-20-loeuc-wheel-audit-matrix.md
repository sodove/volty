# loeuc-core wheel audit matrix

Дата аудита: 2026-08-20. Источник сравнения: [cancelledbit/loeuc-core](https://github.com/cancelledbit/loeuc-core), checked out locally at `C:\Users\sodovaya\AppData\Local\Temp\volty-loeuc-c84e42e713584a5ebbf6106e7e64bfaa`.

Статусы:

- **equivalent** — Volty уже даёт тот же результат или более строгий безопасный контракт;
- **port** — есть конкретный wire fixture/test и поле, куда результат можно честно положить;
- **deferred** — source code показывает возможность, но текущая модель, write boundary или provenance не позволяет безопасно переносить её сейчас;
- **out of scope** — явно исключено пользователем.

## Common safety decisions

| Area | Status | Evidence | Volty decision |
|---|---|---|---|
| Unknown values | equivalent | loeuc README and each family mapping; Volty `BmsData`/`ControllerData` known flags | Preserve `has*`/`socKnown`; never turn an absent field into zero. |
| Wheel writes | equivalent | `docs/how-it-works.md`; Volty `BmsProtocol` command lists and connection guard | Keep wheel adapters notify-only. In particular never write Begode FFE1 or Veteran/Leaperkim FFE1. |
| Charger protocol | out of scope | loeuc README protocol list | Do not add charger support in this plan. |
| Solowheel Xtreme | out of scope | user scope decision | No decoder, detector, tests, or UI identity. |
| SKAT/CAN-Control | out of scope | user scope decision | No decoder, detector, tests, or UI identity. |

## Begode

| Field/behavior | Status | Evidence | Volty target/decision |
|---|---|---|---|
| ET Max `0x00/01/02/03/04/07` offsets and scales | equivalent | loeuc `BegodeProtocolEngine.kt`; `BegodeProtocolTest.kt`; Volty `BegodeProtocol.kt`/tests | Leave capture-backed decoder unchanged. |
| Fault mask filtering | equivalent | loeuc `BegodeTelemetryMapping.kt`; Volty fault tests | Leave existing `0x78` filtering unchanged. |
| Live-only phase current | port | loeuc engine marks `0x00` current as phase current; Volty audit at `BegodeProtocol.kt:1192,1257` | Keep phase current only as `ControllerData.motorCurrentA`; publish BMS current/power unknown until `0x07` supplies battery current. |
| SoC from partial cell pages | port | Volty `BegodeProtocol.kt:1507`; `VoltageSocEstimator.kt`; loeuc cell completeness behavior | `socKnown=false` until the expected full cell set is present; estimator must not infer pack SoC from a partial page. |
| Cell voltage bounds | deferred | loeuc accepts 2500..4500 mV; Volty accepts 1..5000 mV; no real Volty capture at boundaries | Add adversarial tests before narrowing; do not change a capture-backed range speculatively. |
| Legacy PWM/current estimate | deferred | loeuc labels it estimated; no matching Volty legacy capture | Do not publish an estimated battery current/power. |

## KingSong

| Field/behavior | Status | Evidence | Volty target/decision |
|---|---|---|---|
| Standard `AA 55` framing, split/noise recovery | equivalent | loeuc `KingSongProtocolEngine.kt:431`; tests; Volty `KingSongProtocol.kt`/tests | Preserve current parser. |
| A9 distance byte order | port | loeuc `KingSongProtocolEngine.kt:452`; `KingSongProtocolEngineTest.kt:75,285` | Decode the protocol-specific 32-bit order and keep trip as a delta of earned odometer readings. |
| B9 controller temperature latch | port | loeuc engine and test at `KingSongProtocolEngineTest.kt:118` | Once valid B9 is observed, retain it over later A9 temperatures. |
| F5 duty | port | loeuc engine F5 decoder and test at `KingSongProtocolEngineTest.kt:145` | Map byte 15 to `ControllerData.dutyPercent` with `hasDuty`; no write needed. |
| BMS charge cycles | port | loeuc S22 fixture/test at `KingSongProtocolEngineTest.kt:180`; Volty already has `BmsData.numCycles` | Fill `numCycles` from the summary byte when the summary is valid. |
| Eighth BMS temperature | port | loeuc page 06 offset 10 and test at `KingSongProtocolEngineTest.kt:212` | Append it only when page 06 is valid; no placeholder temperature. |
| Legacy `F1 EF` C2/C5 | deferred | loeuc engine supports it, but Volty FFE1 is a wheel command channel and no local capture proves passive frames | Do not add a write/poll path. Revisit only with a passive capture/fixture. |
| F6 limits/energy, F3/F4, factory Ah/max cell | deferred | source fields exist but no direct Volty model field or raw hardware proof | Do not overload unrelated fields or add settings API. |
| KingSong diagnostics/faults | equivalent | neither implementation has a confirmed diagnostic mapping | Keep unknown. |

## InMotion

| Field/behavior | Status | Evidence | Volty target/decision |
|---|---|---|---|
| AA AA framing and XOR validation | port | loeuc `InmotionProtocolEngine.kt`; tests sweep escaping at `InmotionProtocolEngineTest.kt:1019` | Add model-aware frame extraction with split/concat/noise and escaping while preserving notify-only operation. |
| V11/V12/V13/V14/P6 layouts | port | loeuc engine model IDs 61, 71/72/73/111, 81/82, 91/92, 131; tests at `InmotionProtocolEngineTest.kt:153` | Route by model ID and map only fields present in each layout; absent sensors stay unknown. |
| Explicit power/current/duty/speed | port | loeuc `InmotionTelemetryMapping.kt` and per-model tests | Map to existing known flags; do not derive power when source power is absent. |
| P6 two-pack BMS realtime | deferred | loeuc command `0x05` parser/test at `InmotionProtocolEngineTest.kt:620`; current Volty has one generic pack and no proven passive request/response boundary | Keep direct P6 BMS replies out of the generic pack until a separate capture-backed multi-pack/provenance task exists. |
| Diagnostic `0x03` flags | deferred | loeuc has 45 flags, but Volty has no compatible diagnostic model/strings | Do not convert unmodelled diagnostics into controller faults yet. |
| Settings/control/read command lists | deferred | loeuc includes BLE writes and experimental state-changing builders | Keep `handshakeCommands()`/`pollCommands()` empty until a separate safe polling task. |
| TPMS/angles/extra sensor temperatures | deferred | fields have no Volty contract | Keep absent, not zero. |

## Leaperkim / Veteran / Nosfet

| Field/behavior | Status | Evidence | Volty target/decision |
|---|---|---|---|
| CRC validation itself | equivalent | Volty `VeteranProtocol.kt`; loeuc engine | Preserve CRC rejection. |
| CRC-stripped payload offsets | port | loeuc `LeaperKimProtocolEngine.kt:263`; tests at `:285,321,350`; real Patton page-2 fixture | Strip the four-byte CRC before applying payload offsets; add short-frame regression tests. |
| 24-bit hardware/firmware code | port | loeuc engine `:530`; Lynx-S/Nosfet fixtures | Decode bytes 28..30 as the confirmed 24-bit value and expose model/profile identity only after a valid frame. |
| Smart-BMS page topology | port | loeuc engine `:477`; pages 0/4 main and 1/2/3, 5/6/7 BMS | Do not treat main page 0/4 as a BMS page; require a real BMS page before switching to pack samples. |
| Reported BMS SoC precedence | port | loeuc Lynx-S test `LeaperKimProtocolEngineTest.kt:454` | A valid reported percentage wins over voltage estimate and remains known; an absent percentage stays unknown. |
| Controller phase/output current | port | loeuc `LeaperKimTelemetry`; current Volty `VeteranProtocol.kt` | Map only a confirmed battery current to BMS current; keep motor current and battery current semantically separate. |
| Motor temperature | equivalent | loeuc notes ordinary frame has no motor probe | Keep `hasMotorTemp=false`; the `@38` value is not a motor temperature. |
| STR diagnostics and co-stream | deferred | loeuc `LeaperKimStr*` and `LeaperKimCoStream*`; commands can change wheel state and many fields are unproven | Do not enter STR mode, write ASCII commands, or mix guessed fields into ride telemetry. |
| Settings/light/shutdown/field weakening writes | deferred | loeuc experimental command API | No write builders in Volty. |
| Leaperkim battery-side link | port | Volty `ProtocolKind.VETERAN` is controller-only; loeuc reports two BMS packs | Add shared `BmsType.LEAPERKIM` and wire the Veteran wheel shape as one controller plus its pack sources. |
| Nosfet identity | port | loeuc routes Nosfet and LeaperKim through one decoder; Nosfet hardware profiles | Add `ControllerType.NOSFET` as identity only, route it to `ProtocolKind.VETERAN`, and do not create `BmsType.NOSFET`. |

## Ninebot

| Field/behavior | Status | Evidence | Volty target/decision |
|---|---|---|---|
| Protocol-2 marker/length/CRC | equivalent | loeuc `NinebotFraming.kt`; Volty `NinebotProtocol.kt` | Preserve Volty's stricter envelope checks. |
| Command/destination filtering | equivalent | Volty checks command 0x01/0x04 and destination 0x3E/0; loeuc engine does not | Do not weaken Volty validation. |
| Signed speed as magnitude | equivalent | Volty uses signed raw then `abs`; loeuc uses unsigned and can yield 643 km/h for -1234 | Keep Volty behavior; add a regression fixture if needed. |
| Live current/power | equivalent | Volty deliberately marks fields unknown; loeuc maps ambiguous offsets | Keep unknown until wire semantics are proven. |
| Legacy `55 AA` | equivalent | Volty has `NinebotLegacyProtocol`; loeuc only Protocol-2 | Preserve Volty legacy support. |
| Zero live/BMS pages | port | loeuc accepts CRC-clean zero pages as known; Volty audit identifies unsafe zero semantics | Reject structurally empty pages or leave fields unknown; never publish 0 V cells as earned. |
| BMS pack indexing | port | loeuc uses BMS1/2 indices 1/2; Volty API is 0/1 | Keep Volty's 0/1 public indices, but test the translation boundary. |
| Settings writes and polling | deferred | loeuc `NinebotSettings.kt` and `DeviceSession` expose writes/reads | No settings or write commands in this task. |
| Diagnostics | deferred | loeuc warning/alarm mapping is internally inconsistent and Volty has no matching model | Do not invent fault semantics. |

## Provenance and attribution

The implementation should be independently re-written against the cited layouts and raw fixtures. If a worker copies a non-trivial block of `loeuc-core` code rather than reimplementing it, add `cancelledbit/loeuc-core` and its MIT license to `THIRD_PARTY_NOTICES.md` before integration.
