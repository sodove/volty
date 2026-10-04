# Invitation account lifetime

Continue encrypted draft storage by adding terminal account ownership before production DI.
1. Draft owner retire() is immediate/idempotent, terminal for that owner. Check retirement and
   caller cancellation before each external stage and before publishing any result, including
   late storage/HTTP success or failure. Already committed drafts remain durable for explicit
   same-account retry with a new owner. No deletion or network rollback claim.
2. Immutable typed actor+bearer credential snapshot; safe diagnostics; account binding accepts
   refresh only for the same actor, rejects updates after retirement, exposes no current-global
   token reader. Lifetime terminal atomically using StateFlow CAS; no ABA revival.
3. Actual invitation account session creates HTTP port from binding, validates store actor,
   owns draft coordinator and cloned HTTP port. Retire drops local bearer, retires owner, closes
   owned HTTP resources; supplied parent client stays usable. No old owner can publish late
   receipt or use another account's credentials. New session for same actor is independent.
4. Tests drive retire-before-call, retire-during-read/retain/network incl failed late returns,
   caller cancellation swallowed by a noncooperative port, refresh/foreignactor/update after
   retirement, actual MockEngine issued auth with independent accounts and parent client.
   Watch RED, full suite exact count, narrow Luna review; commit evidence.

Limits: no automatic old SocialRepository wiring, native erase, physical token zeroing,
server revocation, or rollback of an already issued command. Drafts preserved intentionally:
a lost HTTP response must remain replayable with its original token after relogin.
Production logout must retire this session before exposing the next account; wiring follows.
