# Volty Plan 1 — Foundation + BMS data layer

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a fresh KMP/Compose-Multiplatform Android app that can scan BLE for JK / JBD / ANT / Daly BMS, connect to one, parse its packets, and stream live `BmsData` to a debug screen. All four protocols ship with unit tests.

**Architecture:** Single `composeApp` module, KMP structure (`commonMain` + `androidMain`), Decompose for navigation, Koin for DI, Kable for BLE. Protocol parsers are ported from `kelly-connect` into Volty's package, tests are written fresh (Kelly has no BMS tests). UI is intentionally bare-bones (a debug screen) — full UI ships in plan 3.

**Tech Stack:** Kotlin 2.3.20, Compose Multiplatform 1.11.1 (Android-only target), Decompose 3.4.0, Koin 4.2.1, Kable 0.43.0, kotlinx-coroutines 1.10.2, kotlinx-datetime 0.6.1, kotlin-test.

**Spec:** [`docs/superpowers/specs/2026-05-23-volty-design.md`](../specs/2026-05-23-volty-design.md)

---

## File map

Created in this plan:

```
volty/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew, gradlew.bat
├── gradle/
│   ├── wrapper/gradle-wrapper.properties
│   ├── wrapper/gradle-wrapper.jar
│   └── libs.versions.toml
├── composeApp/
│   ├── build.gradle.kts
│   └── src/
│       ├── androidMain/
│       │   ├── AndroidManifest.xml
│       │   └── kotlin/com/volty/app/
│       │       ├── VoltyApplication.kt
│       │       ├── MainActivity.kt
│       │       └── di/AndroidModule.kt
│       ├── commonMain/
│       │   └── kotlin/com/volty/app/
│       │       ├── App.kt
│       │       ├── di/AppModule.kt
│       │       ├── domain/
│       │       │   ├── model/
│       │       │   │   ├── BmsData.kt
│       │       │   │   ├── BmsType.kt
│       │       │   │   ├── Chemistry.kt
│       │       │   │   ├── Vehicle.kt
│       │       │   │   ├── AlertConfig.kt
│       │       │   │   └── ConnectionState.kt
│       │       │   └── repository/
│       │       │       ├── BmsRepository.kt
│       │       │       └── VehicleRepository.kt
│       │       ├── data/
│       │       │   ├── bms/
│       │       │   │   ├── BmsProtocol.kt
│       │       │   │   ├── BmsUuids.kt
│       │       │   │   ├── ByteArrayAccumulator.kt
│       │       │   │   ├── CrcUtils.kt
│       │       │   │   ├── JkBmsProtocol.kt
│       │       │   │   ├── JbdBmsProtocol.kt
│       │       │   │   ├── AntBmsProtocol.kt
│       │       │   │   ├── DalyBmsProtocol.kt
│       │       │   │   └── BmsTypeDetector.kt
│       │       │   ├── ble/
│       │       │   │   └── KableBmsRepository.kt
│       │       │   ├── memory/
│       │       │   │   ├── SampleRingBuffer.kt
│       │       │   │   └── InMemoryVehicleRepository.kt
│       │       │   └── stats/
│       │       │       └── MovingAverage.kt
│       │       └── presentation/
│       │           ├── root/
│       │           │   ├── RootComponent.kt
│       │           │   └── RootScreen.kt
│       │           └── debug/
│       │               ├── DebugComponent.kt
│       │               └── DebugScreen.kt
│       └── commonTest/
│           └── kotlin/com/volty/app/data/
│               ├── bms/
│               │   ├── CrcUtilsTest.kt
│               │   ├── ByteArrayAccumulatorTest.kt
│               │   ├── JkBmsProtocolTest.kt
│               │   ├── JbdBmsProtocolTest.kt
│               │   ├── AntBmsProtocolTest.kt
│               │   ├── DalyBmsProtocolTest.kt
│               │   └── BmsTypeDetectorTest.kt
│               ├── memory/
│               │   └── SampleRingBufferTest.kt
│               └── stats/
│                   └── MovingAverageTest.kt
```

Out of scope for this plan: SQLDelight, DataStore, foreground service, AlertEngine, full UI screens, M3 Expressive theme.

---

## Task 1: Gradle wrapper and root build files

**Files:**
- Create: `volty/settings.gradle.kts`
- Create: `volty/build.gradle.kts`
- Create: `volty/gradle.properties`
- Create: `volty/gradle/libs.versions.toml`
- Create: `volty/gradle/wrapper/gradle-wrapper.properties`
- Create: `volty/gradlew`, `volty/gradlew.bat` (copy from Kelly)
- Create: `volty/gradle/wrapper/gradle-wrapper.jar` (copy from Kelly)

- [ ] **Step 1: Copy gradle wrapper from Kelly**

```bash
cp -r /c/Users/sodovaya/Desktop/kelly/kelly-connect/gradle/wrapper /c/Users/sodovaya/Desktop/volty/gradle/
cp /c/Users/sodovaya/Desktop/kelly/kelly-connect/gradlew /c/Users/sodovaya/Desktop/volty/
cp /c/Users/sodovaya/Desktop/kelly/kelly-connect/gradlew.bat /c/Users/sodovaya/Desktop/volty/
chmod +x /c/Users/sodovaya/Desktop/volty/gradlew
```

- [ ] **Step 2: Write version catalog**

Create `gradle/libs.versions.toml` with content:

```toml
[versions]
kotlin = "2.3.20"
agp = "8.10.0"
compose-multiplatform = "1.11.1"
decompose = "3.4.0"
koin = "4.2.1"
coroutines = "1.10.2"
kable = "0.43.0"
activity-compose = "1.12.3"
serialization = "1.10.0"
datetime = "0.6.1"
turbine = "1.2.0"

[libraries]
decompose-core = { module = "com.arkivanov.decompose:decompose", version.ref = "decompose" }
decompose-compose = { module = "com.arkivanov.decompose:extensions-compose", version.ref = "decompose" }
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
koin-compose = { module = "io.insert-koin:koin-compose", version.ref = "koin" }
koin-android = { module = "io.insert-koin:koin-android", version.ref = "koin" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity-compose" }
kable-core = { module = "com.juul.kable:kable-core", version.ref = "kable" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "datetime" }
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
android-application = { id = "com.android.application", version.ref = "agp" }
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "compose-multiplatform" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 3: Write root `settings.gradle.kts`**

```kotlin
rootProject.name = "volty"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":composeApp")
```

- [ ] **Step 4: Write root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

- [ ] **Step 5: Write `gradle.properties`**

```
org.gradle.jvmargs=-Xmx2048M -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
org.gradle.configuration-cache=true
```

- [ ] **Step 6: Verify wrapper**

Run: `./gradlew --version`
Expected: prints Gradle and JVM versions, no errors.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/ gradlew gradlew.bat
git commit -m "chore: gradle wrapper and version catalog"
```

---

## Task 2: composeApp module skeleton

**Files:**
- Create: `composeApp/build.gradle.kts`
- Create: `composeApp/src/androidMain/AndroidManifest.xml`
- Create: `composeApp/src/androidMain/kotlin/com/volty/app/VoltyApplication.kt`
- Create: `composeApp/src/androidMain/kotlin/com/volty/app/MainActivity.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/App.kt`

- [ ] **Step 1: Write `composeApp/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.decompose.core)
            implementation(libs.decompose.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.kable.core)
            implementation(libs.datetime)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation(libs.turbine)
        }

        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.koin.android)
            implementation(libs.coroutines.android)
        }
    }
}

android {
    namespace = "com.volty.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.volty.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
```

- [ ] **Step 2: Write `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation"
        tools:targetApi="31"
        xmlns:tools="http://schemas.android.com/tools" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="30" />

    <application
        android:name=".VoltyApplication"
        android:label="Volty"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 3: Write `VoltyApplication.kt`**

```kotlin
package com.volty.app

import android.app.Application
import com.volty.app.di.androidModule
import com.volty.app.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class VoltyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@VoltyApplication)
            modules(appModule, androidModule)
        }
    }
}
```

- [ ] **Step 4: Write `MainActivity.kt`**

```kotlin
package com.volty.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}
```

- [ ] **Step 5: Write placeholder `App.kt`**

```kotlin
package com.volty.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun App() {
    MaterialTheme {
        Surface { Text("Volty placeholder") }
    }
}
```

- [ ] **Step 6: Run build to verify skeleton**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL. APK at `composeApp/build/outputs/apk/debug/composeApp-debug.apk`.

Note: this will fail at Koin imports until Task 3 creates the modules. To unblock now, comment out the `startKoin { ... }` block in `VoltyApplication.kt`; uncomment in Task 3.

- [ ] **Step 7: Commit**

```bash
git add composeApp/
git commit -m "chore: composeApp skeleton compiles, placeholder App composable"
```

---

## Task 3: Empty Koin modules + wiring

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/di/AppModule.kt`
- Create: `composeApp/src/androidMain/kotlin/com/volty/app/di/AndroidModule.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/volty/app/VoltyApplication.kt` (uncomment startKoin)

- [ ] **Step 1: Write `AppModule.kt`**

```kotlin
package com.volty.app.di

import org.koin.dsl.module

val appModule = module {
    // Repositories and use cases added in subsequent tasks
}
```

- [ ] **Step 2: Write `AndroidModule.kt`**

```kotlin
package com.volty.app.di

import org.koin.dsl.module

val androidModule = module {
    // Android-specific bindings added in subsequent tasks
}
```

- [ ] **Step 3: Re-enable startKoin in `VoltyApplication.kt`** (uncomment block from Task 2 Step 3).

- [ ] **Step 4: Run build**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/
git commit -m "feat(di): empty Koin modules + application bootstrap"
```

---

## Task 4: Domain models

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/domain/model/BmsData.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/domain/model/BmsType.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/domain/model/Chemistry.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/domain/model/Vehicle.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/domain/model/AlertConfig.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/domain/model/ConnectionState.kt`

- [ ] **Step 1: Write `BmsData.kt`**

```kotlin
package com.volty.app.domain.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

data class BmsData(
    val voltage: Float = 0f,
    val current: Float = 0f,
    val power: Float = 0f,
    val soc: Float = 0f,
    val charge: Float = 0f,
    val capacity: Float = 0f,
    val numCycles: Int = 0,
    val cellVoltages: List<Float> = emptyList(),
    val temperatures: List<Float> = emptyList(),
    val chargeEnabled: Boolean = false,
    val dischargeEnabled: Boolean = false,
    val isConnected: Boolean = false,
    val timestamp: Instant = Clock.System.now()
)
```

- [ ] **Step 2: Write `BmsType.kt`**

```kotlin
package com.volty.app.domain.model

enum class BmsType(val label: String) {
    JK_BMS("JK BMS"),
    JBD_BMS("JBD BMS"),
    ANT_BMS("Ant BMS"),
    DALY_BMS("Daly BMS")
}
```

- [ ] **Step 3: Write `Chemistry.kt`**

```kotlin
package com.volty.app.domain.model

enum class Chemistry(
    val label: String,
    val nominalCellV: Float,
    val defaultHighV: Float,
    val defaultLowV: Float
) {
    LI_ION_NMC("Li-ion (NMC)", nominalCellV = 3.7f, defaultHighV = 4.20f, defaultLowV = 2.80f),
    LIFEPO4("LiFePO4", nominalCellV = 3.2f, defaultHighV = 3.65f, defaultLowV = 2.50f),
    LEAD_ACID("Lead-acid", nominalCellV = 2.0f, defaultHighV = 2.45f, defaultLowV = 1.75f)
}
```

- [ ] **Step 4: Write `AlertConfig.kt`**

```kotlin
package com.volty.app.domain.model

data class AlertConfig(
    val cellHighV: Float? = null,
    val cellLowV: Float? = null,
    val cellDeltaMv: Int? = 200,
    val temperatureHighC: Float? = 60f,
    val socLowPercent: Int? = 15,
    val socCutoffPercent: Int? = null,
    val disconnectNotify: Boolean = true,
    val chargeCompleteNotify: Boolean = true
)
```

- [ ] **Step 5: Write `Vehicle.kt`**

```kotlin
package com.volty.app.domain.model

import kotlinx.datetime.Instant

data class Vehicle(
    val id: String,
    val name: String,
    val iconKey: String,
    val bmsType: BmsType,
    val bmsAddress: String,
    val chemistry: Chemistry,
    val cellCount: Int? = null,
    val averagingWindowMin: Int = 5,
    val alertConfig: AlertConfig = AlertConfig(),
    val createdAt: Instant,
    val lastConnectedAt: Instant? = null,
    val isPinned: Boolean = false
)
```

- [ ] **Step 6: Write `ConnectionState.kt`**

```kotlin
package com.volty.app.domain.model

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Scanning : ConnectionState()
    data class Connecting(val vehicle: Vehicle?) : ConnectionState()
    data class Connected(val vehicle: Vehicle?) : ConnectionState()
    data object Disconnected : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}
```

- [ ] **Step 7: Run build to verify models compile**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/volty/app/domain/
git commit -m "feat(domain): core models — BmsData, Vehicle, AlertConfig, ConnectionState"
```

---

## Task 5: Port byte/CRC helpers (with tests)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/bms/CrcUtils.kt`
- Create: `composeApp/src/commonTest/kotlin/com/volty/app/data/bms/CrcUtilsTest.kt`

- [ ] **Step 1: Write the failing tests first**

`composeApp/src/commonTest/kotlin/com/volty/app/data/bms/CrcUtilsTest.kt`:

```kotlin
package com.volty.app.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals

class CrcUtilsTest {

    @Test
    fun `checksumSum returns lower byte of sum`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte())
        // sum = 0x105 -> & 0xFF = 0x05
        assertEquals(0x05, checksumSum(data))
    }

    @Test
    fun `checksumSum respects start and end range`() {
        val data = byteArrayOf(0x10, 0x01, 0x02, 0x10)
        // bytes 1..2 inclusive = 0x01 + 0x02 = 0x03
        assertEquals(0x03, checksumSum(data, start = 1, end = 3))
    }

    @Test
    fun `crc16Modbus matches known vector`() {
        // Known test vector for "123456789" ASCII = 0x4B37
        val data = "123456789".encodeToByteArray()
        assertEquals(0x4B37, crc16Modbus(data))
    }

    @Test
    fun `u16LE little-endian`() {
        // 0x34, 0x12 -> 0x1234 = 4660
        assertEquals(0x1234, byteArrayOf(0x34, 0x12).u16LE(0))
    }

    @Test
    fun `i16LE negative`() {
        // 0xFF, 0xFF -> -1
        assertEquals(-1, byteArrayOf(0xFF.toByte(), 0xFF.toByte()).i16LE(0))
    }

    @Test
    fun `u16BE big-endian`() {
        assertEquals(0x1234, byteArrayOf(0x12, 0x34).u16BE(0))
    }

    @Test
    fun `u32LE four bytes little-endian`() {
        // 0x78,0x56,0x34,0x12 -> 0x12345678
        val data = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        assertEquals(0x12345678L, data.u32LE(0))
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail (compilation error)**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.bms.CrcUtilsTest"`
Expected: FAIL — references to `checksumSum`, `crc16Modbus`, `u16LE` etc. do not resolve.

- [ ] **Step 3: Port helpers from Kelly**

`composeApp/src/commonMain/kotlin/com/volty/app/data/bms/CrcUtils.kt`:

```kotlin
package com.volty.app.data.bms

/** Simple byte-sum checksum (JK BMS, Daly BMS). */
fun checksumSum(data: ByteArray, start: Int = 0, end: Int = data.size): Int {
    var sum = 0
    for (i in start until end) {
        sum += data[i].toInt() and 0xFF
    }
    return sum and 0xFF
}

/** CRC-16/MODBUS (ANT BMS). Reflected polynomial 0xA001. */
fun crc16Modbus(data: ByteArray, start: Int = 0, end: Int = data.size): Int {
    var crc = 0xFFFF
    for (i in start until end) {
        crc = crc xor (data[i].toInt() and 0xFF)
        repeat(8) {
            crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
        }
    }
    return crc and 0xFFFF
}

fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

fun ByteArray.u16LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8)

fun ByteArray.i16LE(offset: Int): Int {
    val v = u16LE(offset)
    return if (v >= 0x8000) v - 0x10000 else v
}

fun ByteArray.u32LE(offset: Int): Long =
    (this[offset].toInt() and 0xFF).toLong() or
            ((this[offset + 1].toInt() and 0xFF).toLong() shl 8) or
            ((this[offset + 2].toInt() and 0xFF).toLong() shl 16) or
            ((this[offset + 3].toInt() and 0xFF).toLong() shl 24)

fun ByteArray.i32LE(offset: Int): Int = u32LE(offset).toInt()

fun ByteArray.u16BE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or
            (this[offset + 1].toInt() and 0xFF)

fun ByteArray.i16BE(offset: Int): Int {
    val v = u16BE(offset)
    return if (v >= 0x8000) v - 0x10000 else v
}

fun ByteArray.u32BE(offset: Int): Long =
    ((this[offset].toInt() and 0xFF).toLong() shl 24) or
            ((this[offset + 1].toInt() and 0xFF).toLong() shl 16) or
            ((this[offset + 2].toInt() and 0xFF).toLong() shl 8) or
            (this[offset + 3].toInt() and 0xFF).toLong()
```

- [ ] **Step 4: Run tests to confirm PASS**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.bms.CrcUtilsTest"`
Expected: 7/7 PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/volty/app/data/bms/CrcUtils.kt composeApp/src/commonTest/kotlin/com/volty/app/data/bms/CrcUtilsTest.kt
git commit -m "feat(bms): byte helpers + checksum + CRC-16/MODBUS with tests"
```

---

## Task 6: BmsProtocol abstraction + ByteArrayAccumulator

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/bms/BmsUuids.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/bms/BmsProtocol.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/bms/ByteArrayAccumulator.kt`
- Create: `composeApp/src/commonTest/kotlin/com/volty/app/data/bms/ByteArrayAccumulatorTest.kt`

- [ ] **Step 1: Write the failing test**

`composeApp/src/commonTest/kotlin/com/volty/app/data/bms/ByteArrayAccumulatorTest.kt`:

```kotlin
package com.volty.app.data.bms

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ByteArrayAccumulatorTest {

    @Test
    fun `append concatenates chunks`() {
        val acc = ByteArrayAccumulator()
        acc.append(byteArrayOf(1, 2, 3))
        acc.append(byteArrayOf(4, 5))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), acc.toByteArray())
        assertEquals(5, acc.size)
    }

    @Test
    fun `trimLeading removes N bytes from the front`() {
        val acc = ByteArrayAccumulator()
        acc.append(byteArrayOf(1, 2, 3, 4, 5))
        acc.trimLeading(2)
        assertContentEquals(byteArrayOf(3, 4, 5), acc.toByteArray())
    }

    @Test
    fun `trimLeading more than size empties buffer`() {
        val acc = ByteArrayAccumulator()
        acc.append(byteArrayOf(1, 2))
        acc.trimLeading(5)
        assertEquals(0, acc.size)
    }

    @Test
    fun `reset empties buffer`() {
        val acc = ByteArrayAccumulator()
        acc.append(byteArrayOf(9, 9, 9))
        acc.reset()
        assertEquals(0, acc.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.bms.ByteArrayAccumulatorTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement `ByteArrayAccumulator.kt`**

```kotlin
package com.volty.app.data.bms

/**
 * Mutable byte buffer that supports append, trim-leading, and reset.
 * Used for accumulating BLE notification chunks.
 */
internal class ByteArrayAccumulator {
    private var data = ByteArray(0)

    fun append(chunk: ByteArray) { data = data + chunk }

    fun toByteArray(): ByteArray = data

    fun trimLeading(count: Int) {
        data = if (count >= data.size) ByteArray(0) else data.copyOfRange(count, data.size)
    }

    fun reset() { data = ByteArray(0) }

    val size: Int get() = data.size
}
```

- [ ] **Step 4: Implement `BmsUuids.kt`**

```kotlin
package com.volty.app.data.bms

/** BLE UUIDs for a BMS type. notifyCharUuid may equal writeCharUuid for single-characteristic BMS. */
data class BmsUuids(
    val serviceUuid: String,
    val notifyCharUuid: String,
    val writeCharUuid: String
)
```

- [ ] **Step 5: Implement `BmsProtocol.kt`**

```kotlin
package com.volty.app.data.bms

import com.volty.app.domain.model.BmsData

abstract class BmsProtocol {

    abstract val uuids: BmsUuids

    /** Commands sent once after connecting. */
    abstract fun handshakeCommands(): List<ByteArray>

    /** Commands sent each poll cycle. Empty list = streaming protocol. */
    abstract fun pollCommands(): List<ByteArray>

    /** Delay between poll cycles (ms). Ignored when [pollCommands] is empty. */
    open val pollIntervalMs: Long = 1000L

    /** Feed an incoming BLE notification chunk. */
    abstract fun onNotification(data: ByteArray)

    /** Latest fully-parsed BMS data, or null if nothing has been parsed yet. */
    abstract fun latestData(): BmsData?

    /** Reset internal buffers and parser state. */
    abstract fun reset()
}
```

- [ ] **Step 6: Run tests, verify PASS + build**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.bms.ByteArrayAccumulatorTest"`
Expected: 4/4 PASS.

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/volty/app/data/bms/ composeApp/src/commonTest/kotlin/com/volty/app/data/bms/ByteArrayAccumulatorTest.kt
git commit -m "feat(bms): BmsProtocol abstraction + accumulator with tests"
```

---

## Task 7: Port JK BMS protocol (with tests)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/bms/JkBmsProtocol.kt`
- Create: `composeApp/src/commonTest/kotlin/com/volty/app/data/bms/JkBmsProtocolTest.kt`

- [ ] **Step 1: Write the failing test**

`composeApp/src/commonTest/kotlin/com/volty/app/data/bms/JkBmsProtocolTest.kt`:

```kotlin
package com.volty.app.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JkBmsProtocolTest {

    private fun synthesizeCellDataFrame(
        numCells: Int = 4,
        cellVoltagesMv: IntArray = intArrayOf(3300, 3301, 3302, 3303),
        packVoltageMv: Long = 13200,
        currentMa: Int = -5000,
        socPercent: Int = 75,
        remainingAhMilli: Long = 9000,
        capacityAhMilli: Long = 12000,
        cycles: Long = 42
    ): ByteArray {
        val frame = ByteArray(320)
        // Header 0x55 AA EB 90
        frame[0] = 0x55.toByte(); frame[1] = 0xAA.toByte()
        frame[2] = 0xEB.toByte(); frame[3] = 0x90.toByte()
        frame[4] = 0x02 // type = cell data
        // Cell voltages at offset 6, 2 bytes each (LE)
        for (i in 0 until numCells) {
            val mv = cellVoltagesMv[i]
            frame[6 + i * 2] = (mv and 0xFF).toByte()
            frame[6 + i * 2 + 1] = ((mv ushr 8) and 0xFF).toByte()
        }
        // Pack voltage at 118 (u32 LE, millivolts)
        writeU32LE(frame, 118, packVoltageMv)
        // Current at 126 (i32 LE, milliamps, negated)
        writeU32LE(frame, 126, (-currentMa).toLong() and 0xFFFFFFFFL)
        // Temps (130, 132) - set both to "no sensor" = -2000 in i16 LE
        writeI16LE(frame, 130, -2000)
        writeI16LE(frame, 132, -2000)
        // SOC at 141 (u8)
        frame[141] = socPercent.toByte()
        // Remaining Ah at 142 (u32 LE, milliamp-hours)
        writeU32LE(frame, 142, remainingAhMilli)
        // Capacity Ah at 146 (u32 LE)
        writeU32LE(frame, 146, capacityAhMilli)
        // Cycles at 150 (u32 LE)
        writeU32LE(frame, 150, cycles)
        // CRC at 299 = sum of bytes 0..298
        frame[299] = checksumSum(frame, 0, 299).toByte()
        return frame
    }

    private fun writeU32LE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeI16LE(buf: ByteArray, offset: Int, value: Int) {
        val v = value and 0xFFFF
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    @Test
    fun `uuids match JK BMS spec`() {
        val p = JkBmsProtocol()
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", p.uuids.serviceUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", p.uuids.notifyCharUuid)
        assertEquals(p.uuids.notifyCharUuid, p.uuids.writeCharUuid)
    }

    @Test
    fun `handshake sends 0x97 then 0x96`() {
        val cmds = JkBmsProtocol().handshakeCommands()
        assertEquals(2, cmds.size)
        assertEquals(0x97.toByte(), cmds[0][4])
        assertEquals(0x96.toByte(), cmds[1][4])
    }

    @Test
    fun `poll commands are empty (streaming protocol)`() {
        assertTrue(JkBmsProtocol().pollCommands().isEmpty())
    }

    @Test
    fun `parses valid cell-data frame`() {
        val proto = JkBmsProtocol(maxCells = 4)
        proto.onNotification(synthesizeCellDataFrame())
        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(4, data.cellVoltages.size)
        assertEquals(3.300f, data.cellVoltages[0], 0.001f)
        assertEquals(3.303f, data.cellVoltages[3], 0.001f)
        assertEquals(13.200f, data.voltage, 0.001f)
        assertEquals(5.000f, data.current, 0.001f) // current negated -> +5 A (charging)
        assertEquals(75f, data.soc)
        assertEquals(9.000f, data.charge, 0.001f)
        assertEquals(12.000f, data.capacity, 0.001f)
        assertEquals(42, data.numCycles)
        assertTrue(data.isConnected)
    }

    @Test
    fun `rejects frame with bad CRC`() {
        val proto = JkBmsProtocol(maxCells = 4)
        val frame = synthesizeCellDataFrame()
        frame[299] = (frame[299].toInt() + 1).toByte() // corrupt CRC
        proto.onNotification(frame)
        assertNull(proto.latestData())
    }

    @Test
    fun `reset clears state`() {
        val proto = JkBmsProtocol(maxCells = 4)
        proto.onNotification(synthesizeCellDataFrame())
        assertNotNull(proto.latestData())
        proto.reset()
        assertNull(proto.latestData())
    }
}
```

- [ ] **Step 2: Run test, verify FAIL**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.bms.JkBmsProtocolTest"`
Expected: compile error — class `JkBmsProtocol` does not exist.

- [ ] **Step 3: Port `JkBmsProtocol.kt` from Kelly**

Copy `composeApp/src/commonMain/kotlin/com/kelly/app/bms/JkBmsProtocol.kt` from `kelly-connect`, change package to `com.volty.app.data.bms`, remove the duplicate `ByteArrayAccumulator` definition at the bottom (it lives in its own file now), change the `import com.kelly.app.domain.model.BmsData` to `import com.volty.app.domain.model.BmsData`. Resulting file:

```kotlin
package com.volty.app.data.bms

import com.volty.app.domain.model.BmsData

class JkBmsProtocol(
    private val maxCells: Int = 24
) : BmsProtocol() {

    override val uuids = BmsUuids(
        serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
        notifyCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb",
        writeCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"
    )

    private val buffer = ByteArrayAccumulator()
    private var numCells: Int = maxCells
    private var fwOffset: Int = 0
    private var lastData: BmsData? = null
    private var chargeSwitch: Boolean = false
    private var dischargeSwitch: Boolean = false

    override fun handshakeCommands(): List<ByteArray> = listOf(
        buildCommand(0x97),
        buildCommand(0x96)
    )

    override fun pollCommands(): List<ByteArray> = emptyList()

    override val pollIntervalMs: Long = 0L

    override fun onNotification(data: ByteArray) {
        buffer.append(data)
        tryParseAll()
    }

    override fun latestData(): BmsData? = lastData

    override fun reset() {
        buffer.reset()
        lastData = null
        numCells = maxCells
        fwOffset = 0
    }

    private fun buildCommand(address: Int, value: ByteArray = ByteArray(0)): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55
        frame[2] = 0x90.toByte()
        frame[3] = 0xEB.toByte()
        frame[4] = address.toByte()
        frame[5] = value.size.toByte()
        value.copyInto(frame, destinationOffset = 6)
        frame[19] = checksumSum(frame, 0, 19).toByte()
        return frame
    }

    private fun tryParseAll() {
        while (true) {
            val buf = buffer.toByteArray()
            val headerIdx = findHeader(buf)
            if (headerIdx < 0) {
                if (buf.size > 4) buffer.trimLeading(buf.size - 4)
                return
            }
            if (headerIdx > 0) buffer.trimLeading(headerIdx)

            val current = buffer.toByteArray()
            if (current.size < 300) return

            val crc = checksumSum(current, 0, 299)
            if ((crc and 0xFF) != (current[299].toInt() and 0xFF)) {
                buffer.trimLeading(4)
                continue
            }

            val responseType = current[4].toInt() and 0xFF
            parseResponse(responseType, current)

            val frameLen = if (current.size >= 320) 320 else 300
            buffer.trimLeading(frameLen.coerceAtMost(current.size))
        }
    }

    private fun findHeader(data: ByteArray): Int {
        for (i in 0..data.size - 4) {
            if ((data[i].toInt() and 0xFF) == 0x55 &&
                (data[i + 1].toInt() and 0xFF) == 0xAA &&
                (data[i + 2].toInt() and 0xFF) == 0xEB &&
                (data[i + 3].toInt() and 0xFF) == 0x90
            ) return i
        }
        return -1
    }

    private fun parseResponse(type: Int, buf: ByteArray) {
        when (type) {
            0x01 -> parseSettings(buf)
            0x02 -> parseCellData(buf)
            0x03 -> { /* device info */ }
        }
    }

    private fun parseSettings(buf: ByteArray) {
        if (buf.size < 300) return
        val nc = buf.u8(114)
        if (nc in 1..32) numCells = nc
        chargeSwitch = buf.u8(118) != 0
        dischargeSwitch = buf.u8(122) != 0
    }

    private fun parseCellData(buf: ByteArray) {
        if (buf.size < 170 + fwOffset) return

        val cells = mutableListOf<Float>()
        for (i in 0 until numCells) {
            val offset = 6 + i * 2
            if (offset + 1 >= buf.size) break
            val mv = buf.u16LE(offset)
            if (mv in 1..5000) cells.add(mv / 1000f)
        }

        val o = fwOffset
        val voltage = buf.u32LE(118 + o) * 0.001f
        val current = -(buf.i32LE(126 + o) * 0.001f)

        val temps = mutableListOf<Float>()
        val t1 = buf.i16LE(130 + o); if (t1 != -2000) temps.add(t1 / 10f)
        val t2 = buf.i16LE(132 + o); if (t2 != -2000) temps.add(t2 / 10f)

        val soc = buf.u8(141 + o).toFloat()
        val charge = buf.u32LE(142 + o) * 0.001f
        val capacity = buf.u32LE(146 + o) * 0.001f
        val numCycles = buf.u32LE(150 + o).toInt()

        lastData = BmsData(
            voltage = voltage,
            current = current,
            power = voltage * current,
            soc = soc,
            charge = charge,
            capacity = capacity,
            numCycles = numCycles,
            cellVoltages = cells,
            temperatures = temps,
            chargeEnabled = chargeSwitch,
            dischargeEnabled = dischargeSwitch,
            isConnected = true
        )
    }
}
```

- [ ] **Step 4: Run JK tests, verify all PASS**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.bms.JkBmsProtocolTest"`
Expected: 6/6 PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/volty/app/data/bms/JkBmsProtocol.kt composeApp/src/commonTest/kotlin/com/volty/app/data/bms/JkBmsProtocolTest.kt
git commit -m "feat(bms): port JK BMS protocol with synthetic-frame tests"
```

---

## Task 8: Port JBD BMS protocol (with tests)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/bms/JbdBmsProtocol.kt`
- Create: `composeApp/src/commonTest/kotlin/com/volty/app/data/bms/JbdBmsProtocolTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.volty.app.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JbdBmsProtocolTest {

    /** Assemble a JBD response frame: DD <cmd> 00 <len> <data...> <csum_hi> <csum_lo> 77 */
    private fun jbdFrame(cmd: Int, data: ByteArray): ByteArray {
        val payload = byteArrayOf(0x00, data.size.toByte()) + data
        // Checksum = 0x10000 - sum(cmd + payload[0..end-1] starting at the len byte through end of data)
        // Per the parser: it reads `len = frame[3]`, then `data = frame[4..4+len]`.
        // We'll match Kelly's logic by computing csum over (cmd, 0x00, len, ...data) but the
        // exact JBD checksum isn't validated by the parser (it never checks csum). Use 0x0000.
        return byteArrayOf(0xDD.toByte(), cmd.toByte()) + payload + byteArrayOf(0x00, 0x00, 0x77)
    }

    /** Build a "main data" (cmd 0x03) payload per Kelly's parseMainData. */
    private fun mainDataPayload(
        voltageCv: Int = 5000,        // u16 BE / 100 -> 50.00 V
        currentCa: Int = -200,        // i16 BE / 100, then negated -> +2.00 A
        chargeCAh: Int = 800,         // u16 BE / 100 -> 8.00 Ah
        capacityCAh: Int = 1000,      // u16 BE / 100 -> 10.00 Ah
        cycles: Int = 7,
        mosState: Int = 0x03,         // both ON
        numTemp: Int = 2,
        temps: List<Int> = listOf(2981, 2991) // Kelvin*10
    ): ByteArray {
        // Per parser: voltage at d, current at d+2, charge at d+4, capacity at d+6, cycles at d+8,
        // SOC at d+19, MOS at d+20, numTemp at d+22, temps starting at d+23
        val payload = ByteArray(23 + numTemp * 2)
        // u16 BE helpers
        fun be16(off: Int, v: Int) {
            payload[off] = ((v shr 8) and 0xFF).toByte()
            payload[off + 1] = (v and 0xFF).toByte()
        }
        be16(0, voltageCv)
        // currentCa is signed; produce two's-complement u16
        val c = currentCa and 0xFFFF
        be16(2, c)
        be16(4, chargeCAh)
        be16(6, capacityCAh)
        be16(8, cycles)
        payload[19] = 80 // SOC = 80%
        payload[20] = mosState.toByte()
        payload[22] = numTemp.toByte()
        for (i in 0 until numTemp) {
            be16(23 + i * 2, temps[i])
        }
        return payload
    }

    /** Cell voltages payload (cmd 0x04): N pairs of u16 BE, millivolts. */
    private fun cellsPayload(cellsMv: IntArray): ByteArray {
        val out = ByteArray(cellsMv.size * 2)
        for ((i, mv) in cellsMv.withIndex()) {
            out[i * 2] = ((mv shr 8) and 0xFF).toByte()
            out[i * 2 + 1] = (mv and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `uuids match JBD spec`() {
        val p = JbdBmsProtocol()
        assertEquals("0000ff00-0000-1000-8000-00805f9b34fb", p.uuids.serviceUuid)
        assertEquals("0000ff01-0000-1000-8000-00805f9b34fb", p.uuids.notifyCharUuid)
        assertEquals("0000ff02-0000-1000-8000-00805f9b34fb", p.uuids.writeCharUuid)
    }

    @Test
    fun `poll commands include 0x03 and 0x04`() {
        val cmds = JbdBmsProtocol().pollCommands()
        assertEquals(2, cmds.size)
        assertEquals(0x03.toByte(), cmds[0][2])
        assertEquals(0x04.toByte(), cmds[1][2])
    }

    @Test
    fun `main data then cell data assembles complete BmsData`() {
        val proto = JbdBmsProtocol()
        proto.onNotification(jbdFrame(0x03, mainDataPayload()))
        proto.onNotification(jbdFrame(0x04, cellsPayload(intArrayOf(3300, 3310))))

        val data = proto.latestData()
        assertNotNull(data)
        assertEquals(50.00f, data.voltage, 0.001f)
        assertEquals(2.00f, data.current, 0.001f) // negated: stored as -200 -> +2.00
        assertEquals(8.00f, data.charge, 0.001f)
        assertEquals(10.00f, data.capacity, 0.001f)
        assertEquals(7, data.numCycles)
        assertEquals(80f, data.soc)
        assertTrue(data.chargeEnabled)
        assertTrue(data.dischargeEnabled)
        assertEquals(2, data.temperatures.size)
        assertEquals(25.0f, data.temperatures[0], 0.1f) // (2981 - 2731) / 10
        assertEquals(2, data.cellVoltages.size)
        assertEquals(3.300f, data.cellVoltages[0], 0.001f)
    }
}
```

- [ ] **Step 2: Run test, verify FAIL**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.bms.JbdBmsProtocolTest"`
Expected: compile error (class missing).

- [ ] **Step 3: Port `JbdBmsProtocol.kt` from Kelly**

Copy `composeApp/src/commonMain/kotlin/com/kelly/app/bms/JbdBmsProtocol.kt` to `composeApp/src/commonMain/kotlin/com/volty/app/data/bms/JbdBmsProtocol.kt`. Change package + `com.kelly.app.domain.model.BmsData` → `com.volty.app.domain.model.BmsData`. No other modifications. (Full source: see `kelly-connect/composeApp/src/commonMain/kotlin/com/kelly/app/bms/JbdBmsProtocol.kt`.)

- [ ] **Step 4: Run JBD tests, verify PASS**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.bms.JbdBmsProtocolTest"`
Expected: 3/3 PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/volty/app/data/bms/JbdBmsProtocol.kt composeApp/src/commonTest/kotlin/com/volty/app/data/bms/JbdBmsProtocolTest.kt
git commit -m "feat(bms): port JBD/Xiaoxiang BMS protocol with tests"
```

---

## Task 9: Port ANT BMS protocol (with tests)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/bms/AntBmsProtocol.kt`
- Create: `composeApp/src/commonTest/kotlin/com/volty/app/data/bms/AntBmsProtocolTest.kt`

- [ ] **Step 1: Inspect Kelly's `AntBmsProtocol.kt`** to understand frame format

Run: `cat /c/Users/sodovaya/Desktop/kelly/kelly-connect/composeApp/src/commonMain/kotlin/com/kelly/app/bms/AntBmsProtocol.kt`
Note: ANT BMS uses CRC-16/MODBUS over the response frame.

- [ ] **Step 2: Write the failing tests**

The test mirrors the JK approach: synthesize the response payload, compute the proper CRC-16/MODBUS over it, feed it to the protocol, and assert parsed values. Use the helper functions `u16LE` / `u16BE` etc from `CrcUtils.kt`.

Write `AntBmsProtocolTest.kt` with at least these assertions:
- `uuids match ANT spec` — service `0xFFE0`-style or whatever Kelly's file states (read from Kelly file)
- `poll commands exist`
- `parses status frame given valid CRC` — voltage, current, SOC, cells, temps
- `rejects frame with bad CRC`

(Replicate the synthetic-frame approach from Task 7. Read the exact offsets from Kelly's `parseStatus` method.)

- [ ] **Step 3: Run test, verify FAIL**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.bms.AntBmsProtocolTest"`
Expected: FAIL.

- [ ] **Step 4: Port `AntBmsProtocol.kt` from Kelly**

Copy the file, change package + BmsData import.

- [ ] **Step 5: Run tests, verify PASS**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.bms.AntBmsProtocolTest"`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/volty/app/data/bms/AntBmsProtocol.kt composeApp/src/commonTest/kotlin/com/volty/app/data/bms/AntBmsProtocolTest.kt
git commit -m "feat(bms): port ANT BMS protocol with tests"
```

---

## Task 10: Port Daly BMS protocol (with tests)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/bms/DalyBmsProtocol.kt`
- Create: `composeApp/src/commonTest/kotlin/com/volty/app/data/bms/DalyBmsProtocolTest.kt`

- [ ] **Step 1: Inspect Kelly's `DalyBmsProtocol.kt`**

Run: `cat /c/Users/sodovaya/Desktop/kelly/kelly-connect/composeApp/src/commonMain/kotlin/com/kelly/app/bms/DalyBmsProtocol.kt`

Note: Daly uses byte-sum checksum and several command IDs (`0x90` voltage, `0x91` status, `0x95` cells, `0x96` temps).

- [ ] **Step 2: Write the failing tests**

Mirror previous approach. Synthesize each command response (voltage frame, status frame, cells frame, temps frame), feed them all, and assert combined `BmsData`. Assert UUIDs, poll command list, and reset behavior.

- [ ] **Step 3: Run test, verify FAIL**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.bms.DalyBmsProtocolTest"`
Expected: FAIL.

- [ ] **Step 4: Port `DalyBmsProtocol.kt` from Kelly**

Copy file, change package + BmsData import.

- [ ] **Step 5: Run tests, verify PASS**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.bms.DalyBmsProtocolTest"`
Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/volty/app/data/bms/DalyBmsProtocol.kt composeApp/src/commonTest/kotlin/com/volty/app/data/bms/DalyBmsProtocolTest.kt
git commit -m "feat(bms): port Daly BMS protocol with tests"
```

---

## Task 11: BmsTypeDetector

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/bms/BmsTypeDetector.kt`
- Create: `composeApp/src/commonTest/kotlin/com/volty/app/data/bms/BmsTypeDetectorTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.volty.app.data.bms

import com.volty.app.domain.model.BmsType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BmsTypeDetectorTest {

    private val JK_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
    private val JBD_SERVICE = "0000ff00-0000-1000-8000-00805f9b34fb"

    @Test
    fun `detects JK by name prefix`() {
        assertEquals(BmsType.JK_BMS, BmsTypeDetector.detect(name = "JK_B2A24S20P", serviceUuids = emptyList()))
        assertEquals(BmsType.JK_BMS, BmsTypeDetector.detect(name = "JK-XYZ", serviceUuids = emptyList()))
    }

    @Test
    fun `detects JBD by name prefix`() {
        assertEquals(BmsType.JBD_BMS, BmsTypeDetector.detect(name = "xiaoxiang-001", serviceUuids = emptyList()))
        assertEquals(BmsType.JBD_BMS, BmsTypeDetector.detect(name = "JBD-1", serviceUuids = emptyList()))
        assertEquals(BmsType.JBD_BMS, BmsTypeDetector.detect(name = "SP04S001", serviceUuids = emptyList()))
    }

    @Test
    fun `detects ANT by name prefix`() {
        assertEquals(BmsType.ANT_BMS, BmsTypeDetector.detect(name = "ANT-201", serviceUuids = emptyList()))
    }

    @Test
    fun `detects Daly by name prefix`() {
        assertEquals(BmsType.DALY_BMS, BmsTypeDetector.detect(name = "DL-32E-24S", serviceUuids = emptyList()))
        assertEquals(BmsType.DALY_BMS, BmsTypeDetector.detect(name = "Daly-xxx", serviceUuids = emptyList()))
    }

    @Test
    fun `detects by service uuid when name is missing`() {
        assertEquals(BmsType.JK_BMS, BmsTypeDetector.detect(name = null, serviceUuids = listOf(JK_SERVICE)))
        assertEquals(BmsType.JBD_BMS, BmsTypeDetector.detect(name = null, serviceUuids = listOf(JBD_SERVICE)))
    }

    @Test
    fun `returns null when no signals match`() {
        assertNull(BmsTypeDetector.detect(name = "Whatever", serviceUuids = listOf("0000abcd-0000-1000-8000-00805f9b34fb")))
        assertNull(BmsTypeDetector.detect(name = null, serviceUuids = emptyList()))
    }
}
```

- [ ] **Step 2: Run test, verify FAIL**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.bms.BmsTypeDetectorTest"`
Expected: class missing.

- [ ] **Step 3: Implement `BmsTypeDetector.kt`**

```kotlin
package com.volty.app.data.bms

import com.volty.app.domain.model.BmsType

object BmsTypeDetector {

    /** Each BMS type's well-known short service code (4 hex digits) inside the long UUID. */
    private val SERVICE_SHORTS = mapOf(
        "ffe0" to BmsType.JK_BMS,
        "ff00" to BmsType.JBD_BMS,
        "ffe0" to BmsType.JK_BMS,
        "fff0" to BmsType.ANT_BMS,
        "fff0" to BmsType.ANT_BMS,
        "fee0" to BmsType.DALY_BMS
    )

    fun detect(name: String?, serviceUuids: List<String>): BmsType? {
        nameMatch(name)?.let { return it }
        for (uuid in serviceUuids) {
            val short = uuid.lowercase().substring(4, 8)
            SERVICE_SHORTS[short]?.let { return it }
        }
        return null
    }

    private fun nameMatch(name: String?): BmsType? {
        if (name.isNullOrEmpty()) return null
        return when {
            name.startsWith("JK_", ignoreCase = true) || name.startsWith("JK-", ignoreCase = true) -> BmsType.JK_BMS
            name.startsWith("xiaoxiang", ignoreCase = true) ||
                name.startsWith("JBD", ignoreCase = true) ||
                name.startsWith("SP", ignoreCase = true) -> BmsType.JBD_BMS
            name.startsWith("ANT", ignoreCase = true) -> BmsType.ANT_BMS
            name.startsWith("DL-", ignoreCase = true) ||
                name.startsWith("Daly", ignoreCase = true) -> BmsType.DALY_BMS
            else -> null
        }
    }
}
```

Note: If during execution you find that the service-short heuristic is wrong (e.g. ANT/Daly use different shorts than assumed), update the test expectations and the `SERVICE_SHORTS` map to match the actual UUIDs each protocol file declares. The source of truth is each protocol's `BmsUuids.serviceUuid`.

- [ ] **Step 4: Run test, verify PASS**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.bms.BmsTypeDetectorTest"`
Expected: 6/6 PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/volty/app/data/bms/BmsTypeDetector.kt composeApp/src/commonTest/kotlin/com/volty/app/data/bms/BmsTypeDetectorTest.kt
git commit -m "feat(bms): BmsTypeDetector with name + service-UUID heuristics"
```

---

## Task 12: SampleRingBuffer (with tests)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/memory/SampleRingBuffer.kt`
- Create: `composeApp/src/commonTest/kotlin/com/volty/app/data/memory/SampleRingBufferTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.volty.app.data.memory

import com.volty.app.domain.model.BmsData
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SampleRingBufferTest {

    private fun sample(t: Instant, power: Float = 0f) =
        BmsData(power = power, timestamp = t)

    @Test
    fun `push and within returns chronological items inside the window`() {
        val buf = SampleRingBuffer(capacity = 100)
        val base = Instant.fromEpochSeconds(1_000_000)
        for (i in 0..9) buf.push(sample(base.plus(i.seconds), i.toFloat()))
        val recent = buf.within(5.seconds, now = base.plus(9.seconds))
        // Window covers t in [4s..9s] -> 6 samples
        assertEquals(6, recent.size)
        assertEquals(4f, recent.first().power)
        assertEquals(9f, recent.last().power)
    }

    @Test
    fun `push beyond capacity overwrites oldest`() {
        val buf = SampleRingBuffer(capacity = 3)
        val base = Instant.fromEpochSeconds(1_000_000)
        for (i in 0..4) buf.push(sample(base.plus(i.seconds), i.toFloat()))
        val all = buf.within(1.minutes, now = base.plus(10.seconds))
        assertEquals(3, all.size)
        assertEquals(2f, all.first().power)
        assertEquals(4f, all.last().power)
    }

    @Test
    fun `within returns empty when no samples in window`() {
        val buf = SampleRingBuffer(capacity = 10)
        val base = Instant.fromEpochSeconds(1_000_000)
        buf.push(sample(base, 1f))
        val recent = buf.within(1.seconds, now = base.plus(1.minutes))
        assertEquals(0, recent.size)
    }

    @Test
    fun `clear empties buffer`() {
        val buf = SampleRingBuffer(capacity = 10)
        val base = Instant.fromEpochSeconds(1_000_000)
        buf.push(sample(base, 1f))
        buf.clear()
        assertEquals(0, buf.within(1.minutes, now = base).size)
    }
}
```

- [ ] **Step 2: Run test, verify FAIL**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.memory.SampleRingBufferTest"`
Expected: class missing.

- [ ] **Step 3: Implement `SampleRingBuffer.kt`**

```kotlin
package com.volty.app.data.memory

import com.volty.app.domain.model.BmsData
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration

class SampleRingBuffer(private val capacity: Int = 1800) {

    private val items = ArrayDeque<BmsData>(capacity)

    @Synchronized
    fun push(sample: BmsData) {
        if (items.size >= capacity) items.removeFirst()
        items.addLast(sample)
    }

    @Synchronized
    fun within(window: Duration, now: Instant = Clock.System.now()): List<BmsData> {
        val cutoff = now - window
        return items.filter { it.timestamp >= cutoff }
    }

    @Synchronized
    fun clear() { items.clear() }
}
```

- [ ] **Step 4: Run test, verify PASS**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.memory.SampleRingBufferTest"`
Expected: 4/4 PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/volty/app/data/memory/SampleRingBuffer.kt composeApp/src/commonTest/kotlin/com/volty/app/data/memory/SampleRingBufferTest.kt
git commit -m "feat(memory): time-windowed ring buffer for BmsData samples"
```

---

## Task 13: MovingAverage (with tests)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/stats/MovingAverage.kt`
- Create: `composeApp/src/commonTest/kotlin/com/volty/app/data/stats/MovingAverageTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.volty.app.data.stats

import com.volty.app.domain.model.BmsData
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MovingAverageTest {

    private fun sample(t: Instant, power: Float, current: Float) =
        BmsData(power = power, current = current, timestamp = t)

    @Test
    fun `empty list returns zero average`() {
        val avg = MovingAverage.over(emptyList(), 5.minutes)
        assertEquals(0f, avg.avgPowerW)
        assertEquals(0f, avg.avgCurrentA)
        assertEquals(5.minutes, avg.window)
    }

    @Test
    fun `single sample returns its values`() {
        val base = Instant.fromEpochSeconds(1_000_000)
        val avg = MovingAverage.over(listOf(sample(base, 200f, -4f)), 5.minutes)
        assertEquals(200f, avg.avgPowerW)
        assertEquals(-4f, avg.avgCurrentA)
    }

    @Test
    fun `arithmetic mean of several samples`() {
        val base = Instant.fromEpochSeconds(1_000_000)
        val samples = listOf(
            sample(base, 100f, -2f),
            sample(base.plus(1.seconds), 200f, -4f),
            sample(base.plus(2.seconds), 300f, -6f)
        )
        val avg = MovingAverage.over(samples, 5.minutes)
        assertEquals(200f, avg.avgPowerW, 0.01f)
        assertEquals(-4f, avg.avgCurrentA, 0.01f)
    }
}

// kotlin.test extensions for delta
private fun assertEquals(expected: Float, actual: Float, delta: Float) {
    kotlin.test.assertTrue(kotlin.math.abs(expected - actual) <= delta,
        "expected $expected ± $delta, got $actual")
}
```

- [ ] **Step 2: Run test, verify FAIL**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.stats.MovingAverageTest"`
Expected: class missing.

- [ ] **Step 3: Implement `MovingAverage.kt`**

```kotlin
package com.volty.app.data.stats

import com.volty.app.domain.model.BmsData
import kotlin.time.Duration

data class MovingAvg(
    val avgPowerW: Float,
    val avgCurrentA: Float,
    val window: Duration
)

object MovingAverage {
    fun over(samples: List<BmsData>, window: Duration): MovingAvg {
        if (samples.isEmpty()) return MovingAvg(0f, 0f, window)
        var p = 0f; var c = 0f
        for (s in samples) { p += s.power; c += s.current }
        return MovingAvg(
            avgPowerW = p / samples.size,
            avgCurrentA = c / samples.size,
            window = window
        )
    }
}
```

- [ ] **Step 4: Run test, verify PASS**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "com.volty.app.data.stats.MovingAverageTest"`
Expected: 3/3 PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/volty/app/data/stats/ composeApp/src/commonTest/kotlin/com/volty/app/data/stats/
git commit -m "feat(stats): MovingAverage over BmsData samples"
```

---

## Task 14: BmsRepository interface + DiscoveredDevice + VehicleRepository

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/domain/repository/BmsRepository.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/domain/repository/VehicleRepository.kt`

- [ ] **Step 1: Write `BmsRepository.kt`**

```kotlin
package com.volty.app.domain.repository

import com.volty.app.data.stats.MovingAvg
import com.volty.app.domain.model.BmsData
import com.volty.app.domain.model.BmsType
import com.volty.app.domain.model.ConnectionState
import com.volty.app.domain.model.Vehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val bmsType: BmsType,
    val knownVehicle: Vehicle? = null
)

interface BmsRepository {
    val activeData: StateFlow<BmsData>
    val activeVehicle: StateFlow<Vehicle?>
    val connectionState: StateFlow<ConnectionState>

    fun scanAll(): Flow<DiscoveredDevice>
    suspend fun connect(vehicle: Vehicle): Result<Unit>
    suspend fun connectGuest(address: String, type: BmsType): Result<Unit>
    suspend fun disconnect()

    fun samples(window: Duration): Flow<List<BmsData>>
    fun movingAverage(window: Duration): StateFlow<MovingAvg>
}
```

- [ ] **Step 2: Write `VehicleRepository.kt`**

```kotlin
package com.volty.app.domain.repository

import com.volty.app.domain.model.Vehicle
import kotlinx.coroutines.flow.Flow

interface VehicleRepository {
    val vehicles: Flow<List<Vehicle>>
    suspend fun get(id: String): Vehicle?
    suspend fun upsert(vehicle: Vehicle)
    suspend fun delete(id: String)
    suspend fun touch(id: String)
}
```

- [ ] **Step 3: Compile check**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/volty/app/domain/repository/
git commit -m "feat(domain): BmsRepository and VehicleRepository interfaces"
```

---

## Task 15: InMemoryVehicleRepository

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/memory/InMemoryVehicleRepository.kt`

Note: this is a temporary implementation for plan 1 only. SQLDelight-backed implementation lands in plan 2.

- [ ] **Step 1: Implement**

```kotlin
package com.volty.app.data.memory

import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.repository.VehicleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock

class InMemoryVehicleRepository : VehicleRepository {

    private val state = MutableStateFlow<List<Vehicle>>(emptyList())
    override val vehicles: StateFlow<List<Vehicle>> = state.asStateFlow()

    override suspend fun get(id: String): Vehicle? = state.value.firstOrNull { it.id == id }

    override suspend fun upsert(vehicle: Vehicle) {
        state.update { list ->
            val without = list.filterNot { it.id == vehicle.id }
            (without + vehicle).sortedByDescending { it.lastConnectedAt ?: it.createdAt }
        }
    }

    override suspend fun delete(id: String) {
        state.update { it.filterNot { v -> v.id == id } }
    }

    override suspend fun touch(id: String) {
        state.update { list ->
            list.map { v -> if (v.id == id) v.copy(lastConnectedAt = Clock.System.now()) else v }
        }
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/volty/app/data/memory/InMemoryVehicleRepository.kt
git commit -m "feat(data): in-memory VehicleRepository (placeholder until SQLDelight)"
```

---

## Task 16: KableBmsRepository — single-vehicle scan + connect + observe

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/data/ble/KableBmsRepository.kt`

This is the biggest task. We can't unit-test BLE itself, but the structure should mirror Kelly's tested code. We test by manual smoke test in Task 18.

- [ ] **Step 1: Implement**

```kotlin
package com.volty.app.data.ble

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.volty.app.data.bms.AntBmsProtocol
import com.volty.app.data.bms.BmsProtocol
import com.volty.app.data.bms.BmsTypeDetector
import com.volty.app.data.bms.DalyBmsProtocol
import com.volty.app.data.bms.JbdBmsProtocol
import com.volty.app.data.bms.JkBmsProtocol
import com.volty.app.data.memory.SampleRingBuffer
import com.volty.app.data.stats.MovingAvg
import com.volty.app.data.stats.MovingAverage
import com.volty.app.domain.model.BmsData
import com.volty.app.domain.model.BmsType
import com.volty.app.domain.model.ConnectionState
import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.repository.BmsRepository
import com.volty.app.domain.repository.DiscoveredDevice
import com.volty.app.domain.repository.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class KableBmsRepository(
    private val vehicleRepository: VehicleRepository
) : BmsRepository {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _activeData = MutableStateFlow(BmsData())
    override val activeData: StateFlow<BmsData> = _activeData.asStateFlow()

    private val _activeVehicle = MutableStateFlow<Vehicle?>(null)
    override val activeVehicle: StateFlow<Vehicle?> = _activeVehicle.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val ringBuffer = SampleRingBuffer(capacity = 30 * 60) // 30 min @ 1 Hz

    private var peripheral: Peripheral? = null
    private var protocol: BmsProtocol? = null
    private var observeJob: Job? = null
    private var pollingJob: Job? = null

    private val advertisementCache = mutableMapOf<String, com.juul.kable.Advertisement>()

    override fun scanAll(): Flow<DiscoveredDevice> = flow {
        val knownAddresses: Map<String, Vehicle> =
            vehicleRepository.vehicles.first().associateBy { it.bmsAddress }
        _connectionState.value = ConnectionState.Scanning
        val scanner = Scanner()
        scanner.advertisements.collect { ad ->
            val name = ad.name
            val serviceList = ad.uuids.map { it.toString().lowercase() }
            val type = BmsTypeDetector.detect(name = name, serviceUuids = serviceList) ?: return@collect
            val id = ad.identifier.toString()
            advertisementCache[id] = ad
            emit(
                DiscoveredDevice(
                    address = id,
                    name = name,
                    rssi = ad.rssi,
                    bmsType = type,
                    knownVehicle = knownAddresses[id]
                )
            )
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun connect(vehicle: Vehicle): Result<Unit> =
        doConnect(vehicle.bmsAddress, vehicle.bmsType, vehicle)

    override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> =
        doConnect(address, type, vehicle = null)

    private suspend fun doConnect(address: String, type: BmsType, vehicle: Vehicle?): Result<Unit> {
        return try {
            disconnect()
            _connectionState.value = ConnectionState.Connecting(vehicle)
            _activeVehicle.value = vehicle

            val proto = createProtocol(type)
            protocol = proto

            var advertisement = advertisementCache[address]
            if (advertisement == null) {
                advertisement = withTimeoutOrNull(5_000L) {
                    Scanner().advertisements.first { it.identifier.toString() == address }
                }
                if (advertisement != null) advertisementCache[address] = advertisement
            }
            if (advertisement == null) {
                _connectionState.value = ConnectionState.Failed("Device not found")
                return Result.failure(IllegalStateException("Device not found"))
            }

            val p = Peripheral(advertisement)
            peripheral = p
            p.connect()

            val notifyChar = characteristicOf(
                service = Uuid.parse(proto.uuids.serviceUuid),
                characteristic = Uuid.parse(proto.uuids.notifyCharUuid)
            )

            observeJob = scope.launch {
                p.observe(notifyChar).collect { data ->
                    proto.onNotification(data)
                    proto.latestData()?.let { bms ->
                        val sample = bms.copy(timestamp = Clock.System.now())
                        _activeData.value = sample
                        ringBuffer.push(sample)
                    }
                }
            }

            delay(200)

            val writeChar = characteristicOf(
                service = Uuid.parse(proto.uuids.serviceUuid),
                characteristic = Uuid.parse(proto.uuids.writeCharUuid)
            )
            for (cmd in proto.handshakeCommands()) {
                p.write(writeChar, cmd, WriteType.WithoutResponse)
                delay(100)
            }

            val pollCmds = proto.pollCommands()
            if (pollCmds.isNotEmpty()) {
                pollingJob = scope.launch {
                    while (isActive) {
                        try {
                            for (cmd in pollCmds) {
                                p.write(writeChar, cmd, WriteType.WithoutResponse)
                                delay(50)
                            }
                        } catch (_: kotlinx.coroutines.CancellationException) { throw it }
                        catch (_: Exception) { /* retry next cycle */ }
                        delay(proto.pollIntervalMs)
                    }
                }
            }

            _connectionState.value = ConnectionState.Connected(vehicle)
            if (vehicle != null) vehicleRepository.touch(vehicle.id)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            _connectionState.value = ConnectionState.Failed(e.message ?: "Connection failed")
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        pollingJob?.cancel(); pollingJob = null
        observeJob?.cancel(); observeJob = null
        try { peripheral?.disconnect() } catch (_: Exception) {}
        peripheral = null
        protocol?.reset(); protocol = null
        _activeData.value = BmsData()
        _activeVehicle.value = null
        ringBuffer.clear()
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun samples(window: Duration): Flow<List<BmsData>> =
        _activeData.map { ringBuffer.within(window) }

    override fun movingAverage(window: Duration): StateFlow<MovingAvg> {
        val flow = MutableStateFlow(MovingAvg(0f, 0f, window))
        scope.launch {
            _activeData.collect {
                flow.value = MovingAverage.over(ringBuffer.within(window), window)
            }
        }
        return flow.asStateFlow()
    }

    private fun createProtocol(type: BmsType): BmsProtocol = when (type) {
        BmsType.JK_BMS -> JkBmsProtocol()
        BmsType.JBD_BMS -> JbdBmsProtocol()
        BmsType.ANT_BMS -> AntBmsProtocol()
        BmsType.DALY_BMS -> DalyBmsProtocol()
    }
}
```

- [ ] **Step 2: Register in `AppModule.kt`**

Update `composeApp/src/commonMain/kotlin/com/volty/app/di/AppModule.kt`:

```kotlin
package com.volty.app.di

import com.volty.app.data.ble.KableBmsRepository
import com.volty.app.data.memory.InMemoryVehicleRepository
import com.volty.app.domain.repository.BmsRepository
import com.volty.app.domain.repository.VehicleRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val appModule = module {
    singleOf(::InMemoryVehicleRepository) bind VehicleRepository::class
    singleOf(::KableBmsRepository) bind BmsRepository::class
}
```

- [ ] **Step 3: Compile check**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

If `Scanner().advertisements.first { ... }` is not resolved, ensure `import kotlinx.coroutines.flow.first` is present.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/volty/app/data/ble/KableBmsRepository.kt composeApp/src/commonMain/kotlin/com/volty/app/di/AppModule.kt
git commit -m "feat(ble): KableBmsRepository — scanAll, connect, observe, ring buffer + DI wiring"
```

---

## Task 17: Decompose RootComponent + DebugComponent + DebugScreen

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/root/RootComponent.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/root/RootScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/debug/DebugComponent.kt`
- Create: `composeApp/src/commonMain/kotlin/com/volty/app/presentation/debug/DebugScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/volty/app/App.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/volty/app/MainActivity.kt`

- [ ] **Step 1: Write `RootComponent.kt`**

For plan 1 the root only hosts the debug screen.

```kotlin
package com.volty.app.presentation.root

import com.arkivanov.decompose.ComponentContext
import com.volty.app.presentation.debug.DebugComponent
import com.volty.app.presentation.debug.DefaultDebugComponent
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

interface RootComponent {
    val debug: DebugComponent
}

class DefaultRootComponent(
    componentContext: ComponentContext
) : RootComponent, ComponentContext by componentContext, KoinComponent {

    override val debug: DebugComponent = DefaultDebugComponent(
        componentContext = componentContext,
        bmsRepository = get(),
        vehicleRepository = get()
    )
}
```

- [ ] **Step 2: Write `DebugComponent.kt`**

```kotlin
package com.volty.app.presentation.debug

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope
import com.volty.app.domain.model.BmsData
import com.volty.app.domain.model.BmsType
import com.volty.app.domain.model.Chemistry
import com.volty.app.domain.model.ConnectionState
import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.repository.BmsRepository
import com.volty.app.domain.repository.DiscoveredDevice
import com.volty.app.domain.repository.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.random.Random

interface DebugComponent {
    val state: StateFlow<State>

    fun onScanClicked()
    fun onConnect(device: DiscoveredDevice)
    fun onDisconnect()

    data class State(
        val devices: List<DiscoveredDevice> = emptyList(),
        val connection: ConnectionState = ConnectionState.Idle,
        val data: BmsData = BmsData(),
        val isScanning: Boolean = false
    )
}

class DefaultDebugComponent(
    componentContext: ComponentContext,
    private val bmsRepository: BmsRepository,
    private val vehicleRepository: VehicleRepository
) : DebugComponent, ComponentContext by componentContext {

    private val scope: CoroutineScope = coroutineScope(Dispatchers.Main)

    private val _state = MutableStateFlow(DebugComponent.State())
    override val state: StateFlow<DebugComponent.State> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        scope.launch {
            bmsRepository.connectionState.collect { c ->
                _state.update { it.copy(connection = c) }
            }
        }
        scope.launch {
            bmsRepository.activeData.collect { d ->
                _state.update { it.copy(data = d) }
            }
        }
    }

    override fun onScanClicked() {
        if (scanJob?.isActive == true) {
            scanJob?.cancel()
            _state.update { it.copy(isScanning = false) }
            return
        }
        _state.update { it.copy(devices = emptyList(), isScanning = true) }
        scanJob = scope.launch {
            bmsRepository.scanAll().collect { d ->
                _state.update { s ->
                    val merged = (s.devices.filterNot { it.address == d.address } + d)
                        .sortedByDescending { it.rssi }
                    s.copy(devices = merged)
                }
            }
        }
    }

    override fun onConnect(device: DiscoveredDevice) {
        scope.launch {
            scanJob?.cancel()
            _state.update { it.copy(isScanning = false) }
            // Build a transient Vehicle for non-saved (guest-style) connection.
            // Save it via VehicleRepository so we have an id; SQLDelight will replace this layer in plan 2.
            val v = Vehicle(
                id = "auto-${Random.nextLong()}",
                name = device.name ?: "BMS-${device.address.takeLast(4)}",
                iconKey = "generic",
                bmsType = device.bmsType,
                bmsAddress = device.address,
                chemistry = Chemistry.LI_ION_NMC,
                createdAt = Clock.System.now()
            )
            vehicleRepository.upsert(v)
            bmsRepository.connect(v)
        }
    }

    override fun onDisconnect() {
        scope.launch { bmsRepository.disconnect() }
    }
}
```

- [ ] **Step 3: Write `DebugScreen.kt`**

```kotlin
package com.volty.app.presentation.debug

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.volty.app.domain.model.ConnectionState
import com.volty.app.domain.repository.DiscoveredDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(component: DebugComponent) {
    val state by component.state.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Volty debug") }) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(12.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Connection: ${state.connection::class.simpleName}", style = MaterialTheme.typography.labelLarge)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = component::onScanClicked) {
                    Text(if (state.isScanning) "Stop scan" else "Scan BMS")
                }
                if (state.connection is ConnectionState.Connected) {
                    OutlinedButton(onClick = component::onDisconnect) { Text("Disconnect") }
                }
            }

            HorizontalDivider()

            Text("Discovered (${state.devices.size})", style = MaterialTheme.typography.labelMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                items(state.devices, key = { it.address }) { d ->
                    DeviceRow(d, onClick = { component.onConnect(d) })
                }
            }

            HorizontalDivider()

            if (state.connection is ConnectionState.Connected) {
                Text("Live data", style = MaterialTheme.typography.labelMedium)
                val d = state.data
                Text("SOC: %.0f%%  V: %.2f  A: %+.2f  W: %+.1f".format(d.soc, d.voltage, d.current, d.power))
                Text("Charge: %.2f / %.2f Ah   Cycles: %d".format(d.charge, d.capacity, d.numCycles))
                Text("Cells (${d.cellVoltages.size}): " + d.cellVoltages.joinToString(", ") { "%.3f".format(it) })
                Text("Temps: " + d.temperatures.joinToString(", ") { "%.1f°C".format(it) })
            }
        }
    }
}

@Composable
private fun DeviceRow(d: DiscoveredDevice, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(d.name ?: d.address) },
        supportingContent = { Text("${d.bmsType.label}  ·  ${d.rssi} dBm") },
        trailingContent = { TextButton(onClick = onClick) { Text("Connect") } }
    )
}
```

- [ ] **Step 4: Update `App.kt`**

```kotlin
package com.volty.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.volty.app.presentation.debug.DebugScreen
import com.volty.app.presentation.root.RootComponent

@Composable
fun App(root: RootComponent) {
    MaterialTheme {
        Surface { DebugScreen(root.debug) }
    }
}
```

- [ ] **Step 5: Update `MainActivity.kt` to build the root with Decompose**

```kotlin
package com.volty.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.arkivanov.decompose.defaultComponentContext
import com.volty.app.presentation.root.DefaultRootComponent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = DefaultRootComponent(defaultComponentContext())
        setContent { App(root) }
    }
}
```

- [ ] **Step 6: Build**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/
git commit -m "feat(ui): debug screen — scan BMS, connect, observe live BmsData"
```

---

## Task 18: Smoke test on real Android device

This is a manual verification step, not code. Goal: prove plan 1's foundation actually works against real hardware.

- [ ] **Step 1: Install APK**

Connect an Android phone (Android 10+ with Bluetooth) via USB, enable USB debugging.

Run: `./gradlew :composeApp:installDebug`
Expected: app installed.

- [ ] **Step 2: Launch Volty, grant BLE permissions**

The app should prompt for `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` (Android 12+) or `ACCESS_FINE_LOCATION` (Android 10/11). Approve.

- [ ] **Step 3: Power on a real BMS within ~10 m**

Any of JK / JBD / ANT / Daly works.

- [ ] **Step 4: Tap "Scan BMS"**

Expected: the BMS appears in the list within 5-10 s, with `BmsType` label and RSSI.

- [ ] **Step 5: Tap "Connect"**

Expected: connection state transitions Idle → Connecting → Connected. Live BMS data starts updating within 2-5 s. Cell voltages, pack voltage, current, SOC, temps all populate.

- [ ] **Step 6: Tap "Disconnect"**

Expected: state goes back to Disconnected, live values clear.

- [ ] **Step 7: Document any issues in `docs/qa/plan-1-smoke-test.md`**

Create the file with a one-line entry per BMS tested:

```
- JK BMS (model XYZ): scan ✓ connect ✓ live data ✓
- JBD BMS (model XYZ): scan ✓ connect ✓ live data ✓
- (if not tested) ANT BMS: deferred — no hardware
- (if not tested) Daly BMS: deferred — no hardware
```

If a particular BMS shows but parse fails — open a follow-up issue / TODO; do not block plan completion on BMS hardware not available.

- [ ] **Step 8: Commit the smoke-test note**

```bash
git add docs/qa/
git commit -m "docs: plan-1 smoke-test record"
```

---

## Self-review checklist (engineer must run before declaring plan complete)

- [ ] All tests pass: `./gradlew :composeApp:testDebugUnitTest`
- [ ] App builds: `./gradlew :composeApp:assembleDebug`
- [ ] Smoke test on at least one real BMS succeeded (Task 18)
- [ ] Git log shows one commit per task (`git log --oneline | head -20`)
- [ ] No `TODO` / `FIXME` left in code added by this plan

## Definition of done

- Volty app installs on Android 10+
- User can scan for BLE BMS devices
- All 4 protocols (JK/JBD/ANT/Daly) parse synthetic frames correctly (unit tests pass)
- Connecting to a real BMS produces a live `BmsData` stream visible on the debug screen
- Ring buffer accumulates samples (verifiable by reading `KableBmsRepository.samples` in debugger, but no UI for this in plan 1)

What's intentionally not done in plan 1:
- SQLDelight persistence (plan 2)
- Welcome / Permissions / Scanning / AutoConnect / Picker screens (plan 2)
- Dashboard, Cells, Graph, Settings, VehicleEdit screens (plan 3)
- Material 3 Expressive theme (plan 3)
- Foreground service + alerts (plan 4)
