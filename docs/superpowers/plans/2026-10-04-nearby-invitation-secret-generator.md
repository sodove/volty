# Android invitation secret generation

The invitation HTTP boundary already requires a canonical32-byte base64url secret and
stable caller-owned request IDs. Supply the real Android entropy source before invitation
creation UI and durable draft integration.

1. Tests first: generator calls SecureRandom.nextBytes for exactly32bytes, encodes exact
   contents with URL-safe unpadded base64, calls again for every new secret, and propagates
   entropy failure without fallback. Common secret holder validates canonical unused bits
   and redacts diagnostic rendering; expose plaintext only through explicit accessor.
2. Android generator uses platform java.security.SecureRandom and java.util.Base64 (API26),
   no UUID/random/time-derived secret. Tests use deterministic SecureRandom subclass only
   to observe production byte request and exact encoding, never claim statistical entropy.
3. Narrow Luna review, fresh full Android, evidence/commit. Secure durable account-bound
   creation draft, stable retries and UI sharing remain next; no production DI yet.
