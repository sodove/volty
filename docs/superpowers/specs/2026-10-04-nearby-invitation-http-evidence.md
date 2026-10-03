# Invitation HTTP evidence

Optional InvitationEndpointPort delegates to existing V11 fenced repositories. PUT stable
invitation UUID receives token/TTL/quota in a bounded JSON body, POST revoke is resource-
idempotent, POST accept receives actor-owned operation UUID and token in body. Authentication
supplies actor; canonical path/body IDs, canonical32-byte token and bounds validate before
the port. Uniform404 invitation_unavailable and409 invitation_conflict contain no secret.
Responses expose metadata or historical acceptance receipt, never token/hash/voice/position.

Shared community reader provides16KiB/10s bounds and strict UTF-8; authenticated no-store.
Production dependency remains null. Six recording-port route tests observed missing-API
compile RED before implementation. Fresh backend158 PASS9s/all6 tasks and Android2690
PASS31s/all35 tasks, exactzero. Tests assert exact issued caller identity/payload, replay-
stable create/revoke, historical membershipChanged semantics, sanitized denial/conflicts,
invalid canonical tokens/bounds/IDs, streamed oversize and malformed UTF-8. Repository PG
evidence stays in the prior invitation slice; no fresh full-PG or device claim here.

Narrow source review result is recorded after completion. Client token generation and
durable draft ownership, invitation commands/UI and production cutover remain pending.
No deployment or legacy group transfer.
Narrow Luna source review PASS, no findings. Before commit a further full Android2698 fresh rerun33s/all35 tasks passed with the pending matching client invitation tests in the checkout; backend source unchanged since backend158 verification.
