# Nearby persisted roster revision foundation

2026-10-03. Additive backend V3 migration after V1 legacy data and V2 session
journal. It adds a shared revision clock and durable session outbox. No production
migration or enablement. Snapshot delivery and media observations remain unfinished.

Fresh session state and changed operation phase/deadline append an outbox record
and increment revision inside the existing actor-locked transaction. Revision
lock comes after actor lock. Failed replacement rolls back session, claim, revision
and outbox together. Unchanged replay and admission/cleanup retry flags do not
create a visible change; cancellation before join has no fictional roster entry.
Outbox contains identifiers, phase and deadline, no JWT, location or display name.
It grants no reader authorization. Repeated cursor reads deliberately return the
same undelivered event; durable consumer acknowledgement is later work.

Six actual PostgreSQL checks passed, run55561f29-959f-4ea6-bcf3-c3bed73ae9eb:
visible changes/replay, cancel-before-join, rollback, competing actors, restart/
cursor replay, V2→V3 upgrade with existing pending lease/claim. Zero failures,
errors or skips; owned fixture stopped. A missing-reader compilation RED preceded
implementation; it is not a mutation sweep. Each case uses its own schema.

Bounded Luna transaction review passed phase/deadline atomicity and replay/flag
behavior, with two conditional scope limits. Primary then checked the policy:
actor/operation/destination/session identity is immutable; every existing-state
copy changes only phase/deadline, so ON CONFLICT's identity-preserving update
cannot diverge from the appended event on current paths. The producer callback
is a documented CPU-only port; current caller delegates only to the local JWT
issuer. A Kotlin function type does not enforce purity for arbitrary future
callers. These conditions are preserved rather than overstating the review.

Final fresh full PostgreSQL43 passed (21 repository,16 coordinator/endpoint,
6 roster journal), runface4535-0da2-4b1a-aaf3-ff0cdfd1585e. Zero failures/errors/
skips, all6 Gradle tasks executed, owned fixture stopped. Existing races, lock
timeouts and legacy migration checks passed with the added journal transaction.
Fresh backend unit85 and Android2573 passed before commit.

Registration still means admission readiness, not observed media or audibility.
The current clock/outbox covers session operations only. Community changes and
verified media observations must join the same revision transaction before the
product roster is enabled; atomic membership authorization remains required.
