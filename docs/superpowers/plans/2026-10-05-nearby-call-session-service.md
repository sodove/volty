# Typed prepared-call SFU admission

Prerequisite 20118f50. Keep channel join CHANNEL-only. A separate internal prepared-call
resume accepts only an existing original DIRECT_CALL operation and exact destination;
never creates an operation, accepts a ring, changes ownership or reinterprets a nonce.
Outsiders and wrong bindings cause no network work and do not cancel the original pair.

Add explicit optional typed room resolution to SessionService, defaulting to channel-only
compatibility. Prepared admission, worker admission, current media refresh and connected
heartbeat resolve by the persisted destination kind. All network requests remain outside
SQL locks. The SQL pair owner rechecks friendship, claims, epochs and deadlines after every
network result. Registration alone is not observed transport or audio. Tokens and credit
retain the shortest peer deadline. Original terminal operations cannot be resurrected.

Tests before source on real SQL: caller credit with pending peer; both admissions and
actual current SFU observation/conditional renewal through CALL room only; stop during
registration before ACK; outsider and wrong-call nonce cause no SFU work or mutation.
Then supplement timeout/failure/worker/current-disconnection review and run full fresh
PG/backend/Android with exact XML before commit. No route/DI/client/device activation.
