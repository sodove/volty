# Begode current calibration implementation plan

> Use superpowers:subagent-driven-development for the isolated learner and its review.

**Goal** — Calibrate Begode battery current polarity in the background and expose sign and offset in the vehicle energy settings.

**Architecture** — Feed native controller and real smart BMS samples to a per-link learner. Persist confirmed results and explicit overrides by controller address. Apply the effective sign and additive offset before publishing the controller sample and its derived battery.

## Constraints

- Begode FFE1 is notify-only. All evidence comes from existing telemetry.
- Preserve the existing raw-positive fallback during calibration, including the first kilometre when evidence is sparse. Require physical evidence before persisting an automatic result.
- Ignore boot placeholders. Use a complete fresh pair for smart BMS correction and keep polarity separate from offset.
- Hold manual settings across reconnects and give them priority over learning. Preserve signed regeneration.
- Keep Gradle at four workers and four active processors.

## Tasks

- [x] Write learner regressions and implement physical polarity evidence and smart BMS offset.
- [x] Persist manual and learned settings and attach the learner to the Begode notification path.
- [x] Add localized controls for polarity and offset to the energy settings.
- [x] Replay both field dumps, run regression tests and review the integrated changes.

## Field evidence

The paired `0x00` voltage and `0x07` current steps are a physical polarity witness during normal riding. The sign requires repeated sag and recovery with consistent current changes. A smart BMS can independently witness the direction using the same section of each branch, then transfer the sign through lag-aware current correlation. The whole-pack voltage field tracks the controller rail and is unsuitable as an independent witness.

The October ET Max CSV supplies enough stable complete-branch windows for the offset. It replays to an additive correction of −1.72 A at 2.03 km. Its export lacks independently timed branch voltages, so this validates the current-correlation and offset path. The smart-only physical anchor is covered by a synthetic flat-controller-voltage regression. Neither short BLE dump contained enough evidence to learn an offset.

Learning runs in the background. Provisional current is available to the dashboard and recorder, while range and the persistent battery model use confirmed polarity. A learned correction is associated with the controller sign that earned it. Existing recordings retain the samples they stored.

## Verification

The built decoder replayed all 540 ET Max notifications and 598 T4 notifications. It confirmed inverted polarity at 0.142 km and direct polarity at 0.168 km respectively, using the odometer increments during the bench spins. No frames were rejected. The Android JVM suite ran with fresh results and build cache disabled. It reported 6,599 tests, no failures and two skips. The Compose layout remains to be checked on a device.
