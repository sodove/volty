# Ride history source and dependency evidence

This is a scoped evidence inventory for the approved Ride History rewrite. It records candidate Vescape sources and direct runtime dependencies declared through `gradle/libs.versions.toml` and used by `composeApp`. It is not a compatibility conclusion or a comprehensive transitive dependency audit. No application `LICENSE` change is made here.

## Vescape source candidates

Pinned source: [`vescape-app/vescape` at `2527c346bbdb25d4e018fdd68e916621e20fac27`](https://github.com/vescape-app/vescape/tree/2527c346bbdb25d4e018fdd68e916621e20fac27), matching the local clone at `C:\Users\sodovaya\.codex\research\vescape-20261013`.

| Candidate in pinned tree | Candidate material | License and notice evidence |
|---|---|---|
| [`src/modules/history/`](https://github.com/vescape-app/vescape/tree/2527c346bbdb25d4e018fdd68e916621e20fac27/src/modules/history) | History state, charts, route/favorite/media helpers and related screens | Package-level `package.json` declares `GPL-3.0-or-later` and author `Kacper Kozak`; root `LICENSE` is GNU GPL version 3. No file-level license/copyright/NOTICE marker found by targeted search in this candidate tree. |
| [`src/screens/main/history/`](https://github.com/vescape-app/vescape/tree/2527c346bbdb25d4e018fdd68e916621e20fac27/src/screens/main/history) | History map panels, ride detail, stats and controls | Same package-level declaration and root license. No file-level license/copyright/NOTICE marker found by targeted search in this candidate tree. |
| [`modules/vescape-core/android/src/main/java/expo/modules/vescapecore/telemetry/TelemetryRepository.kt`](https://github.com/vescape-app/vescape/blob/2527c346bbdb25d4e018fdd68e916621e20fac27/modules/vescape-core/android/src/main/java/expo/modules/vescapecore/telemetry/TelemetryRepository.kt) | Android telemetry repository candidate | Same package-level declaration and root license. No file-level license/copyright/NOTICE marker found by targeted search in this file. |
| [`docs/adr/0030-favorite-media-is-curated-copied-storage.md`](https://github.com/vescape-app/vescape/blob/2527c346bbdb25d4e018fdd68e916621e20fac27/docs/adr/0030-favorite-media-is-curated-copied-storage.md) | Media/favorite behavior reference | Same package-level declaration and root license. No file-level license/copyright/NOTICE marker found by targeted search in this document. |

The source package's license is taken from its `package.json`; the GPLv3 text is in its root `LICENSE`. The candidate scan found no additional per-file notices to enumerate. Preserve the package-level license and attribution evidence for any material actually transferred, and record the transferred files and changes in the source-provenance record.

## Imported UI adaptations

### 2026-10-18 transfer record

Retraction of earlier dated "no Vescape source imported yet" status below: the
first direct UI adaptations now live in
`composeApp/src/commonMain/kotlin/ru/sodovaya/volty/presentation/history/ported/`.
`HistoryRideCard.kt` adapts pinned `HistoryRideRow.tsx`; `HistoryRoutePreview.kt`
adapts pinned `RouteSparkline.tsx`. Each carries GPL-3.0-or-later/source attribution.
The adjacent README enumerates changes and the adjacent LICENSE copies upstream
verbatim (SHA-256 `230184F60BAE2FEAF244F10A8BAC053C8FF33A183BCC365B4D8B876D2B7F4809`).
The package author is Kacper Kozak; no unverified file-level copyright year is
invented. Source-shaped row/Canvas visuals are transferred, not upstream telemetry
storage, tiles, gallery scanning, logos or other assets. This supersedes the old
import status only; the inventory's unresolved distribution/dependency conclusions
are not silently upgraded by per-file license labels.

## Direct runtime modules

The runtime set below comes from `composeApp/build.gradle.kts` common/Android main source sets and the corresponding aliases in `gradle/libs.versions.toml`. License names and URLs are copied from the cached POMs, not inferred from repository descriptions. Cache root on the inspected machine: `C:\Users\sodovaya\git\volty\.gradle-user\caches\modules-2\files-2.1`. Each reference below is relative to that root and names the exact cached POM.

No standalone `LICENSE`, `NOTICE`, or `COPYING` file was found in the listed module-version cache directories. Thus these rows contain POM declarations only; no archive-embedded license notices were evaluated here. The POM `name` and `url` fields are recorded as declared; missing fields are marked unverified.

| Module and version | POM-declared name; license and URL | Cached POM reference |
|---|---|---|
| `com.arkivanov.decompose:decompose:3.4.0` | Decompose; Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/arkivanov/Decompose> | `com.arkivanov.decompose/decompose/3.4.0/9baaeedda507cb1c69c41427990702f55a09c95e/decompose-3.4.0.pom` |
| `com.arkivanov.decompose:extensions-compose:3.4.0` | Decompose; Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/arkivanov/Decompose> | `com.arkivanov.decompose/extensions-compose/3.4.0/8ae2f1096a7d3c6c3b9cfa0bf63ad5369fbbdf0f/extensions-compose-3.4.0.pom` |
| `io.insert-koin:koin-core:4.2.1` | Koin; Apache-2.0 — <http://www.apache.org/licenses/LICENSE-2.0.txt>; <https://insert-koin.io/> | `io.insert-koin/koin-core/4.2.1/ce77ae85d532c4181d0707a021cd3484ef18df94/koin-core-4.2.1.pom` |
| `io.insert-koin:koin-compose:4.2.1` | Koin; Apache-2.0 — <http://www.apache.org/licenses/LICENSE-2.0.txt>; <https://insert-koin.io/> | `io.insert-koin/koin-compose/4.2.1/9f03b0bb17707478d497cf6a135a059ffc9489d0/koin-compose-4.2.1.pom` |
| `io.insert-koin:koin-android:4.2.1` | Koin; Apache-2.0 — <http://www.apache.org/licenses/LICENSE-2.0.txt>; <https://insert-koin.io/> | `io.insert-koin/koin-android/4.2.1/f6de3888a6a3bc29c3904bb5773e189b6f27c6a6/koin-android-4.2.1.pom` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2` | kotlinx-coroutines-core; Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/Kotlin/kotlinx.coroutines> | `org.jetbrains.kotlinx/kotlinx-coroutines-core/1.10.2/828afd085f442cafb6d5d9b6556c2227142a4d03/kotlinx-coroutines-core-1.10.2.pom` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2` | kotlinx-coroutines-android; Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/Kotlin/kotlinx.coroutines> | `org.jetbrains.kotlinx/kotlinx-coroutines-android/1.10.2/f301310c3e7e01e8ef5c86bcb98efe349da80288/kotlinx-coroutines-android-1.10.2.pom` |
| `org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0` | kotlinx-serialization-json; Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/Kotlin/kotlinx.serialization> | `org.jetbrains.kotlinx/kotlinx-serialization-json/1.10.0/1e7fcf22dd5eb6da3a45ca26e11e533758ad7e0f/kotlinx-serialization-json-1.10.0.pom` |
| `com.juul.kable:kable-core:0.43.0` | Kable; Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/JuulLabs/kable> | `com.juul.kable/kable-core/0.43.0/7d8cf783b309ec29c53097487eddcbef694e46a3/kable-core-0.43.0.pom` |
| `io.ktor:ktor-client-core:3.3.1` | ktor-client-core; Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/ktorio/ktor> | `io.ktor/ktor-client-core/3.3.1/c43ba912c947fc60ba99f1b8e86d815eab7833a2/ktor-client-core-3.3.1.pom` |
| `io.ktor:ktor-client-websockets:3.3.1` | ktor-client-websockets; Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/ktorio/ktor> | `io.ktor/ktor-client-websockets/3.3.1/ceb627ae4eed15dee2839baf595cbdad409d59b4/ktor-client-websockets-3.3.1.pom` |
| `io.ktor:ktor-client-cio:3.3.1` | ktor-client-cio; Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/ktorio/ktor> | `io.ktor/ktor-client-cio/3.3.1/7ce10c875bfebc3c7893ceeae0632616029b271d/ktor-client-cio-3.3.1.pom` |
| `io.ktor:ktor-client-okhttp:3.3.1` | ktor-client-okhttp; Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/ktorio/ktor> | `io.ktor/ktor-client-okhttp/3.3.1/7655feb34f9abf708cefbf0de38151d42b7b8f4b/ktor-client-okhttp-3.3.1.pom` |
| `org.jetbrains.kotlinx:kotlinx-datetime:0.6.1` | kotlinx-datetime; Apache-2.0 — <http://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/Kotlin/kotlinx-datetime> | `org.jetbrains.kotlinx/kotlinx-datetime/0.6.1/8a29a8a3f60d59df597a5fcf6f2e9b528112b148/kotlinx-datetime-0.6.1.pom` |
| `app.cash.sqldelight:runtime:2.2.1` | SQLDelight Multiplatform Runtime (Experimental); Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/sqldelight/sqldelight/> | `app.cash.sqldelight/runtime/2.2.1/20f718a6317637b39739a34b08f8e21013d5009d/runtime-2.2.1.pom` |
| `app.cash.sqldelight:coroutines-extensions:2.2.1` | SQLDelight Coroutines Extensions; Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/sqldelight/sqldelight/> | `app.cash.sqldelight/coroutines-extensions/2.2.1/613cbf3b247d3d9319e1b5db3e5cf72c44a9f32f/coroutines-extensions-2.2.1.pom` |
| `app.cash.sqldelight:android-driver:2.2.1` | SQLDelight Android Driver; Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/sqldelight/sqldelight/> | `app.cash.sqldelight/android-driver/2.2.1/d524481003b72026b2184636e4059fc1dd7052ed/android-driver-2.2.1.pom` |
| `androidx.work:work-runtime-ktx:2.12.0` | WorkManager Kotlin Extensions; Apache-2.0 — <http://www.apache.org/licenses/LICENSE-2.0.txt>; <https://developer.android.com/jetpack/androidx/releases/work#2.12.0> | `androidx.work/work-runtime-ktx/2.12.0/b9ae008da94f9f9ffd2e8149461ee2f97d0af5b0/work-runtime-ktx-2.12.0.pom` |
| `androidx.activity:activity-compose:1.12.3` | Activity Compose; Apache-2.0 — <http://www.apache.org/licenses/LICENSE-2.0.txt>; <https://developer.android.com/jetpack/androidx/releases/activity#1.12.3> | `androidx.activity/activity-compose/1.12.3/1c753099c493ac1f57438937e542790ef9ba50aa/activity-compose-1.12.3.pom` |
| `androidx.datastore:datastore-preferences:1.1.7` | Preferences DataStore; Apache-2.0 — <http://www.apache.org/licenses/LICENSE-2.0.txt>; <https://developer.android.com/jetpack/androidx/releases/datastore#1.1.7> | `androidx.datastore/datastore-preferences/1.1.7/c22418b8d65535202ce6ebdfcadfee725d9d251d/datastore-preferences-1.1.7.pom` |
| `androidx.graphics:graphics-shapes:1.0.1` | Graphics Shapes; Apache-2.0 — <http://www.apache.org/licenses/LICENSE-2.0.txt>; <https://developer.android.com/jetpack/androidx/releases/graphics#1.0.1> | `androidx.graphics/graphics-shapes/1.0.1/1a2cbe2880cf7efe79bbfa581db18ad7170f8ab7/graphics-shapes-1.0.1.pom` |
| `dev.chrisbanes.haze:haze:1.7.2` | Haze; Apache-2.0 — <http://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/chrisbanes/haze/> | `dev.chrisbanes.haze/haze/1.7.2/51f91684fdac3d0e7947bbf40a4e07016b55a6a0/haze-1.7.2.pom` |
| `io.github.rallista:valhalla-mobile:0.6.3` | Valhalla Mobile; MIT — <https://github.com/Rallista/valhalla-mobile?tab=MIT-1-ov-file#MIT-1-ov-file>; <https://github.com/Rallista/valhalla-mobile> | `io.github.rallista/valhalla-mobile/0.6.3/61d744aa484235bb8d49b11773707aa422e9aaf4/valhalla-mobile-0.6.3.pom` |
| `io.github.rallista:valhalla-models:0.5.2` | Valhalla API Models; MIT — <https://mit-license.org>; <https://github.com/Rallista/valhalla-openapi-models-kotlin> | `io.github.rallista/valhalla-models/0.5.2/33aa078e9dbe37608c60cb01292665e8985a8b03/valhalla-models-0.5.2.pom` |
| `io.github.rallista:valhalla-models-config:0.5.2` | Valhalla Config Models; MIT — <https://mit-license.org>; <https://github.com/Rallista/valhalla-openapi-models-kotlin> | `io.github.rallista/valhalla-models-config/0.5.2/d027abfbd55575443e2f3e717e83cdd4726ee46d/valhalla-models-config-0.5.2.pom` |
| `org.bouncycastle:bcprov-jdk18on:1.84` | Bouncy Castle Provider; Bouncy Castle Licence — <https://www.bouncycastle.org/licence.html>; <https://www.bouncycastle.org/download/bouncy-castle-java/> | `org.bouncycastle/bcprov-jdk18on/1.84/3471d9c7c926c61724397f88b09d355afb7be4b2/bcprov-jdk18on-1.84.pom` |
| `com.google.firebase:firebase-messaging:26.0.0` | POM name and project URL: **unverified; fields absent**. Apache-2.0 — <http://www.apache.org/licenses/LICENSE-2.0.txt>. Version is selected by `firebase-bom:35.0.0`. | `com.google.firebase/firebase-messaging/26.0.0/3dd9fe1fcea06d0eb08e93e5744ee3298aa0152c/firebase-messaging-26.0.0.pom`; BOM mapping: `com.google.firebase/firebase-bom/35.0.0/e5431e3ec4f7bffa6a052e7c9f43d12003d1e4cf/firebase-bom-35.0.0.pom` |
| `io.livekit:livekit-android:2.28.0` | LiveKit Client Android SDK; Apache-2.0 — <http://www.apache.org/licenses/LICENSE-2.0.txt>; <https://github.com/livekit/client-sdk-android> | `io.livekit/livekit-android/2.28.0/119981a9f11a37f6217db997f24a2bd3d6421653/livekit-android-2.28.0.pom` |
| `org.maplibre.gl:android-sdk:13.0.2` (explicit in build script, not a catalog alias) | MapLibre Android; BSD — <https://opensource.org/licenses/BSD-2-Clause>; <https://github.com/maplibre/maplibre-native> | `org.maplibre.gl/android-sdk/13.0.2/a2975813e40f3262bcd64e564faf06a62fadb013/android-sdk-13.0.2.pom` |

## Unverified scope edges

- The Compose dependencies declared through the Compose Gradle plugin (`compose.runtime`, `foundation`, `material3`, `ui`, `materialIconsExtended`, and `components.resources`) are outside the version-catalog module list above; their resolved coordinates and POMs were not inventoried here.
- `composeApp/libs/audioswitch-039a35aefab7747c557242fa216c9ea11743b604.aar` and `composeApp/libs/brouter-1.7.10-all.jar` are direct local-file dependencies, with no Maven coordinate/POM checked here. Their license/notice evidence is **unverified**.
- Catalog entries used only in tests (`kotlin-test`, `coroutines-test`, `turbine`, `sqldelight-jvm`, and `ktor-client-mock`) are excluded from the runtime table. The Firebase BOM is recorded only as version-selection evidence for Messaging.
- This inventory stops at direct dependencies. Transitive dependencies, bundled map data/tiles, fonts, and notices inside JAR/AAR artifacts remain outside this document.

## Local archive follow-up (primary, 2026-10-13)

Neither local archive contains LICENSE/NOTICE/COPYING entries. AudioSwitch source is the LiveKit POM-pinned davidliu/audioswitch commit039a35aefab7747c557242fa216c9ea11743b604; git show proves LICENSE.txt is Apache2.0 (LICENSE without extension returned404 and was not evidence). Text retained in docs/third-party/licenses/AudioSwitch-Apache-2.0.txt. BRouter upstream v1.7.10 LICENSE is MIT, copyright2019 BRouter contributors: https://github.com/abrensch/brouter/blob/v1.7.10/LICENSE . These source licenses do not themselves prove byte-for-byte identity or complete bundled component provenance of the local archives. Archive notices/transitive inventory remains incomplete; no Vescape source imported yet.

## Summary-panel transfer (2026-10-20)

`presentation/history/ported/HistorySummaryPanel.kt` adapts the pinned
`src/screens/main/history/HistoryStatsBar.tsx` compact/expand hierarchy, with the
same adjacent GPL-3.0-or-later notice/license. Changes: source-qualified selected
metrics, flowing readable cells, bounded lazy expansion and Volty typography;
per-field unknown/partial values use persisted full-stream range statistics.
It does not port upstream session aggregate calculation or recompute peaks/energy
from decimated chart points. Root history activation remains a later acceptance
step. The independent original Kotlin HistorySummaryPolicy converts canonical
extrema to display sign and preserves already consumption-signed Wh.

The direct-dependency inventory above predates the renderer change: application
now uses the same-version official `org.maplibre.gl:android-sdk-opengl:13.0.2`
artifact. The old default Vulkan artifact entry is historical, not evidence for
the new artifact's complete archive/transitive notice audit. Final combined APK
source/notice/distribution acceptance remains open.

## Home composition and navigation transfer (2026-10-20)

The adjacent GPL-noticed `ported/HistoryScreen.kt` adapts the responsibilities of
the pinned Vescape `HistoryOverlay.tsx`, `HistoryRideDetail.tsx` and
`HistoryTelemetryPanel.tsx`: map-backed detail home, header/summary, bottom
charts/navigation, a ride selection sheet and fullscreen charts sharing selection.
`HistoryNavigationStrip.kt` adapts `HistoryPanelNav.tsx` central dropdown title,
adjacent-ride controls and side actions. Original author and revision are recorded
in each file and the adjacent README. Compose rendering uses Volty's theme and
source-qualified read owners, a lazy selected-metric stack instead of a fixed
metric-button strip, adaptive layouts, unknown/read-quality states, and measured
camera padding. The Kotlin state-qualification/adjacency policy is original code.
No additional runtime dependency or upstream assets/token are introduced.
Favorite/media/export actions remain omitted until their real owners are wired;
this does not claim the complete experience or root/recorder activation is done.

## Favorites workflow adaptation (2026-10-21)

`ported/HistoryFavoritesScreen.kt` and `ported/HistoryTrimPanel.kt` adapt the
favorites/list/trim interaction from pinned `HistorySessionSheet.tsx` and
`useHistoryFavorites.ts` at revision
`2527c346bbdb25d4e018fdd68e916621e20fac27` (Kacper Kozak). Both carry adjacent
GPL-3.0-or-later notices and use the preserved upstream LICENSE. The original
Kotlin repository, source fingerprint, SQLite migration, paging/revision rules
and Decompose request ownership are Volty implementations. No new dependency,
upstream media URI persistence or recording mutation was introduced here.
The combined release audit above remains open; this entry records provenance.

## Curated media gallery adaptation (2026-10-21)

`ported/HistoryMediaDrawer.kt` adapts the thumbnail-grid, explicit picker and
favorite media drawer workflow from pinned `HistoryRideMediaDrawer.tsx` and
`MediaHistoryGallery.tsx` (same revision and author as above), with its adjacent
GPL notice. The upstream `MediaHistoryViewer.tsx` was also inspected; the Android
viewer here is an original platform `BitmapFactory`/`VideoView` implementation,
without the upstream synchronized video telemetry overlay. No upstream media
assets, gallery scans, access-token configuration or new runtime SDK is added.

Volty stores bounded private copies behind a narrow FileProvider path, with a
SQLite IMPORTING/READY/DELETING journal and recovery, streaming SHA-256, and
source/favorite-qualified nearest **recorded** fix placement. EXIF capture time
requires an explicit UTC offset; the Android video metadata DATE field is not
used as a capture-time claim. Android framework behavior is checked natively;
JVM tests exercise the original ownership/files/SQLite policies only.
Combined-APK distribution/source/notice audit remains open.

## Whole-recording deletion extension (2026-10-21)

The adapted catalog card exposes an optional trash action. Its confirmation
component, exact recording/count revalidation, SQL cascade, bounded post-commit
file cleanup and durable route-preview purge are original Volty implementations.
No additional upstream source, asset or runtime dependency is introduced here.
This does not close the combined distribution audit or production cutover gates.

## Recording controls and factory cutover (2026-10-21)

The adapted history catalog now hosts an original Volty current-recording panel.
Its parent, native permission/settings launcher, status/control repository and
debug-only visual fixture introduce no upstream source/assets or dependency.
The production factory selects the app-owned native recorder; legacy rows remain
readable and use an explicit inactive-recorder authority for export and deletion.
See `docs/superpowers/specs/2026-10-21-history-recording-cutover.md` for evidence
and remaining acceptance gates. The earlier root-integration note describing the
four-argument factory as still active is superseded by this actual cutover.
