# Account deletion retires ringing before call media activation

Prerequisite ae1b4cfd, typed destinations. An account tombstone currently closes its
session but leaves incoming/outgoing RINGING rows and peers' caller reservations until
somebody polls. Fix the existing negative writer before introducing accepted media.

Under the existing UPDATE access fence, collect both participants of all affected
RINGING calls along with all previously affected channel actors. Lock the complete
actor set in stable UUID order before any call/session mutation increments a revision.
Then retire only nonterminal ringing involving the deleted actor and release exact
original reservations in the same transaction as account/resources/session cleanup.
Use the existing revoked-access outcome; terminal reasons remain immutable. Preserve
unrelated peer channels and newer calls to another recipient. No separate connection,
network effect, synthetic media ACK or accepted call writer.

Real PostgreSQL tests before implementation: caller deletion preserving callee channel;
callee deletion releases multiple incoming callers; injected account tombstone failure
rolls back call/reservation/session changes; deletion of former recipient preserves a
newer unrelated call; concurrent creation/deletion never commits a live deleted-user ring.
Run missing fixture/API checks if needed, runtime RED on actual SQL, then whole PostgreSQL,
backend unit and Android suites with exact XML counts before commit. Parent manual review.
