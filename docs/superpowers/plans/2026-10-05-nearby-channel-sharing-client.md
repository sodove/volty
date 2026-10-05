# Typed channel sharing consent client

Implement a bounded HTTPS child client for v2 own-consent start/status/revoke. Canonical
original session/generation IDs, explicit mode/TTL, captured bearer supplier; no location,
foreground or voice allocation. Parent HttpClient remains borrowed. No redirects. Ten-second
request bound and strict bounded UTF-8 response; wire actor/deadline cannot grant authority.

Typed reply separates verified ACTIVE observation from original negative acknowledgement.
Positive evidence includes original request ordinal, monotonic issuance/receipt and deadline
derived from remaining TTL at original issuance, never from response arrival. Require exact
session/generation, valid mode/phase and strict numeric credit 1..8h; backwards/negative clock,
overflow, delayed expired reply, wrong IDs, raw server deadlines/actor/private media fields
must not become positive permission. Negative zero-credit REVOKED/EXPIRED only.

Actual MockEngine tests first: emitted paths/body/bearer, delayed original-credit deadline,
negative without positive preflight, incoherent/misbound responses, cancellation during bearer
and closed owned child/borrowed parent, bounded response/UTF-8. This port is not the publisher:
account-root lifetime, original-session authority, pending offline revoke and telemetry policy
are subsequent layers. Fresh full exact Android suite before commit. No production activation,
coordinate publication/native/device claims or legacy groups migration.
