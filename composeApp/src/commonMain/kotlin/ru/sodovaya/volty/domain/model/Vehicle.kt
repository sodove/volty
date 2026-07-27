package ru.sodovaya.volty.domain.model

import ru.sodovaya.volty.domain.alert.AlarmDefaults
import ru.sodovaya.volty.domain.alert.AlertRule
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class Vehicle(
    val id: String,
    val name: String,
    val iconKey: String,
    /** Never empty unless [controllers] carries at least one source instead. */
    val packs: List<Pack>,
    val controllers: List<Controller> = emptyList(),
    val topology: PackTopology = PackTopology.PARALLEL,
    val chemistry: Chemistry,
    val averagingWindowMin: Int = 5,
    val alertConfig: AlertConfig = AlertConfig(),
    val createdAt: Instant,
    val lastConnectedAt: Instant? = null,
    val isPinned: Boolean = false,
    /** Which Ride renderer this vehicle uses. Null = follow the app-level default. */
    val dashboardStyle: DashboardStyle? = null,
    /** What the secondary gauge shows on this vehicle's dashboard. */
    val secondaryGauge: SecondaryGauge = SecondaryGauge.DUTY,
    /**
     * "Yield BMS to head unit while riding" (C §5).
     *
     * When this vehicle's battery is reachable over TWO paths sharing an
     * `aliasGroup` — a direct BMS link and a battery hosted behind a gateway
     * head unit — both cannot hold the BMS at once: the head unit takes its
     * single BLE central slot. With the handoff on, the app releases its own
     * direct link once the hosted path is actually delivering, and re-raises
     * it when the head-unit link drops.
     *
     * **Null means "follow the default", which is ON** — and, because the
     * handoff is only ever planned for an alias group that genuinely spans a
     * direct and a gateway-hosted BMS
     * ([ru.sodovaya.volty.data.ble.planAliasHandoffs]), null resolves to
     * "release the direct link" exactly where the contention exists and to
     * "do nothing" everywhere else. Persisted as a nullable column for the
     * same reason [dashboardStyle] is: an unset value is not the same
     * statement as an explicit `true`, and a NOT NULL DEFAULT 1 would claim
     * every pre-existing vehicle had opted in.
     */
    val yieldBmsToHeadUnit: Boolean? = null,
    /**
     * The rider's motion alert configuration (F §10.2), or **null when they have
     * never configured one** — in which case [ru.sodovaya.volty.domain.alert.AlarmDefaults]
     * apply. Use [motionAlertRules] rather than reading this directly.
     *
     * The nullability is the whole point and it is *not* a second way to say
     * "off". "Off" is an [AlertRule][ru.sodovaya.volty.domain.alert.AlertRule]
     * with no levels, and only that — so a rider who deletes every level of
     * every kind gets `listOf(AlertRule(DUTY, []), ...)`, an empty-*ish* but
     * non-null list, and silence survives a restart. Null is the strictly
     * different statement "nobody has ever answered this question", which only
     * the storage layer can make; nothing inside
     * [AlertRule][ru.sodovaya.volty.domain.alert.AlertRule] knows about it.
     */
    val motionAlerts: List<AlertRule>? = null
) {
    init {
        require(packs.isNotEmpty() || controllers.isNotEmpty()) { "Vehicle needs a source" }
        // One rule per kind. Storage keys AlertLevelRow by (vehicleId, kind,
        // position), so a duplicated kind would be silently half-overwritten on
        // save and come back as something the caller never asked for. Refusing
        // it here turns a silent round-trip corruption into a caller bug.
        require(motionAlerts == null || motionAlerts.distinctBy { it.kind }.size == motionAlerts.size) {
            "duplicate MotionAlertKind in motionAlerts: ${motionAlerts?.map { it.kind }}"
        }
    }
}

/**
 * This vehicle's motion alert rules with "never configured" already resolved to
 * [AlarmDefaults] — the form every consumer wants. Still ungated: pass it
 * through [ru.sodovaya.volty.domain.alert.armedRules] before an alarm engine
 * sees it.
 */
val Vehicle.motionAlertRules: List<AlertRule> get() = motionAlerts ?: AlarmDefaults.all()

/** True when this vehicle has at least one motor controller wired in. */
val Vehicle.hasControllers: Boolean get() = controllers.isNotEmpty()

/**
 * [Vehicle.yieldBmsToHeadUnit] resolved: unset (null) follows the default,
 * which is ON. Only an explicit opt-OUT switches the handoff off — see the
 * field's doc for why "on by default" is safe to state unconditionally here.
 */
val Vehicle.yieldsBmsToHeadUnit: Boolean get() = yieldBmsToHeadUnit != false

/** The lowest-indexed controller, or null when the vehicle has none. */
val Vehicle.primaryController: Controller? get() = controllers.minByOrNull { it.index }

/**
 * The address volty identifies/connects this vehicle by. Prefers the
 * primary controller over the primary pack — safe because [init] guarantees
 * at least one of [packs] / [controllers] is non-empty.
 */
val Vehicle.primaryAddress: String get() =
    primaryController?.address ?: packs.first().bmsAddress

/**
 * Builds a conventional one-BMS vehicle. Keeps the pre-multi-pack parameter
 * names so call sites read the same as before.
 */
@OptIn(ExperimentalTime::class)
fun singlePackVehicle(
    id: String,
    name: String,
    iconKey: String,
    bmsType: BmsType,
    bmsAddress: String,
    chemistry: Chemistry,
    cellCount: Int? = null,
    averagingWindowMin: Int = 5,
    alertConfig: AlertConfig = AlertConfig(),
    createdAt: Instant,
    lastConnectedAt: Instant? = null,
    isPinned: Boolean = false
): Vehicle = Vehicle(
    id = id,
    name = name,
    iconKey = iconKey,
    packs = listOf(
        Pack(index = 0, label = name, bmsType = bmsType, bmsAddress = bmsAddress, cellCount = cellCount)
    ),
    topology = PackTopology.PARALLEL,
    chemistry = chemistry,
    averagingWindowMin = averagingWindowMin,
    alertConfig = alertConfig,
    createdAt = createdAt,
    lastConnectedAt = lastConnectedAt,
    isPinned = isPinned
)

val Vehicle.isMultiPack: Boolean get() = packs.size > 1

/** Cell count is auto-filled from live telemetry — see KableBmsRepository. */
fun Vehicle.withCellCount(count: Int): Vehicle =
    copy(packs = packs.mapIndexed { i, p -> if (i == 0) p.copy(cellCount = count) else p })

/**
 * Marker for transient (guest) vehicles synthesized by [BmsRepository.connectGuest].
 * Their [Vehicle.id] uses the sentinel prefix `guest:` so they are never confused
 * with persisted vehicles and never touched in the saved-vehicle store.
 */
const val GUEST_VEHICLE_ID_PREFIX: String = "guest:"

/**
 * True when this vehicle is a transient guest, not persisted in the
 * [ru.sodovaya.volty.domain.repository.VehicleRepository].
 */
val Vehicle.isGuest: Boolean get() = id.startsWith(GUEST_VEHICLE_ID_PREFIX)

/**
 * Sentinel id for the simulated "Try demo" vehicle synthesized by
 * [ru.sodovaya.volty.domain.repository.BmsRepository.connectDemo]. Like a guest,
 * it is never written to the saved-vehicle store.
 */
const val DEMO_VEHICLE_ID: String = "demo"

/**
 * Sentinel id for the simulated "Try demo" WHEEL — a Begode electric unicycle
 * ([DemoProfile.WHEEL]). A second id rather than a prefix test: vehicle ids are
 * caller-supplied strings, and `startsWith("demo")` would silently swallow a
 * real vehicle somebody named `demo-2`, whereas an exact pair cannot.
 */
const val DEMO_WHEEL_VEHICLE_ID: String = "demo-wheel"

/**
 * True when this vehicle is one of the simulated demo vehicles (see
 * [DEMO_VEHICLE_ID], [DEMO_WHEEL_VEHICLE_ID]). Demo is non-persistent like a
 * guest, but distinct: it has no real BLE device behind it at all.
 *
 * Every guard in the app that refuses to persist, touch or prefill from a demo
 * reads THIS, so both demo vehicles must answer true — a wheel demo that
 * answered false would be written to the saved-vehicle store on connect.
 */
val Vehicle.isDemo: Boolean get() = id == DEMO_VEHICLE_ID || id == DEMO_WHEEL_VEHICLE_ID
