# Stable invitation draft evidence

Invitation creation now has an immutable actor/parent/id/TTL/quota/secret draft and an
account-bound atomic-retain store contract. Preparation reads before generating entropy,
retains before any network, uses a concurrent retained winner and refuses changed payload.
Execution reads only the retained draft; ambiguous failure keeps the same payload for an
explicit retry. Foreign store actor/id records cannot be issued or returned as prepared.

Created receipts are independently matched against exact retained IDs/TTL/quota and valid
timestamps/uses/positive revision. A contradictory provider receipt is not presented as
created. Request receipts grant no current membership, capture or sharing. Draft diagnostic
text inherits the redacted secret holder. Storage/entropy/HTTP exceptions have typed safe
results; cancellation propagates, including after a retain commit, without deleting a draft.

Seven tests observed missing API RED20s33tasks before implementation. Initial full2750
passed29s35tasks. Two additional contract tests exercise cancellation after commit and
entropy failure. Final fresh full Android2752 tests, zero failures/errors/skips,30s35tasks.
Narrow Luna source review PASS. Backend unchanged since fresh161 PASS9s6tasks.

The memory store fixture proves coordinator ordering/retry contract, not disk durability,
encryption, Android Keystore or process-death persistence. Those require the actual store
binding and appropriate device verification. No production DI, logout cleanup or UI claim.
Logs under ignored `.superpowers/sdd/2026-10-04-nearby-invitation-creation-draft/`.
