# Nearby + Light social redesign: approved UX specification

## Reference

The approved visual reference is the standalone prototype:

`C:\Users\sodovaya\.codex\visualizations\2026\08\22\01a029f8-6078-7110-bd76-815245c66b72\nearby-light-social-redesign.html`

It shows the intended visual language, spacing, translucent surfaces, map markers,
Light telemetry HUD, and the Nearby social sections. It is a direction reference,
not production code.

## Product shape

Nearby is a full app destination, not a permanently visible map panel. It keeps the
existing app bottom navigation and adds a profile block inside the screen. The
Nearby content is organized into three internal sections: `Люди`, `Друзья`, and
`Группы`.

Light is a special ride dashboard with no bottom navigation. When an authenticated
group ride is active, the existing native map layer remains visible below the Light
HUD and renders the user's group participants as real map markers. A compact group
status control sits above the telemetry strip. Tapping a participant or the status
control opens a transient participant sheet; it must not take over the gauges or
graphs.

All other dashboards expose a consistent Nearby/Group Ride action. The action opens
the full-screen group map when a group ride is active, otherwise it opens Nearby.
The action may be placed in each dashboard's existing control area; it must not add
a second bottom bar to Light.

## Social behavior

- Local ride and BLE telemetry remain available without login.
- Nearby/social actions continue to require authentication.
- Markers consume only `ParticipantMarker` data already produced by the live social
  session; no coordinates, speed, or telemetry may be fabricated.
- Group join is idempotent for an already joined group and must not duplicate it in
  the UI.
- Sharing renewal preserves the selected profile, rotates the expiry, and keeps the
  current live session coherent.
- An owner can delete a group; a member can leave it. Destructive actions require a
  confirmation surface and clear the selected group/live session after success.
- The profile uses the authenticated display name and exposes the existing logout
  action; credentials and raw BLE identifiers never appear in UI state.

## Device and layout constraints

- Keep `DashboardStyle.LIGHT` without the root bottom tab bar.
- Keep Light's top graphs, dual gauge area, telemetry strip, haze/map layering, and
  existing map recenter controls intact.
- Nearby owns a scrollable content surface; the native map is used for the active
  group map state, not as decorative background behind the social lists.
- Compose rendering remains thin; pure state transitions and marker mapping stay
  testable in common code.
- Russian UI strings must exist in both common resource locales.

