# Nearby current-media coordinator and durable hook worker

Heartbeat now queries the current incarnation-fenced SFU participant, records
that observation with actor-locked version CAS, renews only the same ACTIVE SID
under the SFU entry lock, and accepts the receipt only against that SQL SID/version.
Unknown/JOINED media retains its original admission deadline. A rejected old-SID
renewal does not cancel a replacement transport. Failed live observation cannot
reuse cached ACTIVE evidence. No SFU network operation runs under SQL locks.

The durable inbox worker treats signed hooks as reconciliation hints. It queries
current media without renewing a lease or minting credentials, then acknowledges
the exact event ID/digest after a durable outcome. A delayed LEFT for an old SID
cannot stop a newer SID. Terminal old sessions and foreign rooms cannot affect
replacement claims. Failed observation retains the intent; lost inbox ACK safely
replays committed media without another visible revision.

Evidence: service runtime RED (23 tests/8 failures) dd4618c0 preceded the switch;
fresh targeted 23 PASS 7b3a5fd5. HTTP ACTIVE-only confirmation had runtime RED
before correction. Worker missing-API compile RED preceded actual PostgreSQL5
PASS 9f8da399. Full fresh PostgreSQL75 PASS
400f7e30-4949-4330-81d2-27d69488c253, zero failures/errors/skips, owned pool and
fixture cleanup confirmed. Fresh backend110 and Android2573 PASS.

CONNECTED means SFU transport evidence, not phone audibility. Primary source
review and these tests are recorded; no independent review of this delta is
claimed. Production dependencies remain null. Room-finished handling, remaining
grant client/server adaptation, worker scheduling, delivery cursors, atomic
community authorization and product UI integration are still pending.
