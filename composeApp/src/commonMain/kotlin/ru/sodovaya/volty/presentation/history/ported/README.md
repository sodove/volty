# Vescape UI adaptations

These Kotlin/Compose files adapt Vescape's UI at pinned revision
`2527c346bbdb25d4e018fdd68e916621e20fac27` (project author: Kacper Kozak):

| Kotlin file | Original source | Changes |
|---|---|---|
| `HistoryRideCard.kt` | `src/modules/history/components/HistoryRideRow.tsx` | Compose Surface/Row, Volty theme and typography, content may grow with system font size, recorded segmented preview input with loading/error/retry/partial states. Keeps the 86-wide preview, 88-high row minimum, 12-radius card, labels and chevron hierarchy. |
| `HistoryRoutePreview.kt` | `src/modules/history/components/RouteSparkline.tsx` | Compose Canvas instead of Skia, separate paths for GPS segments, neutral loaded-point marks, route-only fallback. Tile/cache rendering is not ported yet. Projection comes from Volty's independently tested policy. |

Both adaptations are marked `GPL-3.0-or-later`, matching upstream `package.json`.
`LICENSE` is a verbatim copy of upstream's GNU GPL version 3 text (SHA-256
`230184F60BAE2FEAF244F10A8BAC053C8FF33A183BCC365B4D8B876D2B7F4809`).
Retraction: the initial LF-only Git rule normalized this upstream CRLF file in
the Git blob. The scoped rule now disables conversion so its original bytes,
not just its license text, are preserved across commits and checkouts.
The source pin, package-author evidence and dependency audit are recorded in
`docs/third-party/vescape-history.md` at the repository root.

This record identifies transferred material and its license. It does not assert
that a directory boundary resolves combined-application distribution requirements
or that the complete screen port/dependency compatibility audit is finished.
