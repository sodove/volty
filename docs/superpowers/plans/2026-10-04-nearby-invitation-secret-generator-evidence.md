# Invitation secret generation evidence

Android invitation generation now defaults to java.security.SecureRandom, requests exactly
32 new bytes and encodes URL-safe unpadded base64. Entropy failure propagates with no
UUID/time/random fallback. Temporary byte buffers are cleared on success and failure; the
resulting immutable String still requires protected storage and careful lifetime management.
Canonical secret holder validates alphabet/length/unused bits and redacts diagnostic text.

Four tests observed missing API RED20s33tasks before implementation. Deterministic
SecureRandom subclasses observe real nextBytes calls and exact encoding/failure/buffer
cleanup, while common tests exercise canonical validation and redaction. They do not prove
statistical entropy or Android provider quality. Narrow Luna source review PASS.

Fresh full Android2743 tests, zero failures/errors/skips,35 tasks executed. Backend unchanged.
No production DI, durable creation draft, stable retry UI, or device Keystore claim yet.
Logs under ignored `.superpowers/sdd/2026-10-04-nearby-invitation-secret-generator/`.
