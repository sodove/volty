# Live UI stability

## Garage and live tabs

- Reproduce retained active vehicle with a down BLE connection in the root navigation policy tests. Use the same connection-based decision for tab visibility, click handling and fallback home. Keep the existing recording absence window for the automatic summary and remove dead live entries from its back stack.
- Keep each animated destination's bottom chrome with its content. Preserve the shared map host, its camera commands and the Light dashboard to history transition.

## Vehicle overview

- Hold a real route read in the component test and assert that the first overview publication includes its route and latest totals. Keep deferred older totals.
- Keep preview dimensions constant while a themed snapshot loads. Use the route sketch if the snapshot fails.

## Verification

Run the Android unit suite. Check the animation and native map colors on a device.
