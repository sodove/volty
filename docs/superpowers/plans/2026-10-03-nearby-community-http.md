# Nearby community HTTP foundation

Expose the already verified read/create/join repository through authenticated v2
contracts before implementing the client server screens. Default dependency is
null; this slice does not enable production or expose removal/moderation writes.

- Separate CommunityEndpointPort and JDBC adapter. JWT subject owns every call;
  never accept owner/actor from body. Existing transactional repository remains
  authority for membership, bans, visibility, idempotency and coherent revision.
- GET /v2/nearby/communities lists bounded current memberships; GET /catalog uses
  literal query and stable UUID cursor. PUT /{communityId} creates/replays a
  private-by-default server; GET /{communityId} gives member detail; POST /join
  explicitly joins a public server. Joining never invokes voice or sharing.
- DTO list summaries contain id/name/description/visibility/createdAt only. No
  channels, owner ID, membership, positions or credentials in catalog. Member
  detail adds revision, ownerId and channels. Denied/unknown detail/join use the
  same404. Conflict409; absent dependency503; every authenticated response no-store.
- Validate UUID cursor/resource, limit1..50, query<=80, title trimmed1..80 without
  controls, description<=1000/noNUL, explicit PRIVATE/PUBLIC visibility. Stream
  create body at most16KiB, finite10s read deadline, strict UTF8/JSON. Reject before
  port invocation. Avoid unbounded receive/deserialization; no redirects/URLs.
- Route tests use issued calls, auth/no-store, private default, idempotent resource
  identity, exact cursor/search, catalog field privacy, denied private/unknown,
  malformed/oversized input, disabled dependency, and explicit join with no voice.
- Fresh targeted then full backend units exact count. Repository/schema unchanged
  since actual PostgreSQL131; no redundant full SQL run for DTO/route-only changes.
  Fresh Android suite before commit; SDK/runtime/UI and production wiring later.
