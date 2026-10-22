# Ride History 0.7.28 acceptance

## Retained-summary export correction

Earlier retained V2 buckets can predate captured boundary/extremum timestamps.
Retraction: `exportV2Shape` / `exportBuckets` previously filtered them out entirely.
JSON and CSV now retain their earned extrema, energy, count and completeness.
Missing captured timestamps remain null, with row quality
`RETAINED_SUMMARY_TIME_UNAVAILABLE`. Available partial timestamps are retained.
Coverage uses the saved first elapsed time or, conservatively, the saved bucket
start when first coverage is unavailable. Buckets crossing the explicitly chosen
window are omitted rather than cropped or assigned invented sample times.

Existing complete-time summary rows retain their representation. This is an
additive schema-1 summary quality variant; consumers must allow null captured
boundary/peak times and use the explicit coverage fields for retained summaries.
GPX continues to contain only recorded GPS/event facts, never fabricated telemetry.

h672: the actual paged SQLite source regression failed because the summary count
was zero. h673: source and structured encoder tests passed after the correction.
Parent-screen regressions exercise selected recording/range, loading replacement,
closed screen and a late export result after switching recordings. These latter
coverage additions test existing ownership behavior; they are not claimed as
product fixes. h674 full suite: 4612 tests / 480 fresh XML, zero failures, errors,
skips or stale results. Serial Gradle, no build cache, four JVM processors.

## Release and native evidence

Configuration recreation initially reset the selected recording to the catalog
(h676). h677 reproduced the failure in the real parent component. StateKeeper now
retains source-qualified view intent, re-reads persisted facts and rejects a reused
recording ID with a different vehicle/origin. It retains range, cursor, selected
metrics, map metric, chart page and the original saved-favorite reference; exports
and in-flight work do not resume. Original favorite bounds remain separate from a
cropped display window. h679: 4614 tests / 480 fresh XML, zero failures/errors/skips
or stale results, plus successful debug assembly.

Native h680/h683 exercised 360x640 dp with font scale 1.5, 800x1280 dp, 800x450 dp
landscape, light/dark themes, animation scales zero and normal, selected-source
metrics and the chart/map round trip. Actual recreation preserved recording and
selected metrics; the chart page also survived resize. A short-landscape clipping
issue found in h680 was fixed by a readable summary bound, single-row wide
navigation and a scrollable side panel. h683 confirms both extrema visible, a
complete compact chart, and access to the timeline by scrolling. Long titles and
subtitles are bounded; chart/cursor values no longer expose raw Float tails.
These Compose observations are native evidence, not JVM render assertions.

Actual signed h674 cold startup reached its Activity in 1743 ms with no new
am_anr event. This does not establish the cause of the earlier host-load-related
startup stall. Native JSON export of the existing h671 ride completed; parsing
its actual private file gives 11,701 samples, 102 locations, five events and 288
summaries, 4,968,739 bytes; SHA-256
`5592f70855442fbc68da89cb367a64247b229a2ea80ad169b844a9911e141426`.
No internal native source IDs or BLE address occur in that file. No external
message was sent. The earlier actual FileProvider recipient-read/grant proof
remains applicable to the unchanged share owner.

h684, after all functional/layout edits: complete suite 4614 tests / 480 fresh XML,
zero failures/errors/skips/stale results. The actual releaseRuntimeClasspath
license-declaration inventory was refreshed: 312 resolved modules, none missing
POM license evidence; this remains an inventory rather than a combined-license
compatibility conclusion. Upstream pin, GPL text, attribution and transfer README
are preserved and a root third-party notice now points to them.

Fresh focused Luna review found no concrete export issue. A restoration review
raised a possible reentrant navigation hazard. Retraction of that finding as a
confirmed defect: the proposed real repository-callback driver passed unchanged
production code (h686, one test). The assertions prove that the cropped read and
successor navigation were issued and the successor view remained intact. The test
is retained as coverage; no speculative production fix was made.

h685 quiescent database snapshot passes integrity checking and compares exactly
with h671 across all nine history tables after all native view/resize/export work.
Original legacy and V2 facts were not modified by those checks.

h687 final full suite after the extra review coverage: 4615 tests / 480 fresh XML, zero failures/errors/skips/stale results.

## Final signed candidate

Implementation/source revision: `df0ec02683233d915118351f0f2f4355956371a0`.
h688 signed ARM and x86 builds completed serially in 218 seconds (114 tasks,
42 executed). Version 0.7.28 / code 51, package `ru.sodovaya.volty`, min API 26,
target API 36. Both signatures verify with APK v2 and the existing certificate
SHA-256 `c2a4af514b119c4e5a4504bd73131a6f7ce142ed18a89e2e47c0bd075ada61bd`.
The ARM artifact contains ARM64/ARMv7 Valhalla libraries; the emulator variant
also contains x86/x86_64. DEX scans find no history visual-probe fixture markers;
the release package is not debuggable.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| ARM APK | 66,229,031 | `16ffeb229ff333a1c15cd66f9d1f62df708d31927c28d7e7f2799dce5b7dbada` |
| x86 acceptance APK | 140,555,910 | `2b50e217d3683fbe1e095538751d4aa8dac8131d70532b19164cbd26361dd7ec` |
| Matching source ZIP | 17,936,251 | `4376209eb0a8bfd651d23f3c9d7fcea98d41fe460955ae7e767fac4cce3a234b` |

The git archive contains the matching source, Gradle wrapper and build scripts,
notices and original GPL text. Private signing/machine files are not tracked;
complete literal private-key blocks/service-account JSON were not found in the
archive. A first naive scanner matched a key-parser test's header literal; this
was a scanner false positive, not a discovered credential. The final scoped scan
requires a complete encoded key block and inspects service-account JSON values.
AGP's version-control-info reports `NO_VALID_GIT_FOUND` for this managed worktree;
the explicit source revision and hashes above provide the actual build record.

h690 installed the final signed emulator artifact without clearing application
data. Cold Activity launch: 1083 ms; no new am_anr event. The actual production
navigation opened the recorded Bumble ride, selected speed/controller current,
collapsed home charts, opened fullscreen charts and moved their common cursor.
The signed build then retained the chart page, both metrics and cursor through
360x640 dp / font 1.5 and 800x1280 dp configurations. Final screenshots are native
PNG captures with no visual editing:

- `release-0.7.28/map.png`: recorded route and source-qualified range summary.
- `release-0.7.28/charts.png`: two recorded signals, real BLE gap and common cursor.
- `release-0.7.28/metrics.png`: source/group metric drawer and actual selection.

Files and `volty-history-0.7.28-manifest.json` are in the ignored execution
artifact directory `.superpowers/sdd/2026-10-13-ride-history-rebuild/`.
h691 quiescent snapshot passes integrity checking; all nine history tables match
h671 exactly after the final signed UI/resize checks. Original 18 legacy rides /
191,504 points and all earlier V2 facts remain unchanged. Emulator dimensions,
density, font, animation scales, light mode, application locale list and shell
ADB mode were restored; Volty is stopped and no owned Bumble actor remains.

The software candidate and its recorded emulator gates are complete for this
correction. Physical vehicle/phone-vendor acceptance remains unperformed. The
runtime license evidence inventory is not a combined-application legal conclusion;
public-distribution compatibility questions recorded in the provenance document
are not silently marked resolved by producing this local signed candidate. The preceding transport/native BLE acceptance is in
`2026-10-22-history-common-decoder-output-acceptance.md`.

Physical vehicles and phone-vendor behavior cannot be certified from the emulator.
