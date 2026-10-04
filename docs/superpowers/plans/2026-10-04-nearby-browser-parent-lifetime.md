# Nearby browser lifetime retirement

Before aggregate account composition, make existing read-state owner retire with its parent.
1. Tests first: invalid pageSize must not allocate an orphan child Job; parent cancellation
   immediately clears previously rendered account data even while a noncooperative read is
   outstanding; releasing that read never repopulates state; independent transport cancellation
   on a still-active owner becomes finite unavailable read, not permanently loading.
2. Validate pageSize before child lifetime allocation. Add cancellation guardian clearing
   state inside the same publication mutex under NonCancellable, without waiting for network
   children. Existing close remains explicit/no membership/voice sideeffects. All read callbacks
   already check lifetime and request generation under publication mutex.
3. In network cancellation catch, rethrow actual caller/lifetime cancellation; classify an
   independently cancelled transport as CommunityFailure.UNAVAILABLE while owner is active.
   Existing finite scope/test clocks, no delayed loops. Narrow Luna review and fresh exactfull
   suite before commit. No Compose/native/device claims.

Then aggregate API+browser+invitation owner into one immutable account composition with
explicit retirement before UI/account switch; keep voice cleanup ownership separate.
Additional lifecycle case: parent cancellation before first queued coroutine dispatch must clear synchronously published query state. Register guardian with CoroutineStart.UNDISPATCHED; do not rely on a later queued guardian start.
