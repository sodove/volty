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
