# Nearby client community HTTP boundary

Build the typed client boundary for the new authenticated community routes before
server components/screens. Backend/Android final checks for the preceding route
slice are running; no source edits until they finish. Default DI remains disabled.

- New community/channel ID and summary/detail models are independent of legacy
  ride groups. Membership, catalog and member detail never start voice/location.
- Owned child HttpClient, HTTPS base, injected secure bearer, redirects disabled,
  finite10s complete request/body deadline and cancellation propagation. Bound
  response512KiB: a50-item page can contain1000-character descriptions. An earlier
  plan chose256KiB from raw UTF8 size; a runtime RED legal fixture showed JSON
  escaping can require six bytes per allowed character and exceed that bound.
  Neither the voice boundary's16KiB nor the earlier256KiB cap fits every legal page.
- Methods memberships/catalog/detail/create/joinPublic. Stable community UUID
  and original creation payload are caller-owned across retries; no automatic
  retry or create-new-ID fallback. Explicit PRIVATE default; PUT creation URL.
- Safe typed failure401/403/404/409/503/network/invalid body, no raw body/message/
  exception text in outcome or logs. Bearer/auth-refresh lifetime belongs to the
  injected supplier. Parse bounded strictUTF8/JSON and validate canonical IDs,
  title/description lengths, known visibility, nonnegative creation/revision.
- Page count<=requestedlimit; strictly increasing unique IDs after the requested
  cursor. Public catalog rejects PRIVATE items instead of rendering them. Detail
  requires expected communityID and unique channelIDs with valid boolean default
  metadata. A foreign-resource response cannot update the chosen server screen.
  Bound decoded detail to128 unique channels; future creation must enforce a
  matching limit and consistent default-channel policy.
- Catalog state contains summary fields only. Owner/channels belong to member
  details; no credentials/session/location data or fabricated membership consent.
- MockEngine fixtures share testScheduler dispatcher. Assert issued URL/query/
  bearer/body, stable creation ID/defaultvisibility, response privacy/provenance,
  malformed/missing/foreign fields, private catalog, cursor duplicates, response
  size bound, no redirect and issued-request cancellation/finite timeout.
- Targeted RED/green then full Android exact counts, narrow bounded Luna review,
  evidence and ledger. Device/SDK/runtime/pagination UI/DI are separate tasks.

Future owner channel creation must impose a bounded channel count before a detail
response can grow without limit. Existing foundation has one default channel;
this adapter's response bound must still fail safely for oversized detail.
