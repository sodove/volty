# Private invitations evidence

V11 adds hash-only invitation metadata and actor/operation acceptance receipts.
Historical V1..V10 are unchanged. Four migration-count expectations moved10 to11,
including channel creation. Caller owns the random token and stable invitation UUID;
test fixture tokens are deterministic and do not prove client entropy or persistence.

Current active owner create/revoke and authenticated acceptance run under the UPDATE
access fence, serializing deletion/ban/leave/revoke/quota. Expiry is server-derived,
inclusive, TTL15min..7days, quota1..1000. Exact create replay preserves expiry;
changed payload, reused token under another ID and revoked ID conflict. Revocation
keeps existing members and replays its original revision. New acceptance changes
only permanent membership; no voice/session/sharing effects. Existing member requests
consume no use or revision even at exhausted quota. Acceptance replay returns historical
actor/operation receipt and cannot recreate membership after leave/revoke.

Thirteen actual-PG cases and two pure token/bounds cases observed missing-API compile
RED before implementation. Initial fresh PG13 PASS, run8815362b-db9e-4f71-b16a-d2425ffb9b95,
zero failures/errors/skips. Includes real issued access-fence barriers in both revoke/
accept orders, competing final-use requests and outbox rollback.

Retraction: the first V11 CHECK and narrow source review claimed revocation pair
consistency but overlooked SQL UNKNOWN. Deliberately incoherent revoked_at=101 with
revocation_revision=NULL was accepted. Added actual PG1 RED run94abecdd-4800-45df-9928-5449b9a4dbb5:
expectedSQLException/actualsuccessfulUpdate. Added explicit IS NOT NULL; final combined
schema/regression verification is recorded below after completion.

Both initial disposable runs stopped their owned containers. Narrow Luna source
reviews of create/revoke and acceptance passed; schema fix-loop is separately reviewed.
HTTP/client crypto, durable invitation drafts, UI and production cutover are pending.
No legacy group/invitation transfer or production deployment occurred.

Final fresh PG51 PASS, runfb73cf77-4ad9-46f0-8f5a-5c6e230fdd7e: invitations14, repository21, historicalmigration4, retirement5, channelcreation7; exactzero, all6 tasks executed, owned_container_stopped=true. Schema fix-loop Luna PASS. Final backend145 fresh rerun8s/all6 tasks and Android2680 fresh rerun31s/all35 tasks, exactzero. Android sources unchanged across backend schema fix. Ordinary tests only, no mutation/device proof.
