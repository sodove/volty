# Authenticated channel commands implementation plan

Execute the approved Nearby under-the-hood plan; UI/mockups remain deferred to assembly.
Existing channel create/delete repositories are the authority. Add an optional endpoint port
and authenticated HTTP routes with stable caller-owned channel/operation UUIDs. Production
dependency stays disabled until the coordinated v2 cutover; no legacy group transfer.

## Contract

- PUT /v2/nearby/communities/{communityId}/channels/{channelId}: bounded strict UTF-8 JSON
  name; authenticated actor only; canonical IDs and normalized name1..80 without controls.
  Returns community/channel identity, normalized title/default flag and creation revision.
- POST /v2/nearby/communities/{communityId}/channels/{channelId}/delete: bounded strict UTF-8
  JSON operationId; explicit deletion returns exact resource identity, revision and actual
  durable cleanupPending aggregate. Never expose SID/epoch/token/actor cleanup targets.
- Unknown/unauthorized resources share a generic404; conflicts409; channel cap409 with
  channel_limit_reached. Optional unwired dependency503. All v2 responses no-store.
- Reject invalid/oversize/malformed UTF-8 before invoking the port; body actorId ignored,
  current authenticated actor passed. No automatic request retry or replacement-channel join.
- Reuse the existing bounded community request reader; narrow visibility change only.

## Steps

1. Recording-port route tests first; observe missing-API RED.
2. DTOs, endpoint adapter, optional dependency and guarded routes; preserve repository
   transaction semantics without moving SFU calls into SQL.
3. Narrow Luna/xhigh source review, fresh backend and Android suites, evidence and commit.
4. Matching client channel commands and private invitation HTTP/client follow afterward.
