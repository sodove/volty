# Roster presence projection evidence

One serial subscription owner now distinguishes unknown presence from authoritative empty,
counts only fresh confirmed transport rows, and preserves persisted revision/rows at local
lease expiry. Exact latest-issued read tokens own successes and failures. Old/foreign or
duplicate completions cannot change state or advance its clock. HTTP ordinal ordering is
checked separately. Older revisions cannot recredit rows; coherent newer reads of the same
revision may restore conservative credit. Conflicts request resync once and hide current rows.

Local credit is independently bound to raw deadline/server time/request start, including
deliberately incoherent provider fixtures. Clock rollback permanently retires a generation;
access denial does likewise. Transient failure/disconnection clears current presence until
an explicit new successful read. No membership, capture or sharing effects.

Ten tests observed missing API RED before implementation (19s33tasks). Fresh full Android:
2722 tests, zero failures/errors/skips,29s35tasks. Two narrow Luna source reviews PASS for
token/revision and clock/credit/failure boundaries. A later fresh full run also includes six
separate URL corrections; see their evidence. Backend unchanged since fresh161 test run.
Ignored ledger stores logs. No Compose/device/audibility or production subscription claim.
