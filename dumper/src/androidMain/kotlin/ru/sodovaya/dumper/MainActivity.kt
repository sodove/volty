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
