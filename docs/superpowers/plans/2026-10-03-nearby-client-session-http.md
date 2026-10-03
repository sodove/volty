# Nearby client session HTTP boundary

Implement the missing request provenance boundary for the approved voice core
while independent backend PostgreSQL tests run. No backend edits/builds during
that run. This does not enable sessions in DI or replace the legacy UI yet.

- Inject owned HttpClient, secure bearer supplier and monotonic clock. Actual
  Android wiring must pass SystemClock.elapsedRealtime, including sleep. No wall
  clock default and no fixed thirty-second credit fallback.
- Capture increasing request ordinal and local start before sending each HTTP
  request. Decode bounded v2 response before recording receipt; attach that exact
  provenance to grants. Do not renew credit at arrival. Preserve the caller's
  generation and operation; validate expected session on heartbeat/disconnect.
- Typed outcomes distinguish pending, credentials, lease acknowledgement,
  terminal, expired receipt and safe failure codes. Pending/status without a
  remaining grant cannot authorize capture. Already-expired credential receipt
  returns its session identity for cleanup without exposing credentials.
- Original request operation UUID is stable across caller-driven join retries.
  No automatic network retry/new room generation. Cancellation propagates.
  Ten-second bounded request deadline; response maximum16KiB, no redirects or
  exception/token text in outcome/log. HTTPS endpoint and WSS credentials.
- Tests issue MockEngine requests and inspect URL/body/bearer, delayed receipt,
  missing/invalid credit, ordinal allocation, foreign session, denied response
  and issued-request cancellation. No success-only fixture or endless time loop.
- Fresh targeted tests then Android full exact count. Do not claim real transport,
  generation retirement, secure auth refresh or device audio integration from
  this isolated boundary. Future controller owns effects, expiry cleanup and
  heartbeats; backend atomic ACL and product/UI cutover remain prerequisites.
