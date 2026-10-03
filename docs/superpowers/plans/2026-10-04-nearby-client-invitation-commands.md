# Client invitation commands implementation plan

Add the client counterpart to invitation HTTP while leaving visual assembly/production DI
for the approved later integration. Caller owns canonical32-byte invitation secret, stable
invitation UUID/TTL/quota and stable acceptance operation across retries. This port generates
none of them; secure Android generation and draft lifecycle are a separate subsequent task.

1. Meaningful MockEngine tests first: exact stable body-only secret, no secret in URL/result;
   creation identity/server expiry delta/quota/current uses/revision/unrevoked metadata;
   exact revoke identity/revision; historical acceptance operation/IDs/boolean/revision,
   no implicit voice/location; strict types/bounds/UTF-8, redirects/timeout/cancel/errors,
   invalid input rejected before auth/network.
2. Separate typed domain results and HTTP adapter. HTTPS nonemptyhost/no credentials/query/
   fragment, no redirect or automatic retry, whole10s request timeout, bounded16KiB response.
   Canonical base64url43 validation includes unused final two bits to match backend.
3. Narrow Luna/xhigh review/fix loop; fresh full Android/backend suites; evidence/commit.
4. Secure Android invitation creation drafts/generation, authorized roster/sharing/runtime
   integration and new UI remain next approved work. No production activation in this slice.

Integration prerequisite before owner UI: authenticated paginated invitation metadata listing (owner only, no secret/hash) so links can be reviewed/revoked after a process restart. Hash-only backend cannot recover a lost secret; reissuing requires a fresh ID/token. Implement this before claiming the invitation management feature usable.
