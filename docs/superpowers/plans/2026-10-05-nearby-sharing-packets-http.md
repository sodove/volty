# Sharing packet publication and audience HTTP

Add optional authenticated packet endpoint port, separate from own-consent metadata. POST
original /v2/nearby/sharing/{sessionId}/{generation}/packet with bounded strict typed packet;
GET /v2/nearby/channel-sessions/{sessionId}/sharing/audience. JWT supplies actual actor.
Default unavailable until v2 composition. Denied/unknown session uses generic 404; conflicting
publication generic 409 or 404 without another actor's data; invalid capture/body 400.

JDBC IO adapter delegates proven packet transaction and single same-channel audience query.
Publication acknowledgement contains exact session/generation/sequence and bounded remaining
sample credit, no private payload/actor/raw epoch deadline. Audience contains actual channel,
server observation time, original peer actor/session/generation, normalized packet and finite
remaining access/sample credit. Validate bindings, uniqueness, capture timing and numeric
credit before serializing; sanitize earned/profile fields again for independent port outputs.
Do not conflate empty sharing audience with voice roster count or connected/audible state.

Actual Ktor/auth tests first: disabled/unauthenticated, exact actor/path/body forwarding,
body/canonical IDs bounds, generic denial, misbound acknowledgement, incoherent audience
data/credits/duplicate actors, earned unknown flags and LOCATION/RIDE privacy. JDBC adapter
tests on actual PG only if needed for new time-credit conversion. Fresh exact backend and
Android before commit; full PG317 repository unchanged is retained, new adapter scoped proof
reported separately unless SQL changes justify another full run. No native/UI/deploy claim.
