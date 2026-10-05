# Typed sharing packet client

Add a bounded HTTPS packet client borrowing its parent HttpClient and capturing bearer per
request. POST original sharing tag/sequence, GET audience only for exact selected channel and
original current viewer session. Caller cancellation and closed child issue no late HTTP after
held bearer. Ten second timeout, strict UTF-8, no redirects, finite response/body bounds.

Reuse LocationSnapshot/SharedTelemetry vocabulary, independently normalize earned unknowns and
strip fields excluded by actual mode. Check original tag/sequence, canonical unique actor and
session IDs, selected channel, observation/capture/sample bounds and numeric remaining credits.
Project credits onto monotonic original request issuance; receipt never renews freshness.
An expired peer is removed; valid empty sharing audience is not a voice participant count.
Surface typed failures, never infer peer audibility from this endpoint. Requests have monotonic
ordinals for subsequent root projection to reject reordered responses. No publisher/FGS/DI/UI
activation yet; exact account/session positive capability integration is the following step.

Tests first with real MockEngine: original path/body/bearer, exact acknowledgements/channel,
duplicates, malformed sample/mode/credit, unknown values and RIDE/LOCATION privacy, delayed
expired/backwards/overflow time, cancelled bearer, child-close/borrowed parent, bounded UTF-8
and redirects. Fresh full Android suite before commit. Backend source unchanged.
