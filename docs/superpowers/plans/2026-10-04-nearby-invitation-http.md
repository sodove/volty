# Private invitation HTTP implementation plan

Expose existing V11 invitation repositories through an optional authenticated endpoint port.
No production activation, UI or client token generator in this task. Legacy invitation codes
and groups stay untransferred; accounts/friends stay preserved.

- PUT /v2/nearby/communities/{communityId}/invitations/{invitationId}: caller-owned canonical
  UUID and canonical32-byte unpadded base64url token in bounded body only, TTL15min..7days,
  quota1..1000. Authenticated actor only. Return metadata without token or hash.
- POST same path /revoke: resource-owned stable revocation, no auto member ejection.
- POST /v2/nearby/invitations/accept: stable actor-owned operation UUID and raw token in bounded
  body only. Return historical acceptance receipt (explicit membership only); never assert
  that replay is a current membership/voice/location grant.
- Uniform404 invitation_unavailable for unknown/expired/revoked/banned/unauthorized;409
  invitation_conflict for stable-ID conflicts;503 when unwired. All authenticated no-store.
- Reuse16KiB/10s strict UTF-8 reader; canonical token validation and bounds before port.
  No secret in server URLs/query, responses, errors or log messages; invalid errors generic.

Recording-port route tests first and missingAPI RED; implement thin DTO/adapter/routes;
scoped Luna/xhigh source review; fresh backend and full Android tests; evidence and commit.
Then client token ownership/HTTP commands, authorized roster/sharing and Android integration.
