# Owned roster presence projection plan

Continue the authoritative roster replica/HTTP sample toward one current voice-channel list.
Pure state precedes Compose; UI only renders it. No SDK participant count, no microphone or
audibility inference. Production subscription/publication remains a separate integration.

1. Tests first for fresh confirmed count vs joining/connecting, inclusive local expiry without
   changing persisted revision/content, unknown initial/disconnected state (not zero), exact
   tag retirement, stale request ordinals/revisions, same-revision conflict/resync, fresh
   authoritative same-revision read after conservative credit expiry, clock rollback and
   denial/unavailability clearing current presence.
2. Projection owns VoiceRosterReplica, latest issued immutable read token, last accepted
   HTTP ordinal, conservative sampled local deadlines and last observed monotonic time.
   Caller captures a token for every success/failure; old failures cannot erase a newer read.
   Recheck raw epoch-to-local deadline binding even for incoherent provider observations.
   Old tags/replies cannot refresh rows.
   Receive with current time validates sample timing, then feeds the replica; conflicting
   data requests resync and never counts last-known rows as current.
3. Derived per-row freshness distinguishes persisted transport evidence from usable lease.
   CONNECTED count includes only confirmed connected peers with fresh leases. Initial,
   disconnected, invalid/conflicting or revoked state is unknown/countnull. Valid empty
   authoritative snapshot earns zero. Expired peer may remain as stale metadata, never
   connected; time passage affects projection only, not server revision.
4. A later clock rollback invalidates current state and requires a new subscription generation;
   stateless leaseIsFresh alone cannot remember expiry/rollback. Closure/denial clears state.
   No automatic join, capture/share or membership change.
5. Narrow Luna/xhigh pure-contract review; fresh full Android/backend suites, evidence/commit.
   Then generation-owned transport/pulses and atomic authorized server publication, v2 sharing,
   Android/runtime integration, direct calls and UI assembly remain planned.
