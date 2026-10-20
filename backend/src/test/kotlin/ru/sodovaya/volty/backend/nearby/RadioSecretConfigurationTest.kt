package ru.sodovaya.volty.backend.nearby

import java.util.Base64
import kotlin.test.*

class RadioSecretConfigurationTest {
    private fun b64(size:Int)=Base64.getEncoder().encodeToString(ByteArray(size) {it.toByte()})
    private fun parse(value:String?)=NearbyBackendConfiguration.parseRadioSecret(value)

    @Test fun absentOrBlankDisablesRadioKeys() {
        assertNull(parse(null));assertNull(parse("   "))
    }
    @Test fun invalidBase64DisablesRadioKeys() = assertNull(parse("not base64!!"))
    @Test fun shortSecretDisablesRadioKeys() {
        assertNull(parse(b64(31)))
    }
    @Test fun validSecretIsDecodedAndTrimmed() {
        assertContentEquals(ByteArray(32) {it.toByte()},parse("  ${b64(32)}\n"))
        assertEquals(48,parse(b64(48))?.size)
        val config=NearbyBackendConfiguration.fromEnvironment(mapOf("VOLTY_RADIO_SECRET" to b64(32)))
        assertEquals(32,config.radioSecret?.size);assertFalse(config.toString().contains(b64(32)))
    }
}
