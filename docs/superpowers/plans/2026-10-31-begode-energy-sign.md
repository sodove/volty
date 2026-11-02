# Begode battery-current polarity correction

**Status: experimental plan withdrawn before release.** Rider confirmed normal positive T4 consumption in the same Volty garage, contradicting a universal sign inversion. All seven experimental source/test edits were restored to their original state and the proposed regression class archived outside the test suite. The scope now requires per-wheel raw evidence; see ../sdd/2026-10-31-begode-energy-sign.md. The original hypothesis and steps below are retained as a retraction record, not an approved implementation.

> **For agentic workers:** Use superpowers:subagent-driven-development for the narrowly scoped decoder correction and review. The primary owns investigation and end-to-end regression tests.

**Goal:** A Begode discharge contributes positive controller Wh and Ah, so entered-capacity range and rest-to-rest capacity learning can work.

**Architecture:** Preserve ControllerData's discharge-positive and BmsData's charge-positive contracts. Decode the 0x07 word in battery convention (raw / 100); negate once at the controller boundary. Smart BMS 0x01 remains separately negated, as confirmed on the rider's ET Max.

**Evidence / spec:** Rider report 2026-10-31: ordinary ET Max ride reports -58.9 Wh/km and no range even with entered capacity. loeuc-core 83f2d99f3ec8d8a871e704bda7dd1b8c87cc22fa, BegodeProtocolEngine.batteryCurrentAmps documents negative raw words during acceleration and positive during braking. WheelLog GotwayAdapter also negates 0x07 into its discharge-positive current. Our stationary capture does not prove physical polarity.

**Constraints:** Never write FFE1. Preserve regen sign and travel-direction independence. Preserve smart BMS branch sign, current units, unknown-data gates, unrelated working changes, and Gradle four-CPU limits. No abs(current), speculative BMS calibration, data migration or clearing rider history. Work in the current checkout, whose relevant decoder files were clean before this change.

## Tasks

- [ ] Primary: add decoder-to-consumption/range/learning regressions and observe their failures before editing production code.
- [ ] Narrow implementer: fix 0x07 battery-convention decode, retract the previous stationary-capture inference, and correct affected existing test expectations and synthetic wire inputs. Do not alter smart branch polarity.
- [ ] Run focused tests, then the full debug unit suite without build-cache reuse; inspect fresh XML counts and failures.
- [ ] Narrow review: verify polarity, regen, untouched smart branch, and meaningful regression coverage. Record remaining field-test limitation and results in ledger.

## Review focus

Raw negative discharge; raw positive regen; both travel directions; zero and unknown current; smart BMS handover; existing stationary fixture expectations interpreted as raw readings rather than a proof of drain; no unrelated edits.
