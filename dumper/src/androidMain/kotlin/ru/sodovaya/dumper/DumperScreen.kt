package ru.sodovaya.dumper

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val AUTO_STOP_MS = 60_000L

@Composable
fun DumperScreen(
    writer: DumpWriter,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val recorder = remember { DumpRecorder(scope) }
    val sanity = remember { FrameSanity() }
    val devices by recorder.devices.collectAsState()
    val error by recorder.error.collectAsState()

    var recording by remember { mutableStateOf(false) }
    // Device we are currently connecting to; non-null makes the list inert.
    var connectingTo by remember { mutableStateOf<DumpRecorder.Device?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(0L) }
    var observations by remember { mutableStateOf(sanity.observations()) }
    var finishedPath by remember { mutableStateOf<String?>(null) }

    // Shared by the Stop button and the auto-stop: finalize the file on disk
    // and hand it to the share sheet.
    fun stopAndFinish() {
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
    }

    // Auto-stop so an abandoned recording cannot grow without bound. Goes
    // through the same finish path as the button, so the dump is not lost.
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        delay(AUTO_STOP_MS)
        stopAndFinish()
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Volty dumper", fontSize = 20.sp)

        if (!permissionsGranted) {
            Text("Bluetooth permissions are needed to scan and record.")
            Button(onClick = onRequestPermissions) { Text("Grant permissions") }
        } else if (!recording && connectingTo == null) {
            Button(onClick = {
                finishedPath = null
                scanning = true
                recorder.startScan()
            }) { Text("Scan") }
        }

        error?.let { Text("Error: $it") }

        connectingTo?.let { d ->
            Text("Connecting to ${d.name ?: d.address}…")
        }

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
            Button(onClick = { stopAndFinish() }) { Text("Stop and send") }
        } else {
            finishedPath?.let { Text("Saved: $it") }
            if (permissionsGranted && scanning && connectingTo == null && devices.isEmpty()) {
                Text("Scanning…")
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(devices) { d ->
                    Card(
                        // Inert while a connection is in flight — a second tap
                        // must not race the first.
                        Modifier.fillMaxWidth().clickable(enabled = connectingTo == null) {
                            connectingTo = d
                            scanning = false
                            scope.launch {
                                sanity.reset()
                                writer.begin(d.name, d.address)
                                // Monotonic clock: a wall-clock adjustment
                                // mid-capture must not skew the t_ms column.
                                startedAt = SystemClock.elapsedRealtime()
                                observations = sanity.observations()
                                val result = recorder.record(d) { chunk ->
                                    sanity.feed(chunk)
                                    writer.append(SystemClock.elapsedRealtime() - startedAt, chunk)
                                    observations = sanity.observations()
                                }
                                connectingTo = null
                                // Failure text arrives via recorder.error.
                                recording = result.isSuccess
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
