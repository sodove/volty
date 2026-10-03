# Nearby community read/create/join HTTP foundation

Authenticated v2 routes now expose bounded memberships/catalog, member detail,
stable-ID private-by-default creation and explicit public join through a dedicated
port/JDBC adapter. Production dependency defaults null/503. Removal/moderation and
voice/sharing endpoints are not part of this port.

JWT subject supplies actor for every operation. Catalog/list DTOs expose only
id/name/description/visibility/creation time; owner/channels appear in member
detail. Denied and unknown resources share404 policy. Creation PUT is idempotent
under the repository's immutable request hash. Page/query/UUID/name/description/
visibility are validated before calls; body is streamed at most16KiB under10s
deadline and strict UTF8 decoder. Authenticated responses carry no-store.

Fresh verification:

- Missing port/dependency compile RED; initial9 runtime cases had one fixture
  failure comparing different per-request IDs. Comparison now checks identical
  code/message policy while retaining issued calls for both resources.
- Final targeted11 passed, zero failures/errors/skips, including oversized
  streaming body without Content-Length and malformed UTF8 that would become an
  otherwise valid creation name under replacement decoding.
- Full backend135 passed, exact124 prior plus11 new cases, zero failures/errors/
  skips. Full Android2614 passed before commit, zero failures/errors/skips. Both
  runs reran tasks with build/configuration caches disabled.
- Narrow Luna source review passed catalog DTO field privacy, JWT actor provenance
  for create/join and absence of session/sharing calls only; no broader auth/body/
  routing/SQL review implied. Reviewer ran no tests.
- Schema/repositories did not change after actual PostgreSQL131. That foundation
  evidence remains scoped to SQL; these route tests use an issued-call fake.

This does not enable the product, prove a real client/JDBC HTTP exchange, implement
server screens, invitations/moderation/owner channel CRUD, complete v2 sharing/
account deletion/legacy cutover, or prove Android audio/Xiaomi behavior. No voice
or position consent is inferred from creation/public join.
