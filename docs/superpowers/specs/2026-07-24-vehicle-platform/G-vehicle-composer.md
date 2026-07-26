# Part G — Vehicle composer (build a transport from N sources)

| Field | Value |
|---|---|
| Part | G |
| Depends on | A, B (and benefits from C/D/H existing so there are multiple source types to compose) |
| Blocks | — |

> Read `00-overview.md`, `01-linking.md`, `A-foundation.md`,
> `B-vesc-dashboard.md` first. Today's `VehicleEdit` configures one BMS. Part G
> turns it into a **composer**: a vehicle is N sources (controllers + packs) with
> roles, motor/wheel config, topology, derived-battery and alias rules. It must
> make the common case trivial and the 2×uBox+2×ANT case possible.

> **INHERITED FROM PART B1 (decided 2026-07-25).** Part B ships VESC detection but
> **no way to create a controller-bearing vehicle** — see `B-vesc-dashboard.md §6.1`
> for the full handover, including the exact list of `packs.first()` shim sites that
> must be fixed in ONE pass together with the creation flow (two of them —
> `PickerComponent.kt:79`, `ScanningComponent.kt:63` — take down a whole screen, not
> just a tap, the moment a zero-pack vehicle can exist). Until Part G lands, the VESC
> path is reachable only via the demo vehicle and tests. This is the first thing
> Part G must deliver, not an afterthought.

## 1. Scope
**In:** the composer UI + component; scan/type detection for controllers
(VESC/FarDriver/Kelly) alongside BMS; assigning a discovered device to
source(s); per-controller motor/wheel config; battery topology; derived-battery
rule; `aliasGroup` (alternate-path) marking + duplicate warning; optional CAN
discovery (`PING_CAN`) with explicit include/exclude; per-vehicle alert
thresholds (F); the "yield BMS to head unit while riding" toggle (C §5); the
km/mi unit setting link (B §9).
**Out:** the transports themselves (B/C/D/E/H); controller writes.

## 2. The model it edits
A `Vehicle` (`A §2`) = `packs: List<Pack>` + `controllers: List<Controller>` +
`topology` + `chemistry` + `alertConfig`. The composer produces a valid such
object and persists it (`A §5`). It must never produce a config `planLinks`
rejects (conflicting protocol kinds at one direct address — `A §4.4`).

## 3. Flows, easy path first
- **One controller** (VESC board, EUC): scan → pick the device → "Controller"
  role → done (derived battery defaults on when there's no BMS). 1–2 taps.
- **One controller + one BMS** (typical custom scooter/bike): add the controller,
  add the BMS, done. Derived battery auto-off (real BMS present).
- **EUC**: one device = controller **and** batteries (Begode). The composer offers
  "wheel (controller + battery)" as a single add.
- **Advanced (his scooter)**: connect the head-unit/VESC gateway → **CAN discovery**
  (`PING_CAN`) lists slave controllers + hosted BMS → user includes the two
  uBoxes and the hosted VESC-BMS, optionally adds the direct ANT as an alternate
  path (§5). Never auto-added.

## 4. Per-source configuration
- **Controller**: type (auto-detected, editable), label, `MotorConfig`
  (pole-pairs, wheel diameter, gear ratio) — with a "read from controller"
  affordance for VESC (`GET_MCCONF`) so the user rarely types it;
  `providesDerivedBattery` (auto-set by §6); `canId` (advanced, for CAN sources).
- **Battery pack**: type, label, cell count (auto-filled from telemetry),
  `canId`/hosted (advanced), `aliasGroup` (§5).
- **Vehicle**: name, icon, battery `topology` (parallel/series), `chemistry`,
  `alertConfig` (F defaults by shape), unit system link.

## 5. Alias / duplicate resolution (`01-linking §4`)
- **Duplicate warning**: when two battery sources look like the same physical
  pack (same series-cell count + voltage tracking within a tight band over a few
  samples, or identical reported serial), the composer flags it and offers to
  **mark them one logical battery** (assign a shared `aliasGroup`) rather than
  double-count.
- **Intentional alternate paths** (his direct ANT + hosted VESC-BMS): the user
  explicitly groups them; the "yield BMS to head unit while riding" toggle
  (default on for such a group) drives the C §5 handoff.

## 6. Derived-battery rule
`providesDerivedBattery` defaults **true** for a controller **iff** the vehicle
has no other battery source covering it, else **false**. Recomputed whenever the
source set changes (adding a BMS turns the controller's derived battery off; a
lone controller keeps it). Surfaced as an editable toggle with this default.

## 7. Scan / detection (extends existing picker)
`BmsTypeDetector` + `DiscoveredDevice` learn controller types (VESC = Nordic UART
(B §6), Kelly = KLS UUID (H §6), FarDriver = its UUID (E §4)); the picker labels
a discovered device as controller / battery / both and routes it into the
composer. Recognition of a saved multi-source vehicle is by the set of its
addresses (`01-linking §5`).

## 8. Testing
- Component tests: build each flow (one controller; controller+BMS; EUC;
  gateway+CAN discovery) → a valid `Vehicle`; persistence round-trip.
- Validation: composer never emits a `planLinks`-rejected config; derived-battery
  default recomputes on source changes; duplicate detection suggests an
  `aliasGroup`; conflicting kinds at one direct address are prevented in the UI.
- Detection: each controller type surfaces with the right role.

## 9. Open questions
1. **CAN-discovery UX depth** — how much to expose (raw CAN ids vs friendly
   "Controller 2 / hosted battery"). Prefer friendly with an advanced view.
2. **Duplicate heuristic strength** — voltage-tracking window + sample count to
   avoid false "duplicate" prompts on two genuinely similar packs.
3. **Icon/type presets** — extend `IconKey` for vehicle archetypes
   (scooter/EUC/bike) vs the current battery-centric icons.

---

## 8. The save path must stop rebuilding the vehicle (2026-07-26)

Found in Part F Task 4's review, and it is the third instance of the same bug.

`VehicleEditComponent.onSave()` does not update the vehicle it loaded — it
**rebuilds one from scratch** with `singlePackVehicle(...)` and then hand-copies
selected fields across from `existing`. Every field not named in that copy list is
silently reset to its default on any save, including saves that never touched it:
rename the vehicle, change the secondary gauge, edit pole pairs — and the field is
gone.

Fields it has already caught out: `controllers`/`topology`, then
`yieldBmsToHeadUnit`, then `motionAlerts` (Part F). Each was fixed by adding one
more line to the copy list, and the file now carries a comment explaining the
pattern — which did not stop the third one, because the failure mode is *omission*
and a comment cannot enforce omission.

**Part G2 owns this screen and must replace the rebuild with an update in place**
— start from the loaded `Vehicle` and `copy()` only what the form edited, so a new
field is preserved by default rather than lost by default. The current polarity
means every future field added to `Vehicle` is a data-loss bug until someone
remembers this file.

Part F left behind the test that catches the *class* of defect rather than the
instance: `saving with nothing edited is an identity on the whole vehicle` asserts
equality on the entire object, because field-by-field assertions are exactly how
this reached review three times. Keep that test through the rewrite, and note its
one weakness — it is only as strong as its fixture, so a new field whose default
the fixture never overrides still slips through.
