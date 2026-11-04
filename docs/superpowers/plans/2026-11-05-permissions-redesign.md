# Permission onboarding implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development; complex navigation/component work stays with the primary agent per AGENTS.md.

**Goal:** Explain permissions in separate steps and allow a permission-free demo.

**Architecture:** The existing permission component owns grouping, step transitions, refusal and demo selection. Root navigation gates BLE destinations and bypasses notification-only startup gating; demo does not start the connected-device foreground service or request location automatically.

**Tech Stack:** Kotlin, Decompose, Compose Material 3 Expressive, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-05-permissions-redesign.md`

## Global constraints

- Preserve the local BegodeProtocolTest.kt change; no BLE wire changes.
- Keep Gradle workers and ActiveProcessorCount at 4.
- Strings in values and values-ru; no Android backslash escapes.
- No Compose unit test infrastructure; verify visual rendering on an emulator if available.

## Review focus

- Partial Bluetooth grants cannot advance to scanning.
- A denied notification permission cannot trap startup or the current session.
- Both demo profiles work without BLE/location access, including Light style.
- A later attempt to connect real hardware after demo revisits the BLE gate.
- Settings return and repeated callbacks cannot duplicate navigation.

### Task 1: Explain permission steps in both languages

**Files:** Create `composeApp/src/commonMain/composeResources/{values,values-ru}/permissions.xml`.

- [x] Add matching resource keys for the chosen Bluetooth/notification steps, old Android explanation, capability rows, skip/demo/profile actions, denial/settings and demo failure.
- [x] Review resource XML, parity and copy; no tests for static wording.

### Task 2: Component, navigation and permission-free demo (primary)

**Files:** existing PermissionsGateComponent.kt, RootComponent.kt, RootScreen.kt, RideMapHostPolicy.kt, KableBmsRepository.kt; component, root policy, map and demo tests.

**Interfaces:** component consumes required permission strings, current missing-permission reader, on-ready callback and suspend demo starter. Exposes StateFlow of current step, missing group, denial/settings, demo chooser/progress/error, and explicit user actions.

- [x] Write component tests for partial grants, explicit requests, notification denial/skip, refresh from settings, demo profiles/error and repeated callbacks; run red before implementation.
- [x] Write tests for BLE-only route gating, demo foreground-service avoidance and suppression of automatic demo location; run red before implementation.
- [x] Implement the component and wire root callbacks; preserve normal Welcome/scanning resolution and existing config serialization.
- [x] Remove demo foreground-service demand (marked retraction) and suppress only automatic demo GPS demand/prompt; retain normal real-vehicle map behaviour.
- [x] Run targeted tests with actual execution and verify results.

### Task 3: Render the approved screen and verify

**Files:** existing PermissionsGateScreen.kt.

- [x] Render the third mockup without the top VOLTY brand using MaterialTheme colors/type, existing Material icons and native buttons/sheet.
- [x] Add explicit system request, permanent-denial settings and resume recheck bridge, no automatic launch from composition.
- [x] Verify full unit suite, assemble debug APK; inspect fresh onboarding, demo and refusal paths on an emulator if available.
- [x] Review scoped diff, record results and limitations; do not commit/push unless requested.
