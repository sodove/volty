# Client invitation commands evidence

InvitationPort separates creation/revocation/historical acceptance. Caller retains the
secret, invitation ID/TTL/quota and acceptance operation; adapter generates none. Secret is
sent only in bounded JSON bodies and absent from URLs/domain receipts/errors. Canonical
43-character base64url checks alphabet and final sextet's two unused zero bits.

Create validates exact server/invitation, server-derived expiry delta equal to original TTL,
exact quota, uses0..max, explicit null revocation fields and positive creation revision.
Revoke validates exact resource/positive revision. Acceptance validates original operation,
canonical returned server/invitation, actual boolean membershipChanged and nonnegative
revision. Receipt is history, not current membership; integration must refresh current detail.
No implicit voice/sharing/DI, redirect or automatic retry. HTTPS/bounds/strict UTF-8/whole10s
deadline/error sanitation follow the reviewed channel adapter pattern.

Eight MockEngine cases observed missing-API RED before implementation. Retraction: initial
scratch fixture manually repeated base64 to47 chars; corrected from actual32-byte encoding
to43 before implementation, not weakened validator. Fresh Android2698 PASS33s/all35 tasks,
exactzero. Narrow Luna source review PASS. Shared last-bit validator is exercised by accept;
create also uses it, while its issued happy path is covered. This is ordinary unit proof,
not entropy/device/production proof. Backend158 verified unchanged source in the preceding
invitation HTTP slice; the subsequent owner-list missing-API test draft is separate work.

Client SecureRandom generation and durable draft lifecycle, owner metadata listing,
production auth/client-factory logging/retry configuration, roster/sharing/runtime wiring,
UI and device validation remain pending. No deployment or legacy group transfer.
