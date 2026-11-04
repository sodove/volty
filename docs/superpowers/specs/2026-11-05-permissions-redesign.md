# Permission onboarding redesign

Approved in chat: use the third mockup (one permission group per step), remove only the top lightning/VOLTY brand, and match the existing application theme.

## Intent

Explain what Android is asking for and why. Let a first-time user try Volty without granting permissions or owning hardware.

## Design

- Use the existing Material 3 Expressive theme, dynamic/custom accent colours, Material icons and typography. No fixed orange palette. Keep the Bluetooth icon tile, step indicator, left-aligned headline, explanation, two useful capability rows, bottom action and demo alternative from the chosen mockup.
- Bluetooth first: nearby-device scan/connect on Android 12+, location for BLE discovery on older Android. Explain the older Android requirement explicitly. Never combine this request with notifications.
- Notifications second, only where supported and missing. Explain background monitoring status and warnings. Either denial or explicit skip continues to the normal app; notifications are optional.
- Demo is visible before any request, offers scooter and wheel profiles, and opens the existing simulator. No BLE service start and no automatic location prompt from the demo dashboard. Returning to real-device discovery must revisit the Bluetooth gate when required.
- Show a helpful denial message. A permanent Bluetooth denial offers app settings, and returning from settings rechecks permission state.
- Preserve the current post-permission routing (Welcome for no saved vehicles, scanning otherwise). Do not repeatedly block later launches solely because notifications were declined.
- Fit short/landscape screens and larger font sizes using a bounded content width and scrolling. Labels in English and Russian resource sets. Real rendering must be checked on an emulator/device, not via a pretend Compose unit test.

Implementation choices: keep this onboarding in its existing Decompose component; inject permission-list/read functions so the real component can be tested without Android. Keep permission-group decisions in the component and platform request/settings handling in the screen.
