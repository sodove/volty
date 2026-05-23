# Volty Plan 2 — Persistence + Connection UX

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `InMemoryVehicleRepository` with SQLDelight-backed persistence, add `DataStore` for app preferences, build the proper connection-flow UI (`Welcome` → `PermissionsGate` → `Scanning` → `AutoConnect` / `Picker` → `Dashboard`), and add the `VehicleEdit` screen. Also clean up follow-ups from Plan 1's final review.

**Architecture:** SQLDelight for vehicles table. Decompose `StackNavigation<Config>` in `RootComponent` replaces direct `debug` access. The existing `DebugScreen` becomes the temporary "Dashboard" stub until Plan 3 replaces it.

**Tech Stack:** Adds SQLDelight 2.2.1, AndroidX DataStore Preferences (latest stable). Decompose StackNavigation already pulled in via 3.4.0.

**Spec:** [`docs/superpowers/specs/2026-05-23-volty-design.md`](../specs/2026-05-23-volty-design.md)
**Builds on:** [Plan 1 — Foundation](2026-05-23-volty-plan-1-foundation.md)

---

## File map (new + modified)

```
volty/
├── gradle/libs.versions.toml                    (modify — add sqldelight, datastore)
├── composeApp/build.gradle.kts                  (modify — apply sqldelight plugin, add deps)
├── composeApp/src/
│   ├── androidMain/
│   │   ├── kotlin/com/volty/app/
│   │   │   ├── di/AndroidModule.kt              (modify — register AndroidSqlDriverFactory, AndroidDataStoreFactory)
│   │   │   ├── data/db/AndroidSqlDriverFactory.kt           (new)
│   │   │   ├── data/prefs/AndroidDataStoreFactory.kt        (new)
│   │   │   └── (existing files)
│   │   └── AndroidManifest.xml                  (no change yet)
│   ├── commonMain/
│   │   ├── kotlin/com/volty/app/
│   │   │   ├── data/db/
│   │   │   │   ├── SqlDriverFactory.kt          (new — expect)
│   │   │   │   ├── VoltyDatabaseProvider.kt     (new — wraps generated VoltyDatabase)
│   │   │   │   └── SqlDelightVehicleRepository.kt (new)
│   │   │   ├── data/prefs/
│   │   │   │   ├── DataStoreFactory.kt          (new — expect)
│   │   │   │   └── AppPrefs.kt                  (new)
│   │   │   ├── data/bms/
│   │   │   │   └── JbdBmsProtocol.kt            (modify — fix end-of-frame detection per Plan 1 review item 4)
│   │   │   ├── data/memory/SampleRingBuffer.kt  (modify — Mutex instead of @Synchronized per Plan 1 review item 2)
│   │   │   ├── data/ble/KableBmsRepository.kt   (modify — accept external scope, add closeable per item 3)
│   │   │   ├── di/AppModule.kt                  (modify — swap InMemoryVehicleRepository → SqlDelightVehicleRepository, register AppPrefs)
│   │   │   ├── presentation/
│   │   │   │   ├── root/RootComponent.kt        (modify — StackNavigation<Config>)
│   │   │   │   ├── root/RootScreen.kt           (new — Children composable that hosts active child)
│   │   │   │   ├── welcome/WelcomeComponent.kt          (new)
│   │   │   │   ├── welcome/WelcomeScreen.kt             (new)
│   │   │   │   ├── permissions/PermissionsGateComponent.kt (new)
│   │   │   │   ├── permissions/PermissionsGateScreen.kt    (new)
│   │   │   │   ├── scanning/ScanningComponent.kt        (new)
│   │   │   │   ├── scanning/ScanningScreen.kt           (new)
│   │   │   │   ├── autoconnect/AutoConnectComponent.kt  (new)
│   │   │   │   ├── autoconnect/AutoConnectScreen.kt     (new)
│   │   │   │   ├── picker/PickerComponent.kt            (new)
│   │   │   │   ├── picker/PickerScreen.kt               (new)
│   │   │   │   ├── vehicle/VehicleEditComponent.kt      (new)
│   │   │   │   └── vehicle/VehicleEditScreen.kt         (new)
│   ├── commonTest/
│   │   └── kotlin/com/volty/app/data/
│   │       ├── db/SqlDelightVehicleRepositoryTest.kt    (new — JVM, uses in-memory sqlite driver)
│   │       └── (existing tests stay)
└── composeApp/sqldelight/
    └── com/volty/app/data/db/
        ├── VoltyDatabase.sq                     (new — schema)
        └── VehicleQueries.sq                    (new — CRUD)
```

---

## Task 1: SQLDelight + DataStore gradle setup

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `composeApp/build.gradle.kts`

- [ ] Add to `[versions]`: `sqldelight = "2.2.1"`, `datastore = "1.1.7"`.
- [ ] Add to `[libraries]`:
  - `sqldelight-android = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }`
  - `sqldelight-coroutines = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }`
  - `sqldelight-runtime = { module = "app.cash.sqldelight:runtime", version.ref = "sqldelight" }`
  - `sqldelight-jvm = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sqldelight" }`  (test driver)
  - `datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }`
- [ ] Add to `[plugins]`: `sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }`.
- [ ] In `composeApp/build.gradle.kts`:
  - Add `alias(libs.plugins.sqldelight)` to plugins block.
  - Add a `sqldelight` block at the bottom:
    ```kotlin
    sqldelight {
        databases {
            create("VoltyDatabase") {
                packageName.set("com.volty.app.data.db")
                generateAsync.set(false)
            }
        }
    }
    ```
  - Add to `commonMain.dependencies`: `implementation(libs.sqldelight.runtime)`, `implementation(libs.sqldelight.coroutines)`.
  - Add to `androidMain.dependencies`: `implementation(libs.sqldelight.android)`, `implementation(libs.datastore.preferences)`.
  - Add to `commonTest.dependencies`: `implementation(libs.sqldelight.jvm)` (in-memory driver for unit tests).
- [ ] `./gradlew :composeApp:build` — verify build still succeeds with no `VoltyDatabase` schema yet (the plugin should accept an empty schema folder).
- [ ] Commit: `chore(gradle): add SQLDelight 2.2.1 and DataStore`.

---

## Task 2: SQLDelight schema (vehicle table)

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/com/volty/app/data/db/Vehicle.sq`

- [ ] Create `composeApp/sqldelight/...` — wait, with `generateAsync=false` and default source set discovery, the path is `composeApp/src/commonMain/sqldelight/com/volty/app/data/db/Vehicle.sq`. Create that file:

```sql
-- Vehicle profile table. One row per saved BMS.
CREATE TABLE Vehicle (
    id                  TEXT NOT NULL PRIMARY KEY,
    name                TEXT NOT NULL,
    iconKey             TEXT NOT NULL,
    bmsType             TEXT NOT NULL,   -- enum: JK_BMS | JBD_BMS | ANT_BMS | DALY_BMS
    bmsAddress          TEXT NOT NULL,
    chemistry           TEXT NOT NULL,   -- enum: LI_ION_NMC | LIFEPO4 | LEAD_ACID
    cellCount           INTEGER,
    averagingWindowMin  INTEGER NOT NULL DEFAULT 5,
    -- AlertConfig denormalized columns
    cellHighV               REAL,
    cellLowV                REAL,
    cellDeltaMv             INTEGER,
    temperatureHighC        REAL,
    socLowPercent           INTEGER,
    socCutoffPercent        INTEGER,
    disconnectNotify        INTEGER NOT NULL DEFAULT 1,
    chargeCompleteNotify    INTEGER NOT NULL DEFAULT 1,
    -- Timestamps as ISO-8601 strings to avoid Long/Int conversions
    createdAt           TEXT NOT NULL,
    lastConnectedAt     TEXT,
    isPinned            INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX vehicle_pinned_recent ON Vehicle(isPinned DESC, lastConnectedAt DESC, createdAt DESC);

-- Queries
selectAll:
SELECT * FROM Vehicle ORDER BY isPinned DESC, COALESCE(lastConnectedAt, createdAt) DESC;

selectById:
SELECT * FROM Vehicle WHERE id = :id;

upsert:
INSERT OR REPLACE INTO Vehicle(
    id, name, iconKey, bmsType, bmsAddress, chemistry, cellCount, averagingWindowMin,
    cellHighV, cellLowV, cellDeltaMv, temperatureHighC, socLowPercent, socCutoffPercent,
    disconnectNotify, chargeCompleteNotify, createdAt, lastConnectedAt, isPinned
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

delete:
DELETE FROM Vehicle WHERE id = :id;

touch:
UPDATE Vehicle SET lastConnectedAt = :now WHERE id = :id;
```

- [ ] Run `./gradlew :composeApp:generateCommonMainVoltyDatabaseInterface` (or just `assembleDebug`) — SQLDelight generates `VoltyDatabase`, `VehicleQueries`, model class `Vehicle` (this collides with our domain `Vehicle` — handle in next task).
- [ ] If naming collides with `domain.model.Vehicle`, fully-qualify the generated table model where it's used. Or rename the SQLDelight `Vehicle` table to `VehicleRow` to avoid collision. **Recommended:** rename the table to `VehicleRow` in the .sq file — update all queries, then the generated class is `VehicleRow` and no collision.
- [ ] Commit: `feat(db): SQLDelight schema for Vehicle table`.

---

## Task 3: expect/actual SqlDriverFactory + AppPrefs DataStore

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/db/SqlDriverFactory.kt`
- Create: `composeApp/src/androidMain/kotlin/com/volty/app/data/db/AndroidSqlDriverFactory.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/db/VoltyDatabaseProvider.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/prefs/AppPrefs.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/prefs/DataStoreFactory.kt`
- Create: `composeApp/src/androidMain/kotlin/com/volty/app/data/prefs/AndroidDataStoreFactory.kt`

- [ ] `SqlDriverFactory.kt` (commonMain expect):

```kotlin
package com.volty.app.data.db

import app.cash.sqldelight.db.SqlDriver

expect class SqlDriverFactory {
    fun create(): SqlDriver
}
```

- [ ] `AndroidSqlDriverFactory.kt` (androidMain actual):

```kotlin
package com.volty.app.data.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class SqlDriverFactory(private val context: Context) {
    actual fun create(): SqlDriver =
        AndroidSqliteDriver(VoltyDatabase.Schema, context, "volty.db")
}
```

- [ ] `VoltyDatabaseProvider.kt` — wraps the generated database for DI:

```kotlin
package com.volty.app.data.db

import app.cash.sqldelight.db.SqlDriver

class VoltyDatabaseProvider(driver: SqlDriver) {
    val database: VoltyDatabase = VoltyDatabase(driver)
}
```

- [ ] `AppPrefs.kt` (commonMain):

```kotlin
package com.volty.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppPrefs(private val store: DataStore<Preferences>) {

    val lastVehicleId: Flow<String?> = store.data.map { it[Keys.LAST_VEHICLE_ID] }
    val themeMode: Flow<String> = store.data.map { it[Keys.THEME_MODE] ?: "system" }
    val dynamicColorEnabled: Flow<Boolean> = store.data.map { it[Keys.DYNAMIC_COLOR] ?: true }
    val firstLaunchDone: Flow<Boolean> = store.data.map { it[Keys.FIRST_LAUNCH_DONE] ?: false }
    val scanTimeoutSec: Flow<Int> = store.data.map { it[Keys.SCAN_TIMEOUT_SEC] ?: 5 }
    val autoConnectCountdownSec: Flow<Int> = store.data.map { it[Keys.AUTO_CONNECT_COUNTDOWN_SEC] ?: 3 }
    val guestModeShowSaved: Flow<Boolean> = store.data.map { it[Keys.GUEST_MODE_SHOW_SAVED] ?: true }

    suspend fun setLastVehicleId(id: String?) = store.edit { p ->
        if (id == null) p.remove(Keys.LAST_VEHICLE_ID) else p[Keys.LAST_VEHICLE_ID] = id
    }
    suspend fun setThemeMode(mode: String) = store.edit { it[Keys.THEME_MODE] = mode }
    suspend fun setDynamicColorEnabled(enabled: Boolean) = store.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setFirstLaunchDone() = store.edit { it[Keys.FIRST_LAUNCH_DONE] = true }
    suspend fun setScanTimeoutSec(sec: Int) = store.edit { it[Keys.SCAN_TIMEOUT_SEC] = sec }
    suspend fun setAutoConnectCountdownSec(sec: Int) = store.edit { it[Keys.AUTO_CONNECT_COUNTDOWN_SEC] = sec }
    suspend fun setGuestModeShowSaved(show: Boolean) = store.edit { it[Keys.GUEST_MODE_SHOW_SAVED] = show }

    private object Keys {
        val LAST_VEHICLE_ID = stringPreferencesKey("last_vehicle_id")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        val FIRST_LAUNCH_DONE = booleanPreferencesKey("first_launch_done")
        val SCAN_TIMEOUT_SEC = intPreferencesKey("scan_timeout_sec")
        val AUTO_CONNECT_COUNTDOWN_SEC = intPreferencesKey("auto_connect_countdown_sec")
        val GUEST_MODE_SHOW_SAVED = booleanPreferencesKey("guest_mode_show_saved")
    }
}
```

- [ ] `DataStoreFactory.kt` (commonMain expect):

```kotlin
package com.volty.app.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

expect class DataStoreFactory {
    fun create(): DataStore<Preferences>
}
```

- [ ] `AndroidDataStoreFactory.kt` (androidMain actual):

```kotlin
package com.volty.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

private val Context.appDataStore by preferencesDataStore(name = "volty_prefs")

actual class DataStoreFactory(private val context: Context) {
    actual fun create(): DataStore<Preferences> = context.appDataStore
}
```

- [ ] Compile-check. Commit: `feat(data): SqlDriverFactory + DataStore expect/actual + AppPrefs`.

---

## Task 4: SqlDelightVehicleRepository (with tests)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/db/SqlDelightVehicleRepository.kt`
- Create: `composeApp/src/commonTest/kotlin/com/volty/app/data/db/SqlDelightVehicleRepositoryTest.kt`

### Step 1: Write the failing tests

The test uses the SQLite JVM driver (`app.cash.sqldelight:sqlite-driver`) in-memory.

```kotlin
package com.volty.app.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.volty.app.domain.model.AlertConfig
import com.volty.app.domain.model.BmsType
import com.volty.app.domain.model.Chemistry
import com.volty.app.domain.model.Vehicle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepositoryTest {

    private fun newRepo(): SqlDelightVehicleRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VoltyDatabase.Schema.create(driver)
        return SqlDelightVehicleRepository(VoltyDatabaseProvider(driver))
    }

    private fun v(id: String, name: String = "test") = Vehicle(
        id = id,
        name = name,
        iconKey = "generic",
        bmsType = BmsType.JK_BMS,
        bmsAddress = "AA:BB:CC:DD:EE:FF",
        chemistry = Chemistry.LI_ION_NMC,
        alertConfig = AlertConfig(),
        createdAt = Clock.System.now()
    )

    @Test
    fun `empty repo emits empty list`() = runTest {
        val repo = newRepo()
        assertEquals(emptyList(), repo.vehicles.first())
    }

    @Test
    fun `upsert then get returns the same vehicle`() = runTest {
        val repo = newRepo()
        val source = v("id-1", "Stealth")
        repo.upsert(source)
        val got = repo.get("id-1")
        assertNotNull(got)
        assertEquals("Stealth", got.name)
        assertEquals(BmsType.JK_BMS, got.bmsType)
        assertEquals(Chemistry.LI_ION_NMC, got.chemistry)
    }

    @Test
    fun `upsert replaces existing row`() = runTest {
        val repo = newRepo()
        repo.upsert(v("id-1", "A"))
        repo.upsert(v("id-1", "B"))
        val all = repo.vehicles.first()
        assertEquals(1, all.size)
        assertEquals("B", all[0].name)
    }

    @Test
    fun `delete removes vehicle`() = runTest {
        val repo = newRepo()
        repo.upsert(v("id-1"))
        repo.delete("id-1")
        assertNull(repo.get("id-1"))
    }

    @Test
    fun `touch sets lastConnectedAt to now`() = runTest {
        val repo = newRepo()
        repo.upsert(v("id-1"))
        repo.touch("id-1")
        val got = repo.get("id-1")
        assertNotNull(got?.lastConnectedAt)
    }

    @Test
    fun `vehicles ordered by pinned then lastConnectedAt then createdAt`() = runTest {
        val repo = newRepo()
        repo.upsert(v("a", "Older").copy(createdAt = Clock.System.now()))
        repo.upsert(v("b", "Newer").copy(createdAt = Clock.System.now()))
        repo.touch("a") // a becomes most-recently-connected
        val names = repo.vehicles.first().map { it.name }
        assertEquals(listOf("Older", "Newer"), names)
    }
}
```

### Step 2: Run → FAIL.

### Step 3: Implement `SqlDelightVehicleRepository.kt`

```kotlin
package com.volty.app.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.volty.app.domain.model.AlertConfig
import com.volty.app.domain.model.BmsType
import com.volty.app.domain.model.Chemistry
import com.volty.app.domain.model.Vehicle as DomainVehicle
import com.volty.app.domain.repository.VehicleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepository(provider: VoltyDatabaseProvider) : VehicleRepository {

    private val queries = provider.database.vehicleQueries

    override val vehicles: Flow<List<DomainVehicle>> = queries.selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)
        .map { rows -> rows.map { it.toDomain() } }
        .flowOn(Dispatchers.Default)

    override suspend fun get(id: String): DomainVehicle? =
        queries.selectById(id).executeAsOneOrNull()?.toDomain()

    override suspend fun upsert(vehicle: DomainVehicle) {
        val a = vehicle.alertConfig
        queries.upsert(
            id = vehicle.id,
            name = vehicle.name,
            iconKey = vehicle.iconKey,
            bmsType = vehicle.bmsType.name,
            bmsAddress = vehicle.bmsAddress,
            chemistry = vehicle.chemistry.name,
            cellCount = vehicle.cellCount?.toLong(),
            averagingWindowMin = vehicle.averagingWindowMin.toLong(),
            cellHighV = a.cellHighV?.toDouble(),
            cellLowV = a.cellLowV?.toDouble(),
            cellDeltaMv = a.cellDeltaMv?.toLong(),
            temperatureHighC = a.temperatureHighC?.toDouble(),
            socLowPercent = a.socLowPercent?.toLong(),
            socCutoffPercent = a.socCutoffPercent?.toLong(),
            disconnectNotify = if (a.disconnectNotify) 1L else 0L,
            chargeCompleteNotify = if (a.chargeCompleteNotify) 1L else 0L,
            createdAt = vehicle.createdAt.toString(),
            lastConnectedAt = vehicle.lastConnectedAt?.toString(),
            isPinned = if (vehicle.isPinned) 1L else 0L
        )
    }

    override suspend fun delete(id: String) { queries.delete(id) }

    override suspend fun touch(id: String) { queries.touch(Clock.System.now().toString(), id) }
}

// Conversion helpers
@OptIn(ExperimentalTime::class)
private fun com.volty.app.data.db.VehicleRow.toDomain(): DomainVehicle = DomainVehicle(
    id = id,
    name = name,
    iconKey = iconKey,
    bmsType = BmsType.valueOf(bmsType),
    bmsAddress = bmsAddress,
    chemistry = Chemistry.valueOf(chemistry),
    cellCount = cellCount?.toInt(),
    averagingWindowMin = averagingWindowMin.toInt(),
    alertConfig = AlertConfig(
        cellHighV = cellHighV?.toFloat(),
        cellLowV = cellLowV?.toFloat(),
        cellDeltaMv = cellDeltaMv?.toInt(),
        temperatureHighC = temperatureHighC?.toFloat(),
        socLowPercent = socLowPercent?.toInt(),
        socCutoffPercent = socCutoffPercent?.toInt(),
        disconnectNotify = disconnectNotify == 1L,
        chargeCompleteNotify = chargeCompleteNotify == 1L
    ),
    createdAt = Instant.parse(createdAt),
    lastConnectedAt = lastConnectedAt?.let { Instant.parse(it) },
    isPinned = isPinned == 1L
)
```

Note: if you renamed the table `Vehicle` → `VehicleRow` in Task 2, adjust the import accordingly.

### Step 4: Tests PASS

```bash
cd /c/Users/sodovaya/Desktop/volty && ./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.db.SqlDelightVehicleRepositoryTest"
```

### Step 5: Commit `feat(db): SqlDelightVehicleRepository with persistence tests`.

---

## Task 5: Wire SqlDelight + AppPrefs into Koin

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/volty/app/di/AppModule.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/volty/app/di/AndroidModule.kt`

- [ ] In `androidModule`: register `single { SqlDriverFactory(androidContext()) }`, `single { DataStoreFactory(androidContext()) }`.
- [ ] In `appModule`:
  - Add: `single { VoltyDatabaseProvider(get<SqlDriverFactory>().create()) }`
  - Add: `single { AppPrefs(get<DataStoreFactory>().create()) }`
  - Replace `singleOf(::InMemoryVehicleRepository) bind VehicleRepository::class` with `singleOf(::SqlDelightVehicleRepository) bind VehicleRepository::class`.
- [ ] Delete `composeApp/src/commonMain/kotlin/com/volty/app/data/memory/InMemoryVehicleRepository.kt` (no longer used).
- [ ] Build, app launches.
- [ ] Commit `feat(di): wire SqlDelight + AppPrefs, drop InMemoryVehicleRepository`.

---

## Task 6: Plan 1 review item fixes (3 items)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/volty/app/data/memory/SampleRingBuffer.kt` (Mutex)
- Modify: `composeApp/src/commonMain/kotlin/com/volty/app/data/ble/KableBmsRepository.kt` (closeable scope)
- Modify: `composeApp/src/commonMain/kotlin/com/volty/app/data/bms/JbdBmsProtocol.kt` (frame end by `len` not last 0x77)

### 6a. `SampleRingBuffer` Mutex

Replace `@Synchronized` with `Mutex` from `kotlinx.coroutines.sync.Mutex`. Convert `push`, `within`, `clear` to `suspend` and wrap bodies in `mutex.withLock { ... }`. Update tests accordingly (use `runTest` and `runBlocking` for the assertions).

If converting to suspend ripples too widely (e.g. `KableBmsRepository.samples` uses it synchronously), keep it non-suspend by using a non-blocking lock pattern: `synchronized(lock) { ... }` with a `private val lock = Any()` — that compiles on all KMP targets too (it's a stdlib intrinsic). **Recommended:** use `kotlin.concurrent.AtomicReference` for the deque snapshot, OR just go with `synchronized(this)` since Volty is Android-only for now.

For simplicity and minimal churn: replace `@Synchronized` annotation with `synchronized(this) { ... }` blocks. This is identical bytecode but doesn't carry the JVM-only annotation semantically.

### 6b. `KableBmsRepository` scope teardown

Add a public `fun close()` that calls `scope.cancel()`. Register `KableBmsRepository` in Koin with `onClose { it.close() }` (Koin 4 supports this). Or expose `Closeable` interface.

### 6c. `JbdBmsProtocol` frame end via `len` field

In `tryParseAll()`, after finding the start `0xDD`, read `frame[3]` as the data length, compute `expectedFrameLen = 4 + dataLen + 3` (header + cmd + flag + len + data + csum_hi + csum_lo + end), then check `current.size >= expectedFrameLen` and verify `current[expectedFrameLen - 1] == 0x77`. If not, advance past this start byte (`trimLeading(1)`) and keep scanning. Drop the "find last 0x77 in buffer" hack.

Add a unit test in `JbdBmsProtocolTest` that proves the new logic handles a payload byte equal to `0x77` correctly.

- [ ] Run full test suite: 50+1 tests now (+ JBD payload-with-0x77 test). All PASS.
- [ ] Commit: `fix(plan-1-review): Mutex/synchronized cleanup, scope teardown, JBD frame length`.

---

## Task 7: Decompose StackNavigation in RootComponent

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/root/RootComponent.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/root/RootScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/volty/app/App.kt`

Replace the current "root has one child" hardcoded structure with `StackNavigation<Config>`. Configs (sealed class):

- `Welcome`
- `PermissionsGate`
- `Scanning`
- `AutoConnect(vehicleId: String)`  (use ID, not the full Vehicle, for `@Parcelable`-style serialization to survive process death)
- `Picker(mode: String)` — mode = "cold" | "add" | "guest"
- `Dashboard` (uses existing `DebugScreen` for now — Plan 3 replaces it)
- `VehicleEdit(vehicleId: String?)`  (null = new)

Initial config based on:
- If saved vehicles empty → `Welcome`
- Else if permissions missing → `PermissionsGate`
- Else → `Scanning`

`RootScreen.kt` uses `ChildStack` composable to render the active child. Each child component is created in `RootComponent` via factory methods passed to `StackNavigation.childContext(...)`.

Read Decompose 3.4 docs for the exact API: `childStack(source = stackNavigation, serializer = Config.serializer(), initialConfiguration = ...)`. Plus `kotlinx.serialization` Config — enable serialization plugin on the module if not already (it already is per Plan 1 Task 2).

- [ ] Implement skeleton stack navigation with only `Dashboard` config wired up (other configs are TODO routes that just print "Not implemented"). Verify navigation works (push/pop). Commit `feat(nav): Decompose StackNavigation skeleton`.

---

## Task 8: WelcomeComponent + Screen

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/welcome/WelcomeComponent.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/welcome/WelcomeScreen.kt`

State: no state. Just two buttons.

Events:
- `onAddBattery()` — `RootComponent` pops to `PermissionsGate` → eventually `Picker(mode="add")`.
- `onQuickConnect()` — pops to `Picker(mode="guest")`.

Screen shows:
- Volty logo placeholder (gradient block 80×80 dp).
- "Welcome to Volty" title.
- Subtitle: "Monitor your batteries via Bluetooth. Pick one to start."
- Primary button: "+ Add my battery".
- Secondary button: "⚡ Quick connect (guest)".
- Buttons centered.

- [ ] Wire into `RootComponent` so when `vehicles.isEmpty()` at startup, `Welcome` is the initial config.
- [ ] Commit `feat(ui): WelcomeScreen`.

---

## Task 9: PermissionsGate (BLE + notifications)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/permissions/PermissionsGateComponent.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/permissions/PermissionsGateScreen.kt`
- Create: `composeApp/src/androidMain/kotlin/com/volty/app/permissions/PermissionsChecker.kt` (expect actual)
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/permissions/PermissionsChecker.kt` (expect)

`PermissionsChecker` is an `expect class` with `fun missingPermissions(): List<String>` and `suspend fun request(permissions: List<String>): Map<String, Boolean>`. On Android it wraps `ContextCompat.checkSelfPermission` and uses `androidx.activity.compose.rememberLauncherForActivityResult` — actually that lives in the screen layer. Simpler: pass the result via an Activity callback.

For Plan 2's MVP: make the gate **manual** — show a screen that says "Grant Bluetooth + Location permissions to scan for batteries" with an "Open Settings" button. The actual permission request flow can be a `rememberLauncherForActivityResult` invocation initiated by `PermissionsGateScreen` (a UI-side concern). Component just tracks `permissionsGranted: Boolean`.

Required Android permissions (already in manifest, see Plan 1):
- `BLUETOOTH_SCAN` (API 31+)
- `BLUETOOTH_CONNECT` (API 31+)
- `ACCESS_FINE_LOCATION` (API ≤ 30 only)
- `POST_NOTIFICATIONS` (API 33+) — for alerts in Plan 4. Still ask now so Plan 4 can use it.

On grant → component calls `onAllGranted()` → `RootComponent` navigates to `Scanning`.

- [ ] Commit `feat(ui): PermissionsGate screen`.

---

## Task 10: ScanningComponent + Screen

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/scanning/ScanningComponent.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/scanning/ScanningScreen.kt`

State:
- `scanningSec: Int` (countdown 5→0)
- `knownInRange: Set<Vehicle>`
- `otherFound: List<DiscoveredDevice>`
- `phase: enum (Scanning, NoneFound, Cancelled)`

Behavior:
- On init: subscribe to `bmsRepository.scanAll()`. Tick countdown every second. If during scan ≥2 known found → cancel, navigate to `Picker(mode="cold")`. If timer hits 0 with 1 known → `AutoConnect(vehicle)`. If 0 known → `Picker(mode="cold")` (so user can see other-nearby).
- User can tap "Skip → device list" → navigate to `Picker(mode="cold")` regardless.

Screen:
- Centered pulsing icon (animated, ~90×90 dp circle scaling)
- "Looking for your batteries" title
- Subtitle: "Make sure your BMS is on and in range."
- Below: "Scanning for N saved batteries… M·Ns left" — N = saved count, M = countdown
- "Skip → device list" button

- [ ] Commit `feat(ui): ScanningScreen with auto-pickup logic`.

---

## Task 11: AutoConnectComponent + Screen (3-sec countdown)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/autoconnect/AutoConnectComponent.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/autoconnect/AutoConnectScreen.kt`

State:
- `vehicle: Vehicle`
- `countdownSec: Int` (3→0)
- `status: enum (Counting, Connecting, Connected, Failed(msg))`

Behavior:
- On init: countdown from `AppPrefs.autoConnectCountdownSec` (default 3). 1/s tick. On tick reach 0 OR user taps "Connect now" → start `bmsRepository.connect(vehicle)`. On success → navigate `Dashboard`. On fail → set `Failed(msg)` and show "Try again" button.
- User can tap "Cancel" any time → navigate to `Picker(mode="cold")`.

Screen:
- Rotating countdown ring around center number (110×110 dp), the number animates 3 → 2 → 1 → 0 then changes to a small spinner "Connecting…".
- "Stealth Board found" title (vehicle.name).
- Subtitle: "Auto-connecting in **3 seconds**." (changes per tick).
- Card with vehicle avatar + name + BMS type + RSSI (read RSSI from advertisementCache via repository if available).
- "Connect now" button.
- "Cancel" text button.

- [ ] Commit `feat(ui): AutoConnect with 3-sec cancellable countdown`.

---

## Task 12: PickerComponent + Screen

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/picker/PickerComponent.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/picker/PickerScreen.kt`

State:
- `mode: String` ("cold" | "add" | "guest")
- `myInRange: List<Vehicle>`
- `otherNearby: List<DiscoveredDevice>` (filtered: not in myInRange)

Behavior:
- On init: start scanning. Continuously update lists.
- Tap on `myInRange` row → `bmsRepository.connect(vehicle)`. On success → `Dashboard`. On fail → show snackbar.
- Tap on `otherNearby` row:
  - In `add` mode: `bmsRepository.connectGuest(d.address, d.bmsType)`. On success → navigate to `VehicleEdit(null)` with prefilled BMS info (pass via component state). After saving → return to `Dashboard`.
  - In `guest` mode: `bmsRepository.connectGuest(d.address, d.bmsType)`. On success → `Dashboard` directly (no save).
  - In `cold` mode: treat like `guest` mode? Or show a dialog "Save this battery to your list?"? Decide based on UX — for MVP, show the "Save" dialog after successful guest connect.

Screen:
- Top app bar with mode-appropriate title ("Pick a battery" / "Quick connect" / "Add new battery").
- Top section: "My batteries · in range" (only if mode != "add" and mode != "guest" — i.e. cold).
- Bottom section: "Other BMS nearby" (always).
- Each row: avatar / name / "BMS type · last seen now" / RSSI / tap action.
- "+ Add new battery" text button at bottom (if mode != "add").

- [ ] Commit `feat(ui): Picker screen for cold/add/guest modes`.

---

## Task 13: VehicleEditComponent + Screen

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/vehicle/VehicleEditComponent.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/vehicle/VehicleEditScreen.kt`

State:
- `vehicleId: String?` (null = create)
- `name: String`
- `iconKey: String` (default "generic")
- `chemistry: Chemistry`
- `bmsType: BmsType` (read-only in edit mode)
- `bmsAddress: String` (read-only in edit mode)
- `averagingWindowMin: Int` (1/5/10/30 chips)
- `cellCount: Int?` (optional)
- `alertConfig: AlertConfig`
- `loaded: Boolean`

Behavior:
- On init: if `vehicleId != null`, load from `VehicleRepository.get(...)`; else create blank with prefilled BMS info passed by Picker.
- On save:
  - Validation: name non-empty, averagingWindowMin in (1,5,10,30), chemistry set.
  - `vehicleRepository.upsert(...)`.
  - Navigate back (or to `Dashboard` if first create).
- On cancel: navigate back without saving.
- On delete (if not new): show confirm dialog → `vehicleRepository.delete(id)` → pop back.

Screen sections:
- TopAppBar with "Save" action.
- Name field.
- Icon picker (presets: skateboard, ebike, scooter, moto, solar, ev, boat, rv, generic — show as a row of icon buttons).
- BMS type + address (read-only display).
- Chemistry segmented control (3 options).
- Cell count (optional integer field).
- Averaging window chip group (1/5/10/30 min).
- Alert thresholds (expandable section — for MVP just show cell V high/low, temp high, SOC low as editable fields; rest can use defaults).
- Delete button (red, only if editing).

- [ ] Commit `feat(ui): VehicleEdit create/edit profile`.

---

## Task 14: Wire end-to-end navigation

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/root/RootComponent.kt`

Update the StackNavigation initial-route logic:
- App start: if `vehicles.isEmpty()` → `Welcome`; else if missing permissions → `PermissionsGate`; else → `Scanning`.
- Welcome.onAddBattery → `PermissionsGate` (if needed) → `Picker(mode="add")`.
- Welcome.onQuickConnect → `PermissionsGate` (if needed) → `Picker(mode="guest")`.
- PermissionsGate.allGranted → respect previously-intended target (use saved intent in component state, or just go to `Scanning` if cold start).
- Scanning callbacks → `AutoConnect(vehicleId)` or `Picker(mode="cold")`.
- AutoConnect.connected → `Dashboard`. AutoConnect.cancelled → `Picker(mode="cold")`.
- Picker.connectedKnown → `Dashboard`. Picker.connectedGuestInAddMode → `VehicleEdit(null)` with prefilled BMS info.
- VehicleEdit.saved → `Dashboard` (or pop back if editing).
- VehicleEdit.deleted → pop back.

Test by exercising the flow on emulator: app starts → Welcome → tap Add → PermissionsGate → Picker(add) → tap an unknown BMS row → connects → VehicleEdit → fill name → Save → Dashboard.

- [ ] Commit `feat(nav): wire complete connection flow`.

---

## Task 15: Smoke test on real device

Manual verification — same as Plan 1 Task 18 but for the new flow.

Create `docs/qa/plan-2-smoke-test.md` with checklist:

1. Fresh install (clear app data first). App opens to `Welcome`.
2. Tap "+ Add my battery". Permissions gate appears (first run). Grant.
3. Picker(add) shows scanning. Power on a BMS. Tap its row. Connection succeeds → VehicleEdit appears with prefilled BMS info.
4. Set name "Test Bike", chemistry Li-ion, averaging 5 min. Save. → Dashboard (debug screen) shows live data.
5. Disconnect from Dashboard. Close app.
6. Power BMS off, reopen app. Scanning → NoneFound → Picker(cold) with the saved "Test Bike" greyed out.
7. Power BMS on. App finds it after a few seconds.
8. Close app, reopen — saved "Test Bike" loaded from SQLDelight (proves persistence).
9. AutoConnect: only one known BMS in range → AutoConnect screen with 3-sec countdown → connects → Dashboard.
10. Multiple known BMS in range → Picker(cold) with both visible → tap one → connects.
11. Quick connect (Welcome → guest) → Picker(guest) → tap any BMS → connects → Dashboard. After disconnect/reopen, this BMS is NOT in saved list.

- [ ] Commit `docs: plan-2 smoke-test checklist`.

---

## Definition of done for Plan 2

- All saved-vehicle data persists across app restarts (SQLDelight).
- `Welcome` shown on first launch with empty repo.
- `PermissionsGate` shown when missing BLE permissions.
- `Scanning` runs ≤ 5 s with auto-routing to `AutoConnect` (1 known), `Picker(cold)` (0 or 2+ known), or after "Skip" button.
- `AutoConnect` shows 3-sec cancellable countdown then connects.
- `Picker` differentiates "my batteries" from "other nearby".
- `VehicleEdit` creates and edits vehicle profiles.
- Three Plan-1-review-items resolved (Mutex/sync, scope teardown, JBD frame length).
- All Plan 1 unit tests still pass; SqlDelightVehicleRepositoryTest adds ≥6 new tests.
- Build green, APK installs and the full flow works on real device.
