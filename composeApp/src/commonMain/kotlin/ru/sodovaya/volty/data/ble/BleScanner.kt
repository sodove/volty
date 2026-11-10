package ru.sodovaya.volty.data.ble

import com.juul.kable.Advertisement
import com.juul.kable.Scanner

internal expect fun nonBlockingBleScanner(): Scanner<Advertisement>
