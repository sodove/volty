# Owner invitation metadata listing plan

Private invitation management needs a way to inspect/revoke links after a process restart.
Hash-only storage cannot recover a lost secret; a new shareable secret requires a fresh ID.
Expose existing metadata only, never raw token/hash, to the current active server owner.

1. JDBC paginated read under existing repeatable-read transaction: canonical actor/server/
   optional afterUUID, limit1..50, current active owner+server authorization, deterministic
   invitation UUID ordering, exact server filtering. Include expired/revoked metadata for
   management. Former owner/ordinary member/unknown/deleted actor/server receive Denied.
2. Actual PG tests first: owner-only bounded list, changed owner inherits metadata not secrets,
   foreign-server rows excluded, stable cursor/no duplicates, counts/expiry/revoked data,
   deleted actor/server denial. No voice/membership/share effects.
3. Authenticated GET /v2/nearby/communities/{communityId}/invitations?after=UUID&limit=20;
   canonical single query params, limit1..50, optional dependency503/no-store/generic404.
   Recording route tests first; reuse metadata DTO. Matching typed client list counterpart
   validates bounded response and identities; no implicit secret regeneration.
4. Narrow Luna/xhigh reviews, actual relevant PG/fresh backend+Android suites, evidence/commit.
   Then secure Android creation drafts and later UI/production integration. No deployment.
