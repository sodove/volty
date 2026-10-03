# Nearby explicit remote cleanup projection

Terminal session HTTP replies now include the journal's `cleanupPending` value.
The route does not infer cleanup from CANCELLED/EXPIRED, session ID or transport
observation. The client preserves absent/null as unknown and requires an actual
JSON boolean; quoted `"false"` cannot authorize a cleanup-complete projection.

`VoiceControlOwner` keeps intent-unconfirmed requests and remote-pending requests
separately. Terminal true/null confirms intent only. Explicit false clears remote
pending for that exact request; engine Stopped never clears it. Retry processes
the union of both sets, preserving original operation/generation or session tag
after a replacement. No credentials enter either state flow.

Fresh verification:

- Client3 new cases first failed compilation for missing projection API.
- Client targeted23 then runtime RED1: kotlinx.serialization accepted quoted
  boolean text. Raw bounded JSON type validation corrected this; final23 passed.
- Backend route contract runtime RED1/10 for missing cleanup field; projection
  then passed full124 unit tests, exact count, zero failures/errors/skips.
  Deliberately incoherent terminal-null-session/pending-cleanup fixture ensures
  the route projects repository evidence rather than guessing from normal rows.
- Tests assert issued disconnect/cancel retries and preserve a new generation
  while old remote cleanup is pending. HTTP test issued4 requests covering absent,
  true, false and quoted-false values.
- Final fresh full Android2614 passed, exact count and zero failures/errors/skips.
  No build/configuration caches, tasks rerun.
- Narrow Luna source review passed only true/null versus false remote-set handling,
  exact request removal, engine-release separation and retry union. No tests run
  by the reviewer, no broader actor/SDK/backend review implied.

Actual PostgreSQL131 covered V9/resource repositories before this route-only
projection; schema/repository source did not change afterward. Backend dependencies
remain disabled. Explicit false is the backend cleanup contract, not a new deployed
SFU/device observation. Durable offline storage, retry scheduling, SDK runtime,
Android integration and product exit UI remain prerequisites. No audibility/Xiaomi,
load hard-expiry, production rollout or mutation-sweep claim.
