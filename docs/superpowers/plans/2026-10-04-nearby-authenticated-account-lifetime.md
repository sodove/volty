# Trusted authenticated account lifetime

After authentication ownership stages through b58d567e, establish a concrete account
lifetime at verified profile + exact token commit. Preserve legacy login/friend behavior;
canonical Nearby identity validation belongs at the v2 factory boundary.

1. Add tests for logout cancelling the actual account Job before held child completion
   and before remote logout returns; same-actor relogin obtains a fresh lifetime; same
   generation refresh retains its immutable actor and lifetime while supplying the new
   token. A subsequent foreign-actor profile must not replace its principal.
2. Expose a repository-owned read-only authenticated account state. Its constructor is
   internal, actor immutable, Job parented to repository scope. Token access is bound
   to its original authentication generation and uses owned refresh; never a callback
   that chooses the current global generation. Logout/replacement withdraws state and
   cancels the previous account Job before storage/network waits. Borrowers cannot
   revive this capability by refreshing it.
3. Keep transport/storage waits outside the state lock. Reject mismatched profile actor
   without retrying a refresh as though this were an ordinary expired-token response.
4. Review the narrow lifetime implementation and run the fresh complete Android suite,
   exact XML counts, before commit. Then wire Nearby consumers to the trusted lifetime
   with local consumer retirement independent from account retirement.

Backend source was read: Application.kt auth/refresh resolves the persisted refresh
row's userId, then issues the access token for that user.id; Database.kt createUser
assigns UUID. No unverified JWT decode, backend changes or group migration. This stage
does not enable Nearby UI or prove native audio/remote stop acknowledgment.
