# History export format checkpoint

This is the encoding portion of Task7. It does not expose an export button or
claim a working file/share flow. The paged database adapter, bounded temporary
file owner, atomic publication, checksum, scoped FileProvider and native chooser
acceptance are still required before the feature is available.

The original Volty encoders consume a Flow of retained records and write
fragments, rather than building a whole document in memory. These are retained
detail and summary facts, not a claim to export every original acquisition.
Current recordings are rejected consistently by all three formats.

## GPX 1.1

Only recorded locations become points. Explicit GPS/clock/overflow/interruption
boundaries, clock discontinuities and excessive GPS silence split track segments.
Equal-time distinct fixes remain distinct. A legacy recording without recorded
GPS returns NO_ROUTE; no live location is consulted.

Point time retains the original captured UTC timestamp. Volty extensions retain
elapsed time, clock generation, accuracy and the selected monotonic range/origin.
Coordinates use plain decimals, as required by the
[official GPX 1.1 schema](https://www.topografix.com/GPX/1/1/gpx.xsd).
Captured longitude +180 is represented by the equivalent -180 meridian in the
GPX attribute; its original representation remains in an extension. Stored
recording data is unchanged.

## CSV and JSON

JSON schemaVersion1 declares data quality, source aliases, units, display and
consumption sign multipliers, range axis and counts. It retains explicit unknown
reasons and null values, along with GPS, scoped/global events and summary facts.
Legacy elapsed/generation placeholders are exported as null, never as measured
zero. CSV uses named columns, quoted multiline fields and the same canonical
units/signs. Untrusted text receives spreadsheet formula protection; numeric
negative values retain their numeric sign.

File-local source aliases preserve distinctions without exposing raw recording,
vehicle or device IDs. Display names redact device addresses and private
absolute/file/content paths. Ordinary Unicode names remain readable.

Summary buckets retain their earned extrema, energy and completeness without
proportional rescaling. Only buckets whose entire recorded coverage is inside
the requested window may be supplied by the database reader. A crossing bucket
is rejected rather than leaking outside-window facts or inventing interval
statistics. JSON states FULL_STORED_BUCKETS_INSIDE_WINDOW explicitly.

Retraction: an earlier uncommitted encoder accepted crossing buckets with an
ORIGINAL_BUCKET/overlap flag. That described their energy honestly but still
included facts beyond an explicitly selected interval. It was replaced before
this checkpoint was committed; incomplete fully contained buckets remain valid.

## Evidence and limits

Seventeen synthetic format tests exercise parsed GPX segments/points, XML/JSON
escaping, same-time fixes, unknown values, canonical units/signs, source aliasing,
formula-bearing Unicode CSV labels, private metadata redaction, cancellation,
source mismatch, summary boundaries, monotonic range provenance and active
recording rejection. They do not substitute for actual database or device reads.

Final h571: 4500 tests in 467 fresh XML reports, zero failures/errors/skips/stale
reports. All 55 Gradle tasks executed; migration verification and debug APK
assembly passed in 80 seconds. Both fresh synthetic edge-case GPX fixtures also
validated independently against the downloaded official XSD. No XSD or other
external source was added to the distributed application.

The debug APK SHA-256 was
`2CC047CB4F3619F6C3D3B6E8A1CD579C9ACFC963227CE304355425A0C96506D6`.
This is not a signed release or native export acceptance claim. Task7 integration
and Task8 production navigation/recorder/release gates remain open.
