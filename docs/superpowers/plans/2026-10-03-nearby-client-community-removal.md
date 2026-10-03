# Nearby client explicit leave/delete boundary

Add a separate typed removal port for the newly authenticated server endpoints.
Caller retains immutable operation UUID/community/successor across explicit retry;
no automatic retry, successor selection, deletion fallback or new UUID fallback.
Keep membership removal separate from disconnecting voice/trip while staying a
member. Neither read browsing nor joining implies this permanent removal action.

Owned HTTPS child client, no redirects, secure bearer supplier, finite whole10s
deadline, streamed16KiB cap, strict UTF8/JSON and cancellation propagation.
Validate expected operation/community IDs, nonnegative actual JSON revision and
required actual JSON cleanupPending boolean. No raw body/token/exception in state.
Typed owner-action conflicts from bounded409 code; unknown code means conflict.
Receipt means membership/deletion SQL committed; true means media cleanup still
pending and false only reports backend receipt, not Android engine release.

Target RED/green asserts issued stable body/path/bearer, explicit leave/delete,
owner conflicts, foreign IDs, unknown/quoted/missing flag, strict UTF8, size bounds,
no redirect, finite timeout, cancellation and safe exceptions. Full Android before
commit; narrow Luna review. UI confirmations, mutation owner, durable retry/DI,
account retirement and actual SFU/Android acceptance remain later tasks.
