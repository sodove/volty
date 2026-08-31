# Nearby Live Map Stability Emulator Smoke

Run this against the candidate APK and the already deployed compatible backend. Use two test accounts on two emulator instances (or one emulator plus a physical device), with the same group selected.

## Baseline visibility

- Sign in as A and B.
- Join the same group and select it on both devices.
- Grant location permission and start Location-only sharing on both.
- Keep each device moving or use emulator location injection for at least two updates.
- Confirm the Nearby roster contains A and B, the Light bottom sheet lists both, and the Light map shows two distinct markers.
- Stop location updates on B without leaving the group. Confirm B remains in roster/sheet as no current point or stale, while A remains visible.

## Recovery

- Toggle network off on A for 20–30 seconds. Confirm the roster is not replaced by an empty participant state.
- Restore network. Confirm the next snapshot updates marker/presence without restarting the app.
- Rotate/recreate A's Activity while sharing and with the group selected. Confirm the runtime reconnects and A remains visible.
- Force-stop/relaunch A. Confirm only server-valid sharing is restored; expired sharing is not resurrected.

## Membership and expiry

- Renew sharing for A. Confirm B sees the replacement expiry/location state rather than the old marker persisting.
- Have B leave the group. Confirm A no longer sees B's marker and B's selected-group panel disappears without an app restart.
- Delete the group as owner. Confirm both clients leave the live screen and no WebSocket reconnect storm occurs.

## Evidence to capture on failure

Record app build/version, account role, group id (not tokens), exact timestamp, roster count, live snapshot count, marker count, connection state, and the first relevant client/server log line. Never include access tokens, voice credentials, or exact private coordinates in the report.
