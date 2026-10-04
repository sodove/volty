# Authentication caller cancellation before Nearby consumers

The trusted account lifetime is established at da83885b. Before handing it to Nearby,
close pending-auth cancellation: a transport can remain inside NonCancellable after
the caller cancels, and an already-issued credential write can finish before throwing
cancellation to its caller. A cancelled login must not retain Authenticating or leave
an old persisted token when another login is still pending.

1. Actual held-profile fixture: cancel login while its producer still waits; withdraw
   pending local auth before producer completion. Release in finally; assert no account.
2. Replace that cancelled login with a newer successful account; releasing the old
   producer/cleanup must preserve the new account and secure credentials.
3. Hold an actual credential write, supersede with a new pending profile, cancel old
   caller and release write; assert its issued write finished but stored token was
   cleared before the new pending login is cancelled. Do not let new cleanup mask it.
4. Pending authentication retains caller cancellation ownership until verified commit.
   A finite guardian withdraws pending ownership; normal completion retires the guardian
   without logging out. Persistence rollback under the credential lock is noncancellable
   and finishes before a newer writer can commit. No remote I/O under the state lock.
5. Separate narrow review and fresh full Android suite with exact totals before commit.

This is local lifecycle/serialized-store evidence, not native power-loss proof or an
assertion that cancelling an issued backend operation rolls it back.
