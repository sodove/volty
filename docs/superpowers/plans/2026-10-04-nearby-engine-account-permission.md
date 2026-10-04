# Engine account permission fence

Full voice cleanup must live outside the account Job, but native start/publication and
preferences must still check the exact authenticated lifetime. Existing engine bridge
only samples voice intent and lease; during queued logout retirement that state may
still look authorized. Supply an additional nonblocking account-permission predicate
to VoiceRuntime and VoiceEngineEffectRunner, combined with existing intent/tag/lease
authorization at every driver checkpoint. Exception fails closed. Stop/cleanup bypass
this positive predicate and remain retryable on the independent parent scope.

Tests first with real repository login/logout and deliberately still-earned voice state:
queued start never allocates after logout; held issued connect never publishes when it
returns; preferences denied but failed native close still retries; full Runtime forwards
the gate and rejects a late grant before native allocation. Finite gates, no delayed loop.
Missing API RED then signature-only ignored predicate runtime RED, fresh exact full suite
and parent manual narrow review. Actual device mic/route, global account handoff/drain,
foreground and application composition remain required separate integration work.
