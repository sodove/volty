package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.flow.toList
import ru.sodovaya.volty.domain.model.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KableBmsRepositoryBluetoothGuardTest {
    @Test
    fun scan_is_empty_and_does_not_touch_kable_when_bluetooth_is_off() = bleRepositoryTest(
        bleAdapterStateProvider = BleAdapterStateProvider { false },
    ) { repository ->
        val devices = repository.scanAll().toList()

        assertEquals(emptyList(), devices)
        val state = assertIs<ConnectionState.Failed>(repository.connectionState.value)
        assertEquals(BLUETOOTH_DISABLED_REASON, state.reason)
    }
}
