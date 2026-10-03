# Nearby owner resource deletion

Continue after atomic departure93f491c6 and client mailbox96401953. Last owner
needs explicit deletion, independent of ordinary voice disconnect. Primary owns
SQL; simple scoped Luna reviews only. Keep production routes/DI disabled.

## Community deletion foundation

- Count V1..V8; add V9 actor/operation-owned resource deletion receipt and exact
  immutable cleanup targets. No old migration edits. Replay must return original
  revision/targets; conflicting resource cannot delete something else.
- Require active owner for a new deletion. Matching receipt replay requires the
  same active actor, does not require the resource still ACTIVE. Outsider/unknown
  requests return the same denial. No server recreation under a deleted UUID.
- UPDATE access fence first. Collect current members plus actors whose current
  operation points into this community. Lock ALL affected actors in canonical
  UUID order before terminalizing ANY claim or acquiring revision clock. Existing
  single-actor helper can reacquire an already held actor lock after batch locking.
- Cancel only exact current community claims. Incoherent claim identity rolls
  back instead of guessing an SFU target. Preserve voice in other communities.
- Tombstone community and every channel, append DELETED event/receipt/targets in
  the same transaction. Keep internal membership rows with the tombstone: V7's
  deferred owner-membership FK must remain valid. They are historical rows, not
  discoverable/active membership; all existing reads/admission/join require ACTIVE.
  Record this deliberate design choice rather than silently removing the FK.
- Delete matching legacy group after exact cleanup is journalled; V1 cascades its
  legacy members/sharing/locations/invite code. New UUID tombstones and cleanup
  receipts remain. This does not make unfenced old-instance writers safe; final
  legacy cutover/quiescence is still mandatory.
- No SFU network under locks. Return durable exact targets for worker processing;
  deleted SQL state is not media revocation ACK. Integrate future v2 sharing before
  exposing removal routes. Account deletion remains a separate atomic fence task.
- Actual PG tests: owner alone; multi-member exact claims; unrelated voice
  survives; outsider denied; replay stable after cleanup; conflicting operation;
  deleted UUID cannot rejoin/recreate; deliberately incoherent claim rollback;
  forced final outbox failure rolls back all claim/legacy/tombstone/receipt changes.
- Fresh targeted PG then full exact-count PG and backend units. Android suite
  before commit. Never edit backend while its actual PG build runs.

## Next separate tasks

Owner channel deletion needs immutable channel target in the same receipt schema,
with explicit default-channel policy. Moderation, invitations, account deletion,
v2 sharing, HTTP services, device adapter and UI are still required. This plan's
first slice must not pretend those are complete or silently enable production.
