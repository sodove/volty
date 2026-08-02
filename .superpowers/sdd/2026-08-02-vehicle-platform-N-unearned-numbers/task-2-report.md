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
