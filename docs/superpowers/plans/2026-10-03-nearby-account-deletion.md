# Nearby account deletion fence

The approved community plan requires deleting an account to close its owned
communities and affected sessions. Existing JdbcStore.deleteAccount bypasses the
v2 access fence. Replace that path with one UPDATE-fenced transaction: collect
owned communities/affected actors, lock all actor claims in sorted order before
any revision, cancel the deleted actor's exact current claim regardless of its
destination and other actors' claims only inside owned communities. Tombstone
owned resources, retain historical owner memberships for the deferred FK, remove
membership in other communities, revoke legacy location and tokens, mark user
deleted, append changes atomically. No network under SQL locks. Repeated deletion
does not invent revisions or target replacement work.

Author real PostgreSQL tests before API: exact claims/unrelated preservation,
rollback on final outbox failure, idempotence and post-delete credential/admission
denial, incoherent claim rollback, and actual SHARE/UPDATE issued-query barriers
in both orders. Reuse AccessFenceBarrierSource with finite gates. Integrate the
existing JdbcStore path, not a disconnected optional prototype. Schema remains V9.
Fresh Android/backend suites and exact disposable PostgreSQL counts before commit.
