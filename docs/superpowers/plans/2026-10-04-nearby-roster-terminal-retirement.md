# Roster terminal retirement

Manual review found that runtime-local terminal closure (access denied, clock rollback
or exhausted read ordinal) cancels the runtime but leaves the account consumer guardian
and its HTTP port admitted until logout/explicit retire. An earlier owner revision
treated runtime closure as sufficient; that does not revoke direct snapshot permission.

Add a regression using actual login and MockEngine403. Assert earned retirement,
snapshot cancellation before another HTTP request, refresh false, authentication alive,
and fresh owner on the same account usable. Finite pulse channel, finally cleanup.
After actual RED, observe terminal runtime state in the already-undispatched guardian;
its finally locally retires and independently closes adapter/runtime. Account cancellation
and explicit retirement still execute the same finally. No parent join/cancellation.

Fresh full suite exact count and parent manual review. No audio/native stop ACK claim.
