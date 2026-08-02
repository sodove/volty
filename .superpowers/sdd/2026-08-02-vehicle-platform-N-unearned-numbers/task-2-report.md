# Part N — Task 2 report

## Scope and implementation

`Vehicle.withCellCount(count)` now applies a learned count to every pack with
the primary pack's BLE address.  That is the identity of a Begode wheel: its
two reported branches are one physical pack multiplexed over one BLE device.
Packs at another address retain their own count.

`List<Pack>.expandedTo(count)` now gives a synthesised branch the template
pack's `cellCount`.  Thus a rider-entered count and a derived count both reach
the branch that needs voltage-based SoC estimation.

No BLE write path changed; specifically, this task never writes Begode FFE1.

## Changed files

- `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Vehicle.kt`
- `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/domain/model/Pack.kt`
- `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/model/VehiclePacksTest.kt`
- `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/domain/stats/VoltageSocEstimatorNoCellsTest.kt`
- `.superpowers/sdd/2026-08-02-vehicle-platform-N-unearned-numbers/task-2-report.md`

## TDD evidence

Before writing tests, the named breaks were:

1. changing `withCellCount` back to index 0 only leaves the second branch
   unconfigured and unestimable;
2. propagating to every pack corrupts a genuinely independent Begode pack;
3. dropping the template count while synthesising a branch loses a rider's
   configured count.

RED command:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.domain.model.VehiclePacksTest" --tests "ru.sodovaya.volty.domain.stats.VoltageSocEstimatorNoCellsTest"
```

RED result: `17 tests completed, 3 failed` —
`withCellCountUpdatesEveryBranchOfTheSameWheelButNotAnotherPack`,
`expandedToCarriesARiderEnteredCellCountToTheSecondWheelBranch`, and
`aCellLessSecondWheelBranchEstimatesFromItsOwnConfiguredCount`.

GREEN command: same focused command.

GREEN result: `BUILD SUCCESSFUL`; 17 selected tests passed.  The focused
selection was rerun after the address-only refactor and remained green.

## Audited full-suite / mutation evidence

The audited harness was reused verbatim:

```powershell
& 'C:\Users\sodovaya\Desktop\volty\.superpowers\sdd\2026-07-30-vehicle-platform-I-field-fixes\t11sweep.ps1' -Only 'BASELINE,CONTROL' -Expected 1644
```

It wipes `composeApp/build/test-results/testDebugUnitTest`, appends a fresh
GUID nonce to commonMain for every invocation, restores every target, and
asserts the XML count.  An initial `-Expected 1646` attempt was rejected as
`WRONG-TEST-COUNT(1644)` for both baseline and control; it is not treated as
evidence.  The corrected run produced:

```text
BASELINE  SURVIVED exit=0 tests=1644 failed=0
CONTROL   KILLED(18) exit=1 tests=1644 :: RideEnergyTest#...
```

Each Task 2 mutant was temporarily applied with `apply_patch`, then run
through the same audited harness as:

```powershell
& 'C:\Users\sodovaya\Desktop\volty\.superpowers\sdd\2026-07-30-vehicle-platform-I-field-fixes\t11sweep.ps1' -Only 'BASELINE' -Expected 1644
```

The harness still supplied its fresh nonce, results-directory wipe, full
suite, and XML count assertion; `BASELINE` is only its run label while the
Task 2 mutation is active.

| Behavior | Temporary bytecode mutation | Result / killing assertion |
| --- | --- | --- |
| A learned count reaches each same-wheel branch and a cell-less second branch estimates | `if (pack.bmsAddress == primaryPack.bmsAddress)` → `if (pack.index == 0)` | `KILLED(2) exit=1 tests=1644`: vehicle shared-branch assertion and branch-1 estimator assertion |
| A different physical pack remains independent | same condition → `if (true)` | `KILLED(1) exit=1 tests=1644`: independent-wheel count assertion |
| A rider-entered count reaches a synthesised branch | `cellCount = template.cellCount` → `cellCount = null` | `KILLED(1) exit=1 tests=1644`: synthetic-branch count assertion |

Final restored-baseline command (same audited command with
`-Only 'BASELINE' -Expected 1644`) produced:

```text
BASELINE  SURVIVED exit=0 tests=1644 failed=0
```

## Self-review

- The association uses BLE address, the existing domain model's documented
  boundary between branches of a Begode and independent packs.
- A pack-less controller-only vehicle remains an identity operation.
- Existing pack indices and non-cell configuration remain untouched.
- The new estimator test uses an intentionally incoherent producer fixture:
  voltage is present, cells are absent, and `socKnown` is false.  It proves
  the profile-derived count, rather than producer habits, makes branch 1
  estimable.
- `git diff --check` passed.

## Concerns

None.  The test/build output continues to include unrelated existing Kotlin
expect/actual and redundant-conversion warnings; no new warning was added.

## Fix round 1/5 — persisted legacy branch repair

### Finding and implementation

The original Task 2 `withCellCount` propagation was unreachable for a profile
already stored as `{ branch 0 = 40, branch 1 = null }`: the repository's
auto-fill gate returned as soon as branch 0 matched 40.  `expandedTo()` also
leaves that already-two-branch profile unchanged, so reconnecting could never
repair branch 1.

`KableBmsRepository.maybePersistCellCount` now returns only when every pack at
the primary pack's BLE address already holds the confirmed count.  A pack at a
different address neither receives the repair nor makes an otherwise complete
wheel write again.

Additional changed files in this fix-round commit:

- `composeApp/src/commonMain/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepository.kt`
- `composeApp/src/commonTest/kotlin/ru/sodovaya/volty/data/ble/KableBmsRepositoryCellCountTest.kt`

No BLE write path changed; Begode FFE1 remains untouched.

### TDD evidence

Before writing the tests, the named breaks were:

1. restoring the primary-only guard suppresses the upsert before branch 1 can
   be repaired;
2. requiring all packs, including another address, to match makes an unrelated
   pack force a needless rewrite.

RED command:

```powershell
.\gradlew.bat :composeApp:testDebugUnitTest --tests "ru.sodovaya.volty.data.ble.KableBmsRepositoryCellCountTest"
```

RED result: the 11-test focused class had exactly one failure:
`a confirmed wheel count repairs a legacy second branch without touching
another address` expected one real persistence upsert and observed zero.  Its
driver feeds `BegodeDumpFixture` through the real `BegodeProtocol`, asserts
the decoder confirmed 40S, and only then evaluates the repository collector;
the failure is therefore the real persistence gate, not a direct
`withCellCount` call.

GREEN command: same focused command.

GREEN result: `BUILD SUCCESSFUL`; all 11 focused tests passed.

### Audited mutation / full-suite evidence

The audited harness command was rerun with the new exact count:

```powershell
& 'C:\Users\sodovaya\Desktop\volty\.superpowers\sdd\2026-07-30-vehicle-platform-I-field-fixes\t11sweep.ps1' -Only 'BASELINE,CONTROL' -Expected 1646
```

Result: `BASELINE SURVIVED exit=0 tests=1646 failed=0`; its independent
bytecode-changing control was `KILLED(18) exit=1 tests=1646`.  The harness
wipes results and appends/restores a fresh GUID nonce for every invocation.

For each temporary guard mutation below, the same audited harness was run as
`-Only 'BASELINE' -Expected 1646` while that mutation was active:

| Behavior | Temporary mutation | Result / killing assertion |
| --- | --- | --- |
| A stale same-address branch is repaired | address-scoped all-branches predicate → old `packs.firstOrNull()?.cellCount == n` guard | `KILLED(1) exit=1 tests=1646`: legacy-branch repair upsert assertion |
| An unrelated address does not force a rewrite | address-scoped predicate → `vehicle.packs.all { it.cellCount == n }` | `KILLED(1) exit=1 tests=1646`: unrelated-address no-upsert assertion |

Final restored baseline, run through the audited harness at `-Expected 1646`:

```text
BASELINE  SURVIVED exit=0 tests=1646 failed=0
```

### Fix-round self-review and concerns

- The gate is scoped by the same BLE-address identity used by `withCellCount`.
- The regression starts from the exact pre-patch persisted shape and asserts
  the issued repository upsert, so it cannot pass merely because a direct
  domain helper works.
- The independent-address fixture checks both directions: it remains 24 while
  a legacy wheel branch is repaired, and its own missing count cannot trigger
  a wheel rewrite.
- Reviewer Minor stale-comment item was intentionally not changed in this
  round, as directed.
- `git diff --check` passed. No new concerns.
