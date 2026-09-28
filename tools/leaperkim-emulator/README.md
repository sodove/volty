# Leaperkim / Sherman L BLE replay

This tool replays the captured FFE1 notification payloads as a connectable BLE GATT
peripheral. It uses Bumble with Android Emulator Netsim, so the PC Bluetooth adapter is
not involved. The intended setup is the Volty Android Emulator running Volty as the
central, with this script running as the Leaperkim peripheral.

The fixture contains 129 complete notification rows visible in the attached diagnostics
text (5.335 seconds). The diagnostics summary said 200 captured records, but the text
attachment contains only those 129 complete rows. The export was newest-first; the
fixture stores them in chronological order with their original millisecond offsets.
The recorded MAC address is omitted.

## Requirements

- Python 3.12 with Bumble installed. The current workstation has Bumble 0.0.234.
- Android Emulator 33.1.4 or newer with Bluetooth/Netsim enabled.
- A Volty AVD. The workstation's x86_64 AVD is `Pixel_3a_API_34_extension_level_7_x86_64`.

## Run

Start the emulator with Netsim as its Bluetooth controller. In PowerShell:

    $sdk = 'C:\Users\sodovaya\AppData\Local\Android\Sdk'
    Start-Process -FilePath "$sdk\emulator\emulator.exe" -ArgumentList @('-avd', 'Pixel_3a_API_34_extension_level_7_x86_64', '-packet-streamer-endpoint', 'default', '-no-boot-anim') -WindowStyle Hidden

Install or launch Volty in that AVD. A debug build can be installed without a production signing key:

    $env:ANDROID_HOME = $sdk
    .\gradlew.bat :composeApp:installDebug

Start the replay before opening the vehicle setup wizard so its BLE scan can find the virtual wheel:

    py -3.12 tools\leaperkim-emulator\emulate_leaperkim.py

In Volty, add a monowheel. In the controller step, add `LK16313` as a controller and select
`Veteran / Leaperkim`. In the battery step, choose `Это то же устройство` and assign the
same scan result to the battery role as `Leaperkim`. Save and connect. This creates one BLE
link with both configured roles, matching the vehicle topology; connecting from Nearby as
a battery guest exercises only the BMS path. The script uses a static random address
`C0:DE:CA:FE:BA:BE`; it does not impersonate the wheel's real MAC. The FFE1 write handler is
deliberately a no-op and logs attempted writes.

Notifications begin only after Volty subscribes to FFE1. The capture repeats indefinitely
at its recorded timing by default. Use --once for one 5.335-second pass, --speed 2 for a
two-times-faster replay, or --summary-only to inspect the fixture without starting BLE.

To stop the emulator, press Ctrl+C in the script. To stop the AVD, use:

    & "$sdk\platform-tools\adb.exe" emu kill

## Bumble basis

The implementation uses Bumble's current GATT-server shape: Device with an HCI transport,
a service containing a NOTIFY characteristic, and Device.notify_subscribers after the
client enables notifications. The `android-netsim` host transport connects the Bumble
virtual peripheral and Android Emulator to Netsim's virtual radio; no physical Bluetooth
adapter is involved. See Bumble's [Android platform guide](https://google.github.io/bumble/platforms/android.html),
[Android Emulator transport](https://google.github.io/bumble/transports/android_emulator.html),
and [GATT server example](https://github.com/google/bumble/blob/main/examples/run_gatt_server.py).
