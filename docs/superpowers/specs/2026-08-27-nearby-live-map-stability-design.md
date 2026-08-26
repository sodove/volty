# Nearby Live Map Stability Design

**Date:** 2026-08-27

**Goal:** Make Nearby group membership and participant locations reliable across sharing, GPS updates, WebSocket reconnects, activity recreation, and map rendering, with a reproducible regression test for the build where no one appeared on the map.

## Scope

This package covers the live group path only: group roster and sharing snapshots, server event propagation, client runtime state, GPS publication, authentication/reconnect, lifecycle recovery, stale-state projection, and map marker presentation. It also adds diagnostics at component boundaries so a future “participants 0” report identifies the failing layer.

Out of scope: redesigning Nearby UI, replacing voice transport, adding turn-by-turn navigation, or changing the BLE telemetry protocol. Existing dirty worktree changes are treated as user-owned and must be preserved.

## Observed failure modes

1. A member can have sharing enabled but no successful `live_updates` row yet; the live snapshot then has no roster entry and the client renders zero participants.
2. Nearby list, bottom sheet, and map derive from different snapshots and freshness rules; a WebSocket failure can blank the list while stale markers remain or statuses disagree.
3. Leave/renew/delete paths do not consistently broadcast a revoke/update event to other observers, leaving ghost or missing markers.
4. GPS provider selection can wait forever on an enabled-but-unfixed GPS provider, and the first last-known location can be lost before collection.
5. The app-scoped runtime is closed from Root lifecycle teardown, so Activity recreation can leave a permanently closed runtime. Process death also does not rehydrate active sharing.
6. WebSocket reconnects use a dynamic token provider, but a server-side handshake rejection does not force a token refresh.

## Design

The server remains authoritative for group membership and sharing expiry. A live snapshot contains every current group member with `location = null` when that member has no accepted location update, plus a presence value derived from last-seen/update freshness. The map renders only members with a usable location. The UI consumes one client-side projection containing roster, latest locations, presence, freshness, and connection state, so list, sheet, and map cannot silently disagree.

Freshness is evaluated both on incoming events and on a lightweight client ticker. A failed connection keeps the last snapshot visible but marks locations stale after the agreed threshold; it does not erase the roster. Leave, renew, and group deletion generate explicit events to observers, and a non-member subscription is terminal rather than an infinite reconnect loop.

The location provider tries usable platform providers and exposes an initial snapshot through replay/state semantics. Lifecycle ownership is app-scoped: a screen/root close detaches UI collection but does not destroy the singleton runtime. A process restart restores auth and rehydrates only server-valid sharing state. WebSocket authorization refreshes once on handshake rejection before retrying.

## Acceptance criteria

- Two accounts in one selected group see each other in the Nearby roster and on the map when both have a fresh location.
- A member with sharing enabled but no accepted GPS update appears in the roster/sheet as no current point, never as “participants 0”.
- On WebSocket/network failure, the last roster remains visible; markers become stale deterministically and recover on the next snapshot.
- Leaving or renewing sharing removes/replaces the member for all observers without an app restart.
- GPS provider fallback and initial delivery allow the first location to publish when GPS is enabled but temporarily unfixed and network/passive location is usable.
- Activity recreation does not permanently stop live updates; process restart does not resurrect expired sharing.
- A rejected WebSocket token causes one forced refresh and then either reconnects or reports a stable auth failure without a retry storm.
- Map marker presentation keeps stale markers visibly distinct without whole-trail alpha flicker; no camera/marker rendering change may reintroduce jumpy interpolation.
- Unit/contract tests cover every acceptance criterion that can be expressed without a device; Android/emulator smoke covers the remaining lifecycle and map path.

## Verification strategy

Use failing regression tests before each production change. Run targeted common/backend tests after each task, then `./gradlew.bat :composeApp:testDebugUnitTest` and `./gradlew.bat :composeApp:assembleRelease` from the repository root. The standalone `backend` Gradle project has no wrapper in this checkout, so backend tests must be run with the available project tooling or clearly reported as unavailable; do not infer them from the Android build.
