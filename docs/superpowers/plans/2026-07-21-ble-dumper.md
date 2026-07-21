# BLE Dumper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Отдельное Android-приложение `:dumper`, которое пишет сырой поток нотификаций с колеса Begode в файл и показывает на экране, годен ли дамп.

**Architecture:** Новый Gradle-модуль в репозитории volty, KMP с единственным `androidTarget` (ради переиспользования плагинов из каталога), без зависимостей от кода volty. Пять файлов: чистая логика подсчёта кадров, BLE-приёмник на Kable, писатель файла, экран и активность. Тестируется только чистая часть.

**Tech Stack:** Kotlin 2.3.20, Compose Multiplatform 1.11.0, Kable 0.43.0, AGP 8.13.2, kotlin-test.

**Спека:** `docs/superpowers/specs/2026-07-21-ble-dumper-design.md`

## Global Constraints

- Модуль лежит в `dumper/`, пакет и `namespace` — `ru.sodovaya.dumper`, `applicationId` — `ru.sodovaya.dumper`.
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`.
- Только debug-сборка. Релизная конфигурация подписи не добавляется.
- Ноль зависимостей от модуля `:composeApp`. Код не переиспользуется копированием без нужды.
- Сборка: `./gradlew :dumper:assembleDebug`; тесты: `./gradlew :dumper:testDebugUnitTest`.
- Комментарии в коде на английском, как во всём репозитории.
- **Приложение никогда ничего не пишет в характеристику.** FFE1 у Begode — командный канал; запись туда может перенастроить колесо под человеком.
- Сервис `0000ffe0-0000-1000-8000-00805f9b34fb`, характеристика `0000ffe1-0000-1000-8000-00805f9b34fb`.
- Кадр: заголовок `55 AA`, длина 24 байта, хвост `5A 5A 5A 5A` в байтах 20..23, тип в байте 18, номер в байте 19.
- После каждой задачи `./gradlew :composeApp:testDebugUnitTest` тоже должен оставаться зелёным — новый модуль не имеет права задеть volty.

---

### Task 1: Модуль и подсчёт кадров

Скелет модуля плюс вся чистая логика. BLE и файлов здесь нет, поэтому задача целиком проверяется тестами.

**Files:**
- Modify: `settings.gradle.kts`
- Create: `dumper/build.gradle.kts`
- Create: `dumper/src/androidMain/AndroidManifest.xml`
- Create: `dumper/src/androidMain/res/xml/file_paths.xml`
- Create: `dumper/src/commonMain/kotlin/ru/sodovaya/dumper/FrameSanity.kt`
- Test: `dumper/src/commonTest/kotlin/ru/sodovaya/dumper/FrameSanityTest.kt`

**Interfaces:**
- Consumes: ничего.
- Produces: `FrameSanity` с `fun feed(chunk: ByteArray)`, `fun observations(): FrameSanity.Observations`, `fun reset()`; `FrameSanity.Observations(notifications: Int, bytes: Int, frames: Int, frameTypes: Set<Int>, bmsNums: Set<Int>, maxCellPacket: Int)`.

- [ ] **Step 1: Подключить модуль**

В `settings.gradle.kts` после `include(":composeApp")` добавить:

```kotlin
include(":dumper")
```

- [ ] **Step 2: Создать `dumper/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
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
            implementation(libs.coroutines.core)
            implementation(libs.kable.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.coroutines.android)
        }
    }
}

android {
    namespace = "ru.sodovaya.dumper"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.sodovaya.dumper"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Debug only: this is a field tool, never published.
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}
```

- [ ] **Step 3: Создать манифест**

`dumper/src/androidMain/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation"
        tools:targetApi="31" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <!-- Legacy Bluetooth permissions: required for BLE scan/connect on
         API 26-30 (minSdk 26). Without ACCESS_FINE_LOCATION a scan on those
         versions silently returns nothing. -->
    <uses-permission android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="30" />

    <application
        android:label="Volty Dumper"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Shares the recorded dump file with the chosen app (see DumpWriter). -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>
```

- [ ] **Step 4: Создать `file_paths.xml`**

`dumper/src/androidMain/res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- Matches File(context.cacheDir, "dumps") in DumpWriter. -->
    <cache-path name="dumps" path="dumps/" />
</paths>
```

- [ ] **Step 5: Написать падающие тесты `FrameSanity`**

`dumper/src/commonTest/kotlin/ru/sodovaya/dumper/FrameSanityTest.kt`:

```kotlin
package ru.sodovaya.dumper

import kotlin.test.Test
import kotlin.test.assertEquals

class FrameSanityTest {

    /**
     * Builds a well-formed 24-byte Begode frame: 0x55 0xAA header, [type] at
     * index 18, [num] at index 19, 0x5A 0x5A 0x5A 0x5A tail at 20..23.
     */
    private fun frame(type: Int, num: Int): ByteArray {
        val f = ByteArray(24)
        f[0] = 0x55
        f[1] = 0xAA.toByte()
        // Bytes 2..17 are payload; their values do not matter to FrameSanity.
        for (i in 2..17) f[i] = 0x11
        f[18] = type.toByte()
        f[19] = num.toByte()
        for (i in 20..23) f[i] = 0x5A
        return f
    }

    @Test
    fun countsAWellFormedFrame() {
        val s = FrameSanity()
        s.feed(frame(type = 0x00, num = 0))
        val o = s.observations()
        assertEquals(1, o.notifications)
        assertEquals(24, o.bytes)
        assertEquals(1, o.frames)
        assertEquals(setOf(0x00), o.frameTypes)
    }

    @Test
    fun collectsBmsNumsOnlyFromTypeOne() {
        val s = FrameSanity()
        s.feed(frame(type = 0x01, num = 0))
        s.feed(frame(type = 0x01, num = 3))
        s.feed(frame(type = 0x04, num = 7))
        assertEquals(setOf(0, 3), s.observations().bmsNums)
    }

    @Test
    fun tracksTheHighestCellPacketIndex() {
        val s = FrameSanity()
        s.feed(frame(type = 0x02, num = 1))
        s.feed(frame(type = 0x03, num = 4))
        s.feed(frame(type = 0x02, num = 2))
        // Type 0x01's num must not leak into the cell-packet maximum.
        s.feed(frame(type = 0x01, num = 9))
        assertEquals(4, s.observations().maxCellPacket)
    }

    @Test
    fun reassemblesAFrameSplitAcrossChunks() {
        // At MTU 23 a 24-byte frame ALWAYS arrives split — this is the normal
        // case on a real wheel, not an edge case.
        val f = frame(type = 0x02, num = 5)
        val s = FrameSanity()
        s.feed(f.copyOfRange(0, 20))
        s.feed(f.copyOfRange(20, 24))
        val o = s.observations()
        assertEquals(2, o.notifications)
        assertEquals(24, o.bytes)
        assertEquals(1, o.frames)
        assertEquals(setOf(0x02), o.frameTypes)
        assertEquals(5, o.maxCellPacket)
    }

    @Test
    fun resynchronisesAfterLeadingGarbage() {
        val s = FrameSanity()
        s.feed(byteArrayOf(0x4E, 0x41, 0x4D, 0x45)) // "NAME" — the wheel's ASCII preamble
        s.feed(frame(type = 0x04, num = 0))
        assertEquals(1, s.observations().frames)
        assertEquals(setOf(0x04), s.observations().frameTypes)
    }

    @Test
    fun rejectsAFrameWithABrokenTailAndStillFindsTheNextOne() {
        val bad = frame(type = 0x00, num = 0).also { it[23] = 0x00 }
        val s = FrameSanity()
        s.feed(bad)
        assertEquals(0, s.observations().frames)
        s.feed(frame(type = 0x01, num = 2))
        assertEquals(1, s.observations().frames)
        assertEquals(setOf(0x01), s.observations().frameTypes)
        assertEquals(setOf(2), s.observations().bmsNums)
    }

    @Test
    fun countsTwoFramesArrivingInOneChunk() {
        val s = FrameSanity()
        s.feed(frame(type = 0x00, num = 0) + frame(type = 0x04, num = 0))
        val o = s.observations()
        assertEquals(1, o.notifications)
        assertEquals(48, o.bytes)
        assertEquals(2, o.frames)
        assertEquals(setOf(0x00, 0x04), o.frameTypes)
    }

    @Test
    fun resetClearsEverything() {
        val s = FrameSanity()
        s.feed(frame(type = 0x01, num = 3))
        s.reset()
        val o = s.observations()
        assertEquals(0, o.notifications)
        assertEquals(0, o.bytes)
        assertEquals(0, o.frames)
        assertEquals(emptySet(), o.frameTypes)
        assertEquals(emptySet(), o.bmsNums)
        assertEquals(-1, o.maxCellPacket)
    }
}
```

- [ ] **Step 6: Запустить тесты и убедиться, что они падают**

Run: `./gradlew :dumper:testDebugUnitTest`
Expected: FAIL — `Unresolved reference: FrameSanity`.

- [ ] **Step 7: Реализовать `FrameSanity`**

`dumper/src/commonMain/kotlin/ru/sodovaya/dumper/FrameSanity.kt`:

```kotlin
package ru.sodovaya.dumper

/**
 * Counts Begode frames in a raw notification stream, and nothing else.
 *
 * Pure: no BLE, no files, no decoding of voltages, currents or temperatures.
 * It reads exactly two bytes at known offsets — the frame type and the frame
 * number — because those are the open questions the dump exists to answer.
 * Interpreting the payload is BegodeProtocol's job.
 */
class FrameSanity {

    data class Observations(
        val notifications: Int,
        val bytes: Int,
        val frames: Int,
        val frameTypes: Set<Int>,
        val bmsNums: Set<Int>,
        /** Highest packet index seen on a cell frame, or -1 when none arrived. */
        val maxCellPacket: Int
    )

    private companion object {
        const val FRAME_SIZE = 24
        const val TYPE_INDEX = 18
        const val NUM_INDEX = 19
        const val TAIL_START = 20
        const val HEADER_0 = 0x55
        const val HEADER_1 = 0xAA
        const val TAIL_BYTE = 0x5A
        const val TYPE_BMS = 0x01
        const val TYPE_CELLS_1 = 0x02
        const val TYPE_CELLS_2 = 0x03
    }

    private val buffer = ArrayList<Int>()

    private var notifications = 0
    private var bytes = 0
    private var frames = 0
    private val frameTypes = mutableSetOf<Int>()
    private val bmsNums = mutableSetOf<Int>()
    private var maxCellPacket = -1

    fun feed(chunk: ByteArray) {
        notifications++
        bytes += chunk.size
        for (b in chunk) buffer.add(b.toInt() and 0xFF)
        drain()
    }

    fun observations(): Observations = Observations(
        notifications = notifications,
        bytes = bytes,
        frames = frames,
        frameTypes = frameTypes.toSet(),
        bmsNums = bmsNums.toSet(),
        maxCellPacket = maxCellPacket
    )

    fun reset() {
        buffer.clear()
        notifications = 0
        bytes = 0
        frames = 0
        frameTypes.clear()
        bmsNums.clear()
        maxCellPacket = -1
    }

    /**
     * Consume every complete frame currently buffered. On a header whose tail
     * does not check out, advance by a single byte rather than a whole frame:
     * a 0x55 0xAA pair can legitimately occur inside payload data, and skipping
     * 24 bytes on a false positive would swallow the real frame behind it.
     */
    private fun drain() {
        var i = 0
        while (true) {
            val start = indexOfHeader(from = i) ?: break
            if (buffer.size - start < FRAME_SIZE) {
                i = start
                break
            }
            if (hasValidTail(start)) {
                record(start)
                i = start + FRAME_SIZE
            } else {
                i = start + 1
            }
        }
        // Drop everything before the first byte still in play, so the buffer
        // does not grow without bound across a long recording.
        if (i > 0) repeat(minOf(i, buffer.size)) { buffer.removeAt(0) }
    }

    private fun indexOfHeader(from: Int): Int? {
        var i = from
        while (i + 1 < buffer.size) {
            if (buffer[i] == HEADER_0 && buffer[i + 1] == HEADER_1) return i
            i++
        }
        return null
    }

    private fun hasValidTail(start: Int): Boolean =
        (TAIL_START until FRAME_SIZE).all { buffer[start + it] == TAIL_BYTE }

    private fun record(start: Int) {
        frames++
        val type = buffer[start + TYPE_INDEX]
        val num = buffer[start + NUM_INDEX]
        frameTypes.add(type)
        when (type) {
            TYPE_BMS -> bmsNums.add(num)
            TYPE_CELLS_1, TYPE_CELLS_2 -> if (num > maxCellPacket) maxCellPacket = num
        }
    }
}
```

- [ ] **Step 8: Запустить тесты и убедиться, что они проходят**

Run: `./gradlew :dumper:testDebugUnitTest`
Expected: PASS, 8 тестов.

- [ ] **Step 9: Убедиться, что volty не задет**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: PASS, 178 тестов.

- [ ] **Step 10: Коммит**

```bash
git add settings.gradle.kts dumper/
git commit -m "feat(dumper): module skeleton and frame counting"
```

---

### Task 2: Запись потока и выдача файла

BLE-приёмник и писатель файла. Тестов нет — обе части целиком в платформенном вводе-выводе; их проверка на устройстве в Task 3.

**Files:**
- Create: `dumper/src/commonMain/kotlin/ru/sodovaya/dumper/DumpRecorder.kt`
- Create: `dumper/src/androidMain/kotlin/ru/sodovaya/dumper/DumpWriter.kt`

**Interfaces:**
- Consumes: `FrameSanity` из Task 1.
- Produces: `DumpRecorder(scope: CoroutineScope)` с `val devices: StateFlow<List<DumpRecorder.Device>>`, `fun startScan()`, `fun stopScan()`, `suspend fun record(device: Device, onChunk: (ByteArray) -> Unit): Result<Unit>`, `fun stop()`; `DumpRecorder.Device(address: String, name: String?, rssi: Int)`. `DumpWriter(context: Context)` с `fun begin(deviceName: String?, address: String)`, `fun append(elapsedMs: Long, chunk: ByteArray)`, `fun finish(summary: List<String>): File`, `fun share(file: File)`.

- [ ] **Step 1: Создать `DumpRecorder`**

`dumper/src/commonMain/kotlin/ru/sodovaya/dumper/DumpRecorder.kt`:

```kotlin
package ru.sodovaya.dumper

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Scans for BLE devices and streams one device's notifications out through a
 * callback.
 *
 * It NEVER writes to the characteristic. FFE1 on a Begode wheel is the command
 * channel — light, pedal mode, tiltback — so sending anything at it without
 * knowing the protocol could reconfigure the wheel under its rider. This tool
 * only listens.
 */
@OptIn(ExperimentalUuidApi::class)
class DumpRecorder(private val scope: CoroutineScope) {

    data class Device(val address: String, val name: String?, val rssi: Int)

    private companion object {
        const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
        const val NOTIFY_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    }

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private var scanJob: Job? = null
    private var recordJob: Job? = null
    private var peripheral: Peripheral? = null

    /**
     * Lists every advertising device, unfiltered. A Begode module's local name
     * is not known in advance and it does not always advertise FFE0, so
     * filtering here would hide the very device we came for.
     */
    fun startScan() {
        scanJob?.cancel()
        _devices.value = emptyList()
        scanJob = scope.launch {
            val seen = LinkedHashMap<String, Device>()
            Scanner().advertisements.collect { ad ->
                val id = ad.identifier.toString()
                seen[id] = Device(address = id, name = ad.name, rssi = ad.rssi)
                _devices.value = seen.values.sortedByDescending { it.rssi }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    /**
     * Connect and pump notifications into [onChunk] until [stop] is called.
     * Returns once the subscription is live; failures come back as
     * [Result.failure] so the screen can show them without crashing.
     */
    suspend fun record(device: Device, onChunk: (ByteArray) -> Unit): Result<Unit> = try {
        stopScan()
        val advertisement = Scanner().advertisements.first {
            it.identifier.toString() == device.address
        }
        val p = Peripheral(advertisement)
        peripheral = p
        p.connect()
        val notify = characteristicOf(
            service = Uuid.parse(SERVICE_UUID),
            characteristic = Uuid.parse(NOTIFY_UUID)
        )
        recordJob = scope.launch {
            // Wait for service discovery before subscribing: peripheral.services
            // is null until it completes.
            p.services.filterNotNull().first()
            p.observe(notify).collect { onChunk(it) }
        }
        Result.success(Unit)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun stop() {
        scope.launch {
            recordJob?.cancelAndJoin()
            recordJob = null
            runCatching { peripheral?.disconnect() }
            peripheral = null
        }
    }
}
```

- [ ] **Step 2: Создать `DumpWriter`**

`dumper/src/androidMain/kotlin/ru/sodovaya/dumper/DumpWriter.kt`:

```kotlin
package ru.sodovaya.dumper

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Accumulates the recorded stream in memory, writes it to a file in the cache
 * directory and hands it to the system share sheet.
 *
 * One line per notification rather than per frame: at MTU 23 a 24-byte frame
 * always arrives split, and exactly how it is split is itself useful evidence
 * for whoever writes the parser.
 */
class DumpWriter(private val context: Context) {

    private val lines = StringBuilder()

    fun begin(deviceName: String?, address: String) {
        lines.setLength(0)
        lines.appendLine("# volty-dumper 1.0")
        lines.appendLine("# device: ${deviceName ?: "(no name)"} ($address)")
        lines.appendLine("# service 0000ffe0 / char 0000ffe1")
        lines.appendLine("# columns: t_ms hex")
    }

    fun append(elapsedMs: Long, chunk: ByteArray) {
        lines.append(elapsedMs).append('\t').appendLine(chunk.toHex())
    }

    fun finish(summary: List<String>): File {
        lines.appendLine("# --- summary ---")
        summary.forEach { lines.append("# ").appendLine(it) }
        val dir = File(context.cacheDir, "dumps").apply { mkdirs() }
        // Fixed name — overwritten each run, so the cache never grows.
        return File(dir, "begode-dump.txt").apply { writeText(lines.toString()) }
    }

    fun share(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Begode BLE dump")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Send dump").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append("0123456789abcdef"[v ushr 4])
            out.append("0123456789abcdef"[v and 0x0F])
        }
        return out.toString()
    }
}
```

- [ ] **Step 3: Собрать**

Run: `./gradlew :dumper:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Коммит**

```bash
git add dumper/
git commit -m "feat(dumper): BLE recorder and dump file writer"
```

---

### Task 3: Экран и сборка APK

Активность, разрешения и единственный экран. Здесь всё сходится: скан → запись → счётчики → отправка.

**Files:**
- Create: `dumper/src/androidMain/kotlin/ru/sodovaya/dumper/MainActivity.kt`
- Create: `dumper/src/androidMain/kotlin/ru/sodovaya/dumper/DumperScreen.kt`

**Interfaces:**
- Consumes: `FrameSanity` (Task 1), `DumpRecorder`, `DumpWriter` (Task 2).
- Produces: устанавливаемый APK.

- [ ] **Step 1: Создать `MainActivity`**

`dumper/src/androidMain/kotlin/ru/sodovaya/dumper/MainActivity.kt`:

```kotlin
package ru.sodovaya.dumper

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Pre-31 a BLE scan returns nothing without location permission.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val launcher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { /* the screen reports what it can and cannot do */ }

        setContent {
            MaterialTheme {
                DumperScreen(
                    writer = DumpWriter(applicationContext),
                    onRequestPermissions = { launcher.launch(permissions) }
                )
            }
        }
    }
}
```

- [ ] **Step 2: Создать `DumperScreen`**

`dumper/src/androidMain/kotlin/ru/sodovaya/dumper/DumperScreen.kt`:

```kotlin
package ru.sodovaya.dumper

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val AUTO_STOP_MS = 60_000L

@Composable
fun DumperScreen(writer: DumpWriter, onRequestPermissions: () -> Unit) {
    val scope = rememberCoroutineScope()
    val recorder = remember { DumpRecorder(scope) }
    val sanity = remember { FrameSanity() }
    val devices by recorder.devices.collectAsState()

    var recording by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var observations by remember { mutableStateOf(sanity.observations()) }
    var finishedPath by remember { mutableStateOf<String?>(null) }

    // Auto-stop so an abandoned recording cannot grow without bound.
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        delay(AUTO_STOP_MS)
        recorder.stop()
        recording = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Volty dumper", fontSize = 20.sp)

        if (!recording) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequestPermissions) { Text("Permissions") }
                Button(onClick = { recorder.startScan() }) { Text("Scan") }
            }
        }

        error?.let { Text("Error: $it") }

        if (recording) {
            val o = observations
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("notifications ${o.notifications}   bytes ${o.bytes}")
                    Text("frames ${o.frames}")
                    Text("frame types: ${o.frameTypes.sorted().joinToString(" ") { it.toHex2() }}")
                    Text("bmsnum (type 01): ${o.bmsNums.sorted().joinToString(" ")}")
                    Text("max cell packet: ${if (o.maxCellPacket < 0) "—" else o.maxCellPacket.toString()}")
                }
            }
            Button(onClick = {
                recorder.stop()
                recording = false
                val o = sanity.observations()
                val file = writer.finish(
                    listOf(
                        "notifications ${o.notifications}  bytes ${o.bytes}  frames ${o.frames}",
                        "frame types seen: ${o.frameTypes.sorted().joinToString(" ") { it.toHex2() }}",
                        "bmsnum values (type 01): ${o.bmsNums.sorted().joinToString(" ")}",
                        "max cell packet index (types 02/03): ${o.maxCellPacket}"
                    )
                )
                finishedPath = file.absolutePath
                writer.share(file)
            }) { Text("Stop and send") }
        } else {
            finishedPath?.let { Text("Saved: $it") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(devices) { d ->
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                sanity.reset()
                                writer.begin(d.name, d.address)
                                startedAt = System.currentTimeMillis()
                                error = null
                                observations = sanity.observations()
                                val result = recorder.record(d) { chunk ->
                                    sanity.feed(chunk)
                                    writer.append(System.currentTimeMillis() - startedAt, chunk)
                                    observations = sanity.observations()
                                }
                                if (result.isFailure) {
                                    error = result.exceptionOrNull()?.message ?: "connect failed"
                                } else {
                                    recording = true
                                }
                            }
                        }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(d.name ?: "(no name)")
                            Text("${d.address}   ${d.rssi} dBm", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun Int.toHex2(): String = toString(16).padStart(2, '0')
```

- [ ] **Step 3: Собрать APK**

Run: `./gradlew :dumper:assembleDebug`
Expected: BUILD SUCCESSFUL; APK в `dumper/build/outputs/apk/debug/`.

- [ ] **Step 4: Убедиться, что volty по-прежнему цел**

Run: `./gradlew :composeApp:testDebugUnitTest && ./gradlew :dumper:testDebugUnitTest`
Expected: PASS — 178 и 8 тестов.

- [ ] **Step 5: Коммит**

```bash
git add dumper/
git commit -m "feat(dumper): recording screen and permissions"
```

---

## Проверка перед сдачей плана

- Спека покрыта: конфигурация модуля и разрешения → Task 1; формат файла и «никогда не пишем в характеристику» → Task 2; счётчики, отказы и автостоп → Task 3; тесты `FrameSanity` → Task 1.
- Имена и сигнатуры между задачами согласованы: `FrameSanity.feed/observations/reset`, `DumpRecorder.Device/startScan/record/stop`, `DumpWriter.begin/append/finish/share`.
- Задача 1 полностью проверяема тестами; задачи 2 и 3 проверяются сборкой и, окончательно, съёмом реального дампа на устройстве — это делает человек.
