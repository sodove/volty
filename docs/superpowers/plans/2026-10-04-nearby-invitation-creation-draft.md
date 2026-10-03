# Stable account-bound invitation creation drafts

Continue the approved caller-retained invitation secret/IDs contract before UI. A lost HTTP
reply must retry the same secret, parent, TTL, quota and invitation ID. Durable storage must
commit before the first network request; Android encrypted storage is a separate binding.

1. Tests first: prepare stores once before any command; storage failure issues no HTTP;
   ambiguous reply then explicit retry preserves actual payload; owner recreation with the
   same store does not regenerate secret; changed payload conflicts before entropy/network;
   incoherent foreign stored record or receipt fails closed; retain race uses stored winner.
2. Immutable redacted draft has canonical actor/parent/invitation IDs, bounded TTL/quota and
   InvitationSecret. Account-bound store read/atomic-retain contract never overwrites a
   retained draft and only returns after durable commit. Coordinator validates caller inputs,
   owns immutable actor binding, reads before generation and executes only retained drafts.
   Provider failures are typed/sanitized; cancellation propagates. No implicit retries.
3. Created receipt must match retained parent/id/TTL/quota/revision and valid uses; it is a
   request result, not a current membership/voice/share grant. Failure retains draft. No raw
   token in diagnostics. Narrow Luna contract review, full fresh Android, evidence/commit.
4. Android Keystore-backed encrypted atomic-retain storage, account logout cleanup and
   invitation UI sharing remain required integrations; memory fixtures are not disk proof.
