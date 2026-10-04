# Nearby account cancellation capability

Before aggregate ownership, tie account API and invitation preparation to the actual account
Job, including its cancelling state rather than waiting for completion of network children.
Existing explicit retire remains terminal; account Job is borrowed, never newly allocated or
cancelled by the binding. Standalone tests/callers may omit it; production aggregate must pass
its authenticated-account lifetime, not an individual screen Job.

1. Add optional Job lifetime to NearbyAccountBinding. Bearer checkout and refresh observe
   lifetime.isActive; inactive lifetime retires credential capability permanently. Already
   checked-out/inflight request may commit, post-call guard still denies publication.
2. Add optional borrowed Job to NearbyAccountHttpSession and InvitationAccountSession,
   propagate to binding; InvitationCreationDraftOwner checks this same Job at all existing
   stage/publication guards so external late callers cannot prepare after parent cancellation.
   Constructor unchanged defaults for isolated existing users; no owned-child jobs created.
3. Tests first: cancelled parent with a deliberately held noncooperative child is NOT complete,
   but bearer checkout/refresh/new APIcalls/new prepare fail immediately; late producer return
   cannot publish; independently created sameactor new account lifetime still works. No wait
   for remote stop/cleanup or claim rollback. Tests release held children in finally.
4. Actual runtimeRED before source, narrow Luna reviews/full suite exact counts/evidence.
   Native/provider proof and trusted auth establishment remain separate; no voice generic
   binding — voice retiring cleanup must retain original stop ownership as documented.

Then aggregate API+browser+draft owner; explicit retirement before screen/account switch,
parent cancellation fallback, resource closure without waiting for noncooperative network.
