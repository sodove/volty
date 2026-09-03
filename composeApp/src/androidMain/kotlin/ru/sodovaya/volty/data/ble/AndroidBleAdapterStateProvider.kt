package ru.sodovaya.volty.data.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context

class AndroidBleAdapterStateProvider(context: Context) : BleAdapterStateProvider {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)

    override fun isBluetoothEnabled(): Boolean = runCatching {
        bluetoothManager?.adapter?.state == BluetoothAdapter.STATE_ON
    }.getOrDefault(false)
}
