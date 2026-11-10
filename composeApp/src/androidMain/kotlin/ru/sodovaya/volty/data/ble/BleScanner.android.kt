package ru.sodovaya.volty.data.ble

import com.juul.kable.Advertisement
import com.juul.kable.Scanner

internal actual fun nonBlockingBleScanner(): Scanner<Advertisement> = Scanner {
    // Android can deliver scan results on the main thread. Kable 0.43 otherwise
    // waits for space in its callback channel, which can cause an ANR.
    preConflate = true
}
