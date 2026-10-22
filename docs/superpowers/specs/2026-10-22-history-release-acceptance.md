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

Final release artifact hashes and source revision are recorded after assembly. The preceding transport/native BLE acceptance is in
`2026-10-22-history-common-decoder-output-acceptance.md`.

Physical vehicles and phone-vendor behavior cannot be certified from the emulator.
