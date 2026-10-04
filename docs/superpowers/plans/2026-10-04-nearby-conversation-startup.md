# Explicit conversation startup ownership

Compose original-account runtime acquisition, visible foreground launch/readiness, persisted
audio loading, and join submission under the original repository root. UI waiter cancellation
does not stop an accepted user command. Explicit stop, account retirement, foreground loss,
failed preparation and correlated terminal voice state release the exact foreground ticket.
Membership is never changed. No restore/background rejoin.

One queued serial owner registers an opaque intent BEFORE asynchronous acquisition. Every later
positive step checks that exact intent and original account. Replacing/stop fences first, cancels
pending owned work without joining noncooperative producers, releases only its own ticket and
submits original runtime cleanup. An obsolete acquisition/preferences completion cannot issue
join, install a ticket or release a newer request. Operation IDs must be canonical and unique
for this process owner; invalid/reused IDs leave the earned current conversation untouched.

Foreground port exposes current exact ticket, request, readiness and stop. Android controller
implements this port; visible Activity launch remains a short callback, consumed before audio
loading and never retained for the active conversation. The caller must check lifecycle at the
actual launch (already enforced by controller). No launcher false result is treated as readiness.

After submission, monitor original runtime state correlated by operation ID, original account
and exact foreground ticket. Naked wanted=false/old terminal state is insufficient evidence.
The root observer survives UI lifetime and ends on explicit terminal evidence. All failure paths
release demand; native service independently retains foreground until app-owned cleanup drains.
Failure of request submission is not remote/native cleanup ACK.

Tests first: actual account/coordinator/MockHTTP/prepared audio with controlled foreground gate.
Storage failure releases idle demand; stop during readiness issues no prefs/join; cancellation
of UI command caller preserves root work; held noncooperative old preference read cannot stop
newer published request; backend failure releases demand; logout while preparing releases.
Finite deferred gates only. Missing API RED, runtime RED, then fresh full suite/exact XML.
Parent manual review (Luna quota; no independent PASS). Native Activity/Service/device proof and
new product UI remain separate; no legacy migration/deployment.
