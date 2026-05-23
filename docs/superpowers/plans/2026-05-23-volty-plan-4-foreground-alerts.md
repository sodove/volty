# Volty Plan 4 — Foreground service + Alerts

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Keep BMS connection alive while the app is backgrounded via a foreground service with persistent notification. Implement `AlertEngine` that watches `BmsData` against per-vehicle thresholds and fires notifications (Critical / Warning / Info) with debounce + hysteresis.

**Architecture:** `MonitoringService` (androidMain) is started by `BmsRepository.connect()` and stopped by `disconnect()`. `Notifier` is a multiplatform expect/actual that exposes `showLive(...)` / `showAlert(...)` / `cancelLive()`. `AlertEngine` is a commonMain UseCase that subscribes to `bmsRepository.activeData` + `activeVehicle`, evaluates thresholds, applies debounce + hysteresis, and calls `Notifier`.

**Tech Stack:** No new libs needed (uses Android framework `Service`, `NotificationCompat`, `NotificationChannel`).

**Spec:** `docs/superpowers/specs/2026-05-23-volty-design.md`

---

## File map

```
volty/
├── composeApp/src/
│   ├── androidMain/
│   │   ├── AndroidManifest.xml                       (modify — register service)
│   │   ├── kotlin/com/volty/app/
│   │   │   ├── service/
│   │   │   │   ├── MonitoringService.kt              (new)
│   │   │   │   └── ServiceController.android.kt      (new — actual)
│   │   │   ├── notification/
│   │   │   │   ├── AndroidNotifier.kt                (new — actual)
│   │   │   │   └── NotificationChannels.kt           (new — helper)
│   │   │   └── di/AndroidModule.kt                   (modify — register Notifier, ServiceController)
│   ├── commonMain/
│   │   ├── kotlin/com/volty/app/
│   │   │   ├── notification/
│   │   │   │   └── Notifier.kt                       (new — expect)
│   │   │   ├── service/
│   │   │   │   └── ServiceController.kt              (new — expect)
│   │   │   ├── domain/usecase/
│   │   │   │   ├── AlertEngine.kt                    (new + tests)
│   │   │   │   ├── AlertEvent.kt                     (new — domain enum)
│   │   │   │   └── DefaultAlertThresholds.kt         (new — chemistry-based defaults)
│   │   │   ├── data/ble/
│   │   │   │   └── KableBmsRepository.kt             (modify — start/stop service on connect/disconnect)
│   │   │   └── di/AppModule.kt                       (modify — register AlertEngine + start it)
│   └── commonTest/
│       └── kotlin/com/volty/app/domain/usecase/
│           └── AlertEngineTest.kt                    (new — fake Notifier + synthetic BmsData flow)
```

---

## Task 1: Alert domain types + threshold defaults

**Files:**
- `composeApp/src/commonMain/kotlin/com/volty/app/domain/usecase/AlertEvent.kt`
- `composeApp/src/commonMain/kotlin/com/volty/app/domain/usecase/DefaultAlertThresholds.kt`

### `AlertEvent.kt`

```kotlin
package com.volty.app.domain.usecase

enum class AlertSeverity { CRITICAL, WARNING, INFO }

enum class AlertKind {
    CELL_HIGH, CELL_LOW, CELL_DELTA, TEMPERATURE_HIGH,
    SOC_LOW, SOC_CUTOFF, DISCONNECT, CHARGE_COMPLETE
}

data class AlertEvent(
    val kind: AlertKind,
    val severity: AlertSeverity,
    val title: String,
    val text: String,
    val vehicleId: String
)
```

### `DefaultAlertThresholds.kt`

```kotlin
package com.volty.app.domain.usecase

import com.volty.app.domain.model.AlertConfig
import com.volty.app.domain.model.Chemistry

/** Resolved (non-null) thresholds. */
data class ResolvedAlertConfig(
    val cellHighV: Float,
    val cellLowV: Float,
    val cellDeltaMv: Int,
    val temperatureHighC: Float,
    val socLowPercent: Int,
    val socCutoffPercent: Int?,
    val disconnectNotify: Boolean,
    val chargeCompleteNotify: Boolean
)

fun resolveAlertConfig(config: AlertConfig, chemistry: Chemistry): ResolvedAlertConfig =
    ResolvedAlertConfig(
        cellHighV = config.cellHighV ?: chemistry.defaultHighV,
        cellLowV = config.cellLowV ?: chemistry.defaultLowV,
        cellDeltaMv = config.cellDeltaMv ?: 200,
        temperatureHighC = config.temperatureHighC ?: 60f,
        socLowPercent = config.socLowPercent ?: 15,
        socCutoffPercent = config.socCutoffPercent,
        disconnectNotify = config.disconnectNotify,
        chargeCompleteNotify = config.chargeCompleteNotify
    )
```

Build + commit `feat(alerts): AlertEvent + ResolvedAlertConfig`.

---

## Task 2: Notifier expect/actual

**Files:**
- `composeApp/src/commonMain/kotlin/com/volty/app/notification/Notifier.kt`
- `composeApp/src/androidMain/kotlin/com/volty/app/notification/AndroidNotifier.kt`
- `composeApp/src/androidMain/kotlin/com/volty/app/notification/NotificationChannels.kt`

### `Notifier.kt` (commonMain expect)

```kotlin
package com.volty.app.notification

import com.volty.app.domain.usecase.AlertSeverity

data class LiveSummary(
    val vehicleName: String,
    val socPercent: Int,
    val voltageV: Float,
    val currentA: Float,
    val etaText: String?
)

expect class Notifier {
    fun showLive(summary: LiveSummary)
    fun cancelLive()
    fun showAlert(title: String, text: String, severity: AlertSeverity, alertId: Int)
}
```

### `NotificationChannels.kt` (androidMain)

```kotlin
package com.volty.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val LIVE = "volty.live"
    const val CRITICAL = "volty.critical"
    const val WARNING = "volty.warning"
    const val INFO = "volty.info"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            NotificationChannel(LIVE, "Live monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Volty is monitoring your battery"
                setShowBadge(false)
            },
            NotificationChannel(CRITICAL, "Critical alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Battery is in a dangerous state"
                enableVibration(true)
            },
            NotificationChannel(WARNING, "Warnings", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Things to keep an eye on"
                enableVibration(true)
            },
            NotificationChannel(INFO, "Info", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Charge complete, disconnects, etc."
            }
        ).forEach { mgr.createNotificationChannel(it) }
    }
}
```

### `AndroidNotifier.kt` (androidMain actual)

```kotlin
package com.volty.app.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.volty.app.MainActivity
import com.volty.app.domain.usecase.AlertSeverity

private const val LIVE_NOTIFICATION_ID = 1001

actual class Notifier(private val context: Context) {

    init { NotificationChannels.ensureCreated(context) }

    private val manager = context.getSystemService(NotificationManager::class.java)

    actual fun showLive(summary: LiveSummary) {
        val sign = if (summary.currentA >= 0) "+" else ""
        val builder = NotificationCompat.Builder(context, NotificationChannels.LIVE)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: real icon in plan 5
            .setContentTitle("Volty · ${summary.vehicleName}")
            .setContentText("${summary.socPercent}% · ${"%.1f".format(summary.voltageV)} V · ${sign}${"%.1f".format(summary.currentA)} A${summary.etaText?.let { " · $it" } ?: ""}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(0, "Disconnect", disconnectIntent())
        manager?.notify(LIVE_NOTIFICATION_ID, builder.build())
    }

    actual fun cancelLive() { manager?.cancel(LIVE_NOTIFICATION_ID) }

    actual fun showAlert(title: String, text: String, severity: AlertSeverity, alertId: Int) {
        val channel = when (severity) {
            AlertSeverity.CRITICAL -> NotificationChannels.CRITICAL
            AlertSeverity.WARNING -> NotificationChannels.WARNING
            AlertSeverity.INFO -> NotificationChannels.INFO
        }
        val priority = when (severity) {
            AlertSeverity.CRITICAL -> NotificationCompat.PRIORITY_HIGH
            AlertSeverity.WARNING -> NotificationCompat.PRIORITY_DEFAULT
            AlertSeverity.INFO -> NotificationCompat.PRIORITY_LOW
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
        manager?.notify(alertId, builder.build())
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun disconnectIntent(): PendingIntent {
        // Action handled by MonitoringService — wired in Task 3
        val intent = Intent("com.volty.app.ACTION_DISCONNECT").setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
```

Note: `"%.1f".format(...)` on JVM via String → works. We're in androidMain so JVM-only formatters are fine.

Register in `AndroidModule.kt`:
```kotlin
single { Notifier(androidContext()) }
```

Add `import androidx.core.app.NotificationCompat` dep if not transitively pulled in — `androidx.core:core-ktx` (via `activity-compose`) provides it.

Build + commit `feat(notification): AndroidNotifier with live + alert channels`.

---

## Task 3: MonitoringService + ServiceController expect/actual

**Files:**
- `composeApp/src/commonMain/kotlin/com/volty/app/service/ServiceController.kt` (expect)
- `composeApp/src/androidMain/kotlin/com/volty/app/service/ServiceController.android.kt` (actual)
- `composeApp/src/androidMain/kotlin/com/volty/app/service/MonitoringService.kt`
- Modify: `AndroidManifest.xml` (register service)
- Modify: `composeApp/src/commonMain/kotlin/com/volty/app/data/ble/KableBmsRepository.kt` (start/stop service)

### `ServiceController.kt` (commonMain expect)

```kotlin
package com.volty.app.service

expect class ServiceController {
    fun start()
    fun stop()
}
```

### `ServiceController.android.kt` (androidMain actual)

```kotlin
package com.volty.app.service

import android.content.Context
import android.content.Intent
import android.os.Build

actual class ServiceController(private val context: Context) {
    actual fun start() {
        val intent = Intent(context, MonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }
    actual fun stop() {
        context.stopService(Intent(context, MonitoringService::class.java))
    }
}
```

### `MonitoringService.kt`

```kotlin
package com.volty.app.service

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.volty.app.MainActivity
import com.volty.app.domain.repository.BmsRepository
import com.volty.app.notification.LiveSummary
import com.volty.app.notification.NotificationChannels
import com.volty.app.notification.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import kotlin.time.Duration.Companion.seconds

class MonitoringService : Service() {

    companion object {
        private const val FOREGROUND_ID = 1001
        const val ACTION_DISCONNECT = "com.volty.app.ACTION_DISCONNECT"
    }

    private val bmsRepository: BmsRepository by inject()
    private val notifier: Notifier by inject()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch {
                bmsRepository.disconnect()
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
        registerReceiver(disconnectReceiver, IntentFilter(ACTION_DISCONNECT), Context.RECEIVER_NOT_EXPORTED)
        val seed = NotificationCompat.Builder(this, NotificationChannels.LIVE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Volty")
            .setContentText("Starting…")
            .setOngoing(true)
            .build()
        startForeground(FOREGROUND_ID, seed)

        scope.launch {
            bmsRepository.activeData
                .combine(bmsRepository.activeVehicle) { d, v -> d to v }
                .sample(2.seconds)
                .collect { (data, vehicle) ->
                    if (vehicle == null) return@collect
                    val sign = if (data.current >= 0) "+" else ""
                    val summary = LiveSummary(
                        vehicleName = vehicle.name,
                        socPercent = data.soc.toInt(),
                        voltageV = data.voltage,
                        currentA = data.current,
                        etaText = null   // TODO: pass eta from repository in Plan 4 follow-up
                    )
                    notifier.showLive(summary)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        try { unregisterReceiver(disconnectReceiver) } catch (_: Exception) {}
        notifier.cancelLive()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

### `AndroidManifest.xml` update

Add inside `<application>`:
```xml
<service
    android:name=".service.MonitoringService"
    android:foregroundServiceType="connectedDevice"
    android:exported="false" />
```

Add to permissions block at top:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
```

### Wire into `KableBmsRepository.kt`

Inject `ServiceController` via constructor:
```kotlin
class KableBmsRepository(
    private val vehicleRepository: VehicleRepository,
    private val serviceController: ServiceController
) : BmsRepository {
```

In `doConnect()` after `_connectionState.value = ConnectionState.Connected(vehicle)`:
```kotlin
serviceController.start()
```

In `disconnect()` (after cancelling jobs and disconnecting peripheral):
```kotlin
serviceController.stop()
```

Register in `AndroidModule.kt`:
```kotlin
single { ServiceController(androidContext()) }
```

Update `AppModule.kt`: `KableBmsRepository` already binds — Koin will auto-inject the new param (it's another `single`).

Build + commit `feat(service): MonitoringService with sticky notification + Disconnect broadcast`.

---

## Task 4: AlertEngine + tests

**Files:**
- `composeApp/src/commonMain/kotlin/com/volty/app/domain/usecase/AlertEngine.kt`
- `composeApp/src/commonTest/kotlin/com/volty/app/domain/usecase/AlertEngineTest.kt`

### `AlertEngine.kt`

```kotlin
package com.volty.app.domain.usecase

import com.volty.app.domain.model.BmsData
import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.repository.BmsRepository
import com.volty.app.notification.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class AlertEngine(
    private val bmsRepository: BmsRepository,
    private val notifier: Notifier,
    private val clock: () -> Instant = { Clock.System.now() }
) {

    // Per-(vehicleId, alertKind) last-fired timestamp for debounce
    private val lastFired = mutableMapOf<Pair<String, AlertKind>, Instant>()
    // Per-(vehicleId, alertKind) "armed" — true means we can fire (false = waiting for hysteresis recovery)
    private val armed = mutableMapOf<Pair<String, AlertKind>, Boolean>()

    private val debounce = 3.seconds
    private var alertCounter = 100

    fun start(scope: CoroutineScope) {
        scope.launch {
            bmsRepository.activeData
                .combine(bmsRepository.activeVehicle) { d, v -> d to v }
                .distinctUntilChanged()
                .collect { (data, vehicle) -> evaluate(data, vehicle) }
        }
    }

    private fun evaluate(data: BmsData, vehicle: Vehicle?) {
        if (vehicle == null || !data.isConnected) return
        val cfg = resolveAlertConfig(vehicle.alertConfig, vehicle.chemistry)
        val now = clock()

        val maxCell = data.cellVoltages.maxOrNull() ?: 0f
        val minCell = data.cellVoltages.minOrNull() ?: 0f
        val deltaMv = ((maxCell - minCell) * 1000f).toInt()
        val maxTemp = data.temperatures.maxOrNull() ?: 0f

        fire(AlertKind.CELL_HIGH, vehicle, now, maxCell > cfg.cellHighV, maxCell < cfg.cellHighV - 0.05f,
            AlertSeverity.CRITICAL, "Cell voltage high", "Max cell ${"%.2f".format(maxCell)} V on ${vehicle.name}")

        fire(AlertKind.CELL_LOW, vehicle, now, minCell < cfg.cellLowV && minCell > 0.1f, minCell > cfg.cellLowV + 0.05f,
            AlertSeverity.CRITICAL, "Cell voltage low", "Min cell ${"%.2f".format(minCell)} V on ${vehicle.name}")

        fire(AlertKind.CELL_DELTA, vehicle, now, deltaMv > cfg.cellDeltaMv, deltaMv < cfg.cellDeltaMv - 30,
            AlertSeverity.WARNING, "Cell imbalance", "Δ ${deltaMv} mV on ${vehicle.name}")

        fire(AlertKind.TEMPERATURE_HIGH, vehicle, now, maxTemp > cfg.temperatureHighC, maxTemp < cfg.temperatureHighC - 3f,
            AlertSeverity.CRITICAL, "Temperature high", "${"%.0f".format(maxTemp)}°C on ${vehicle.name}")

        fire(AlertKind.SOC_LOW, vehicle, now, data.soc.toInt() < cfg.socLowPercent, data.soc.toInt() > cfg.socLowPercent + 3,
            AlertSeverity.WARNING, "Battery low", "${data.soc.toInt()}% on ${vehicle.name}")

        cfg.socCutoffPercent?.let { cutoff ->
            fire(AlertKind.SOC_CUTOFF, vehicle, now, data.soc.toInt() < cutoff, data.soc.toInt() > cutoff + 2,
                AlertSeverity.CRITICAL, "Discharge cutoff", "${data.soc.toInt()}% — stop now (${vehicle.name})")
        }

        val isFull = data.soc >= 99.9f && abs(data.current) < 0.1f
        if (cfg.chargeCompleteNotify) {
            fire(AlertKind.CHARGE_COMPLETE, vehicle, now, isFull, data.soc < 98f,
                AlertSeverity.INFO, "Charge complete", "${vehicle.name} reached 100%")
        }
    }

    private fun fire(
        kind: AlertKind, vehicle: Vehicle, now: Instant,
        triggered: Boolean, recovered: Boolean,
        severity: AlertSeverity, title: String, text: String
    ) {
        val key = vehicle.id to kind
        val isArmed = armed.getOrPut(key) { true }
        if (recovered && !isArmed) armed[key] = true
        if (!triggered || !isArmed) return
        val last = lastFired[key]
        if (last != null && (now - last) < debounce) return
        lastFired[key] = now
        armed[key] = false
        notifier.showAlert(title, text, severity, alertCounter++)
    }
}
```

### `AlertEngineTest.kt`

```kotlin
package com.volty.app.domain.usecase

import com.volty.app.domain.model.AlertConfig
import com.volty.app.domain.model.BmsData
import com.volty.app.domain.model.BmsType
import com.volty.app.domain.model.Chemistry
import com.volty.app.domain.model.ConnectionState
import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.repository.BmsRepository
import com.volty.app.domain.repository.DiscoveredDevice
import com.volty.app.data.stats.MovingAvg
import com.volty.app.notification.LiveSummary
import com.volty.app.notification.Notifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class AlertEngineTest {

    private class FakeNotifier : Notifier(/* no context — won't be called for tests */) {
        // BUT: Notifier on commonMain is an `expect class`; can't subclass directly. Approach: use a small interface in tests or skip.
    }

    // Because Notifier is expect, we test via a small adapter. Refactor approach:
    // Define a `NotifierGateway` interface that wraps Notifier; AlertEngine takes the gateway.
    // For now, this is a placeholder test outline — actual implementation should refactor as needed.

    @Test
    fun `triggers cell-high alert when max cell exceeds threshold`() = runTest {
        // Test placeholder — see implementation notes below
    }
}
```

**Note for the implementer:** `Notifier` is an `expect class` so it can't be subclassed in commonTest. Two options:

A. **Refactor:** Make `Notifier` an `interface` in commonMain instead of `expect class`. The Android actual becomes `AndroidNotifier(context) : Notifier { ... }`. Then tests can use a `FakeNotifier : Notifier`. This is the cleaner option — apply it. Adjust DI to bind interface → AndroidNotifier on Android.

B. Keep `expect class` and skip AlertEngine unit testing in plan 4. Defer to integration testing.

Go with **A**. Refactor `Notifier` to an interface, with `AndroidNotifier` as the Android implementation. Adjust the AndroidModule binding.

After the refactor, the AlertEngine test should:
- Create a `recordingNotifier: TestNotifier` that captures `showAlert(...)` calls into a list.
- Create a `FakeBmsRepository` that exposes `MutableStateFlow<BmsData>` and `MutableStateFlow<Vehicle?>` as its `activeData` and `activeVehicle`.
- Initialize `AlertEngine(FakeBmsRepository, recordingNotifier, clock = { fakeNow })`.
- Call `start(TestScope())`.
- Push BmsData states; assert the right alerts fire in the right order.

Cover at least:
- Cell V high triggers CRITICAL.
- Below-threshold-with-hysteresis re-arms.
- Debounce: same trigger fired twice in 1 s only produces one alert.
- Charge complete fires when SOC reaches 100 and current ≈ 0.
- Recovered (SOC > cutoff + 3) re-arms SOC_LOW.

Build + tests + commit `feat(alerts): AlertEngine with debounce + hysteresis + tests`.

---

## Task 5: Wire AlertEngine to Koin + start on app launch

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/volty/app/di/AppModule.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/volty/app/VoltyApplication.kt`

In `AppModule.kt`:
```kotlin
single { AlertEngine(get(), get()) }
```

In `VoltyApplication.onCreate()` after `startKoin {...}`:
```kotlin
val alertEngine: AlertEngine = getKoin().get()
val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
alertEngine.start(appScope)
```

Use `org.koin.core.context.GlobalContext.get().get<AlertEngine>()` or `KoinApplication.koin.get<...>()`.

Build + tests + commit `feat(alerts): wire AlertEngine to Koin and start at app launch`.

---

## Task 6: Plan 4 smoke test

Create `docs/qa/plan-4-smoke-test.md`:

1. Connect to a BMS. Confirm Dashboard updates.
2. Press Home → app backgrounds. Persistent notification appears: "Volty · {vehicle} · 78% · 42.1 V · -4.2 A".
3. Notification updates at most once every 2 s.
4. Tap "Disconnect" action → connection drops, notification clears, app state goes to Scanning.
5. Re-connect. Force a CRITICAL: in Settings, lower the cell-high threshold below the current max cell. Reconnect. Within ~5 s, a CRITICAL notification appears with sound/vibration.
6. Wait 30 s. Same trigger fires at most once until the cell drops below threshold-hysteresis.
7. Restore threshold. Wait for "recovered". Re-trigger. New alert fires.
8. Disable charge complete in Settings — confirm no charge-complete notification fires.

Commit `docs: plan 4 smoke-test checklist`.

---

## Definition of done

- App connects to BMS and immediately starts foreground service with persistent notification.
- Notification updates with live SOC / V / A every 2 s.
- "Disconnect" action in notification works.
- Closing or backgrounding the app does NOT drop the connection.
- AlertEngine listens to BmsData and fires alerts per chemistry-derived thresholds.
- Alert channels CRITICAL / WARNING / INFO with appropriate priority.
- Debounce (3 s) + hysteresis (≈ threshold ± 3%) prevents notification storms.
- 5+ AlertEngine unit tests pass alongside existing 58 tests.
