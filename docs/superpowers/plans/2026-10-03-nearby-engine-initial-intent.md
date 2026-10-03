# Nearby engine initial mute intent

Before wiring the HTTP owner to the SDK driver, fix an observed source-level
race: Android `setMuted` returns false until a publication exists; driver start
later publishes using the original effect's mute value. A mute during connect
can therefore fail and be overwritten. Device audibility is still unmeasured.

- RED tests use a blocked connect/publication and a deliberately unready track:
  setMuted before publication cannot pretend a track exists. Assert issued SDK
  commands, not timing. Driver accepts/stores latest mute for the exact command.
- Initial publication always starts muted. Once publication exists, reconcile
  latest desired mute through a per-handle serialized control owner. Changes
  during connect/publication cannot be overwritten by the initial effect.
- Cache mute intent even before SDK allocation, tagged to the current command;
  a superseded generation cannot mutate a new command. Never await SDK work while
  holding the driver's ownership mutex, so stop can still invalidate ownership.
- Serialize post-publication mute updates and sample latest intent inside that
  serialization, reapply if intent changed during an awaited SDK operation.
  Publication/mute failure retires/releases the owned stack; no success claim.
- Preserve initial deafen before connect and exact generation release semantics.
  A subsequent RED fixture also showed deafen lost while an old stack's release
  blocked new allocation. Cache it in the same tagged command and sample/apply
  through the synchronous speaker-mute setter before connect.
  Keep this change separate from engine effect runtime, timers, routing and UI.
- Targeted fresh driver tests then full Android exact count, bounded virtual-time
  fixtures. No Android source/device audibility assertion from pure SDK fakes.

Backend full PostgreSQL131 is independent and running; no backend edits/builds.
