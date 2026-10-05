# Direct-call roster under original pair ownership

The channel roster deliberately filters CHANNEL and cannot serve call participants. Add a
separate call snapshot type and reader on the existing pair owner, with no generic channel
membership fallback even if UUIDs collide. Only original caller/callee of an accepted, live,
accepted-friend pair may read; RING has no media roster. Reconcile original elapsed/lost leases
and claims in the same fenced actor-pair transaction before exposing rows. Terminal/unknown
and outsider reads return absent, never an empty successful roster with fabricated authorization.

Return exactly the original pair, one journal revision/time, transport-only phase and actual
participant SID. Pending is JOINING, registered is CONNECTING, actual ACTIVE observation is
CONNECTED. No audibility claim. Clip peer credit to the shorter actual pair lease so one phone
cannot continue trusting a peer after the original other lease expired. Durable cleanup remains
journal evidence; reads do not invent SFU cleanup acknowledgment or perform network IO.

Implement repository first with real PostgreSQL tests for equal caller/callee snapshots,
incomplete transport evidence, outsider/RING denial, friend revocation, lease expiry, and
same-UUID channel isolation. Then authenticated HTTP endpoint and explicit typed client roster
target, preserve channel API and ordinal semantics. Parent manual review, finite fixtures,
fresh exact backend/PostgreSQL/Android verification before every commit. No new subagents.

Wire privacy clarification before HTTP: the repository's participant descriptors are internal
journal rows; call HTTP peers omit original caller/callee operation IDs as well as caller keys
and credentials. The earlier generic channel-peer reuse is not a wire serialization contract.
Use a dedicated call participant DTO/client model rather than inventing operation IDs to fit
the channel replica. Call target and original expected pair remain explicit. This preserves
the existing rule that original caller nonce is absent from peer call snapshots.

HTTP uses GET calls/{callId}/roster on the same explicit call endpoint dependency. Null read
is 404; invalid binding/cardinality/actor/session/phase/credit evidence is 503 with no peer data.
The pair has one equal clipped lease, bounded to 30 seconds; no credentials or operation IDs
are serialized. Client transport must require explicit call/own actor/expected peer identities,
strict numeric fields and transport consistency, bounded body/time and no redirects. Return
a dedicated call observation with issuance ordinal/start/receive times; no channel tag or fake nonce.

Trusted account read owner borrows only the captured SocialAuthenticatedAccount and parent client.
Pin the original call/peer/subscription generation, guard before and after token/HTTP suspension,
and close only this read child on explicit retirement or account cancellation. This has no original
negative media cleanup permission; the account voice runtime retains that separate capability.
Tests use actual HTTP with held replies, same-account refresh, same-user new account, mismatched
tag zero HTTP, and borrowed account/client survival. A cancelled old reader cannot revive through
a new login or publish a held result. No polling loop or notification-triggered capture here.

Pure projection accepts only the pinned call/peer/subscription and original observed session
pair. Drop older request ordinals or older journal revisions without adding credit. Equal
revision with different rows or raw lease deadline requires resync; a new session ID under the
same call cannot replace earned identity. Presence is unknown until an actual valid snapshot,
when resync is needed, after retirement or at lease expiry. Transport-confirmed rows are a
separate subset and do not imply heard speech. Access/auth denial retires permanently;
temporary failures withdraw current presence and retain high-water evidence for retry.

Owned roster runtime uses a queued serial dispatcher and borrowed parent Job, one read producer
at a time, bounded request timeout, broadcast/cold scheduling pulses and coalesced explicit refresh.
Tick freshness independently of a held request; close/account parent cancellation immediately
withdraws rows while an ignored-cancellation producer remains counted until actual completion.
Late producer results cannot resurrect a closed subscription. Clock reset, pulse failure/completion
and access denial fail closed. Runtime owns its child lifetime, not the port or parent account.
Start it at root only after the selected call has earned an original session identity; RING and
incoming discovery do not get a media roster or microphone permission.
