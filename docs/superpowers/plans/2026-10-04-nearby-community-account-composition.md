# Nearby community account composition

Next slice after account cancellation capability; not production auth/DI cutover.
Compose existing bounded community commands, browser state, and durable invitation
preparation for one authenticated actor. No legacy group import, no voice shutdown
through a generic HTTP lifetime, and no native audio claims.

1. Require a parent scope carrying the authenticated account Job. Validate actor/store
   and HTTP root before allocating owned jobs/adapters. Borrow account Job; retirement
   must not cancel the caller's parent or the shared application HttpClient.
2. Construct one NearbyAccountHttpSession with that Job. Use its guarded invitation
   port for InvitationCreationDraftOwner with the same Job. Do not maintain two
   independent refreshable credentials. Compose CommunityBrowser as an owned child.
3. Explicit retirement first fences invitation preparation and HTTP permissions, then
   clears browser state. Parent cancellation is fallback: register an undispatched
   cleanup guardian so cancellation before its first scheduled dispatch is covered.
   Resource closure attempts remain idempotent; no joining held network children.
4. Tests first: cached private rows and held reads; account cancellation before first
   dispatch; explicit retirement from an already-cancelled caller; zero post-retire
   entropy/storage/HTTP calls; preserved draft retry by a fresh same-actor account;
   parent Job/client remain usable after explicit retirement; malformed construction
   leaves no orphan jobs. Assert issued/published effects, release producers finally.
5. Source review and uncached full Android suite with exact XML count before commit.

Integration boundary: authenticated profile must be paired with the exact token and
account operation that established it. Existing DefaultSocialRepository globally
stores credentials and has no generation fence on delayed authentication/refresh.
Do not infer an actor by unverified JWT decoding or wire the new aggregate to mutable
global token/profile snapshots. That auth boundary is a separate subsequent slice.

UI assembly, direct calls, voice retirement/drain, foreground lifecycle, and device
verification remain subsequent work. Job cancellation is not a remote command rollback
or evidence of native microphone release.
Concrete composition interface: di.nearby.NearbyCommunityAccount(parent: CoroutineScope,
credentials: NearbyAccountCredentials, store: InvitationDraftStore,
generator: InvitationSecretGenerator, client: HttpClient, baseUrl: String).
Expose actorId, api: NearbyAccountHttpSession, browser: CommunityBrowser,
invitations: InvitationCreationDraftOwner, cleanupFailed: StateFlow<Boolean>,
refresh(credentials), suspend retire(). No global Koin registration in this slice.

Ruling: use borrowed parent Job directly for API and draft owner, not another aggregate
SupervisorJob. Browser already owns its child; cleanup guardian owns only its launch child.
Require explicit Job in parent context (CoroutineScope factory adds one automatically, so
no-Job test uses custom CoroutineScope implementation). Validate Job/store/root before children.
Guardian starts UNDISPATCHED and in finally runs cleanup in NonCancellable. Explicit retire
first retires owner and HTTP synchronously before any suspension, cancels only the guardian,
and runs/retries serialized cleanup in NonCancellable; account parent remains active.
Both HTTP close and browser clear are attempted despite one failing; safe boolean cleanup
failure retained instead of an uncaught guardian exception. Successful retry resets it.
Never join browser/network children; local state clear is not proof of native resource release.
