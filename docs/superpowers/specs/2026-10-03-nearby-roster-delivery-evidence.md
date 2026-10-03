# Nearby roster delivery cursor foundation

Implemented an owned, serialized server cursor over the durable roster journal.
Registration captures the persisted head before the first authorized snapshot.
Every registered attempt rechecks access, including retry and idle polling.
Publication acknowledgement advances only the exact current offer; a failed
attempt clears that offer without consuming shared journal events. Reconstructed
consumers start from a fresh complete snapshot.

An earlier revision returned Unavailable before authorization when journal read
or batch validation failed. Narrow Luna source review found this mismatch with
the each-attempt contract. Two runtime tests reproduced it (10 tests, 2 failures).
The corrected implementation preserves journal-before-snapshot order but checks
access after a failed read: denied access retires the owner, allowed access yields
Unavailable without a frame. Cancellation propagates without subsequent I/O.
Moving snapshot before journal was rejected because a later journal read could
contain a commit newer than the snapshot.

Validation:

- Fresh backend suite: 123 tests, zero failures/errors. No build cache.
- Fresh Android suite: 2584 tests, zero failures/errors.
- Actual PostgreSQL baseline before review fix: 91 tests, zero failures/errors/
  skips; run `57169190-5091-4605-a1d0-03b20386b877`.
- Actual PostgreSQL delivery class after review fix: 5 tests, zero failures/
  errors/skips; run `585736a9-200e-40cd-a797-e081e28b87ed`.

PostgreSQL tests issue a real repeatable-read revision SELECT, hold it while a
competing cancel commits, and verify first revision1 then revision2 with empty
peers. Other cases cover lost publication, stale ACK, transient read failure,
consumer reconstruction and revoked fixture access despite journal failure.
Acknowledgement does not remove events another consumer still needs. Bounded
JDBC journal reads use a five-second query timeout.

This is a foundation, with no WebSocket scheduler or production community ACL.
Authorization in these tests is an explicit fixture gate, not proof of atomic
membership removal. Ports remain disabled in production. No device audibility,
client transport ownership or peer lease freshness claim follows from these
tests. Exact cursor ownership and ACK-after-publication belong to the forthcoming
transport integration.
