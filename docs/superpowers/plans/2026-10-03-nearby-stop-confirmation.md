# Nearby server-intent versus remote cleanup confirmation

Current `Ended` HTTP result proves terminal SQL intent, not SFU revocation.
Client owner deliberately calls its set `unconfirmedStops` and never claims
remote cleanup from that alone. Complete the explicit protocol projection before
new exit UI; backend PostgreSQL131 is running, so start client-only changes.

- Add nullable `cleanupPending` to terminal client reply. Missing field stays
  unknown, never false. Decode it only from terminal responses; no lease fallback.
- Track `pendingRemoteStops` separately from intent confirmation and engine
  resource release. On stop keep the original operation/generation or exact tag.
  `Ended(cleanupPending=true/null)` confirms intent only; `false` additionally
  confirms that backend's exact durable cleanup completed. Failed replies retain
  both projections. A success response cannot target the current replacement.
- Retry the union of intent-unconfirmed and remote-pending requests; otherwise
  a terminal intent acknowledgement would strand pending cleanup forever.
  No automatic/persistent retry or product enablement from this slice.
- Issued-request tests: terminal true then false; absent field remains unknown;
  engine Stopped cannot erase pending remote cleanup; cleanup retry after new
  generation still targets old exact session; HTTP bounded body parses boolean.
- After running backend build finishes, add nullable DTO field populated from
  Ended journal entry `cleanupPending`, not phase or media observation guesses.
  Add route contract test using a terminal row with cleanup still pending. Only
  guarded SFU worker strict ACK may complete the journal cleanup flag.
- Verify targeted then fresh Android full exact count, backend route unit tests
  and full unit suite. Schema/repository unchanged by the route projection; report
  PostgreSQL foundation evidence separately. No claim of deployed remote ACK,
  device audibility, hard expiry under load or durable offline retry.
