# Account-bound incoming action registry

Resolve native action payload as two opaque original identities: notification binding and
ticket. A fresh binding nonce per exact account/owner prevents an old PendingIntent from
targeting a new login even when actor and ticket UUIDs deliberately collide. Validate real
account object/owner/root before replacing previous binding. Registry owns only the unique
notification child; replacing/detaching it cannot retire shared audio runtime.

Queued accept/decline delegate to tested original ticket actions, recheck binding after
accept suspension, and refuse obsolete/foreign/retired account without HTTP. Original negative
Deferred already submitted survives registry close/replacement. Detach exact old binding cannot
close a newer binding. Close survives cancelled UI caller. No mirrored bearer/global token or
job authority. Actual HTTP tests on existing root fixture: positive action, same-user relogin
with identical ticket ID, wrong owner/account denial before current replacement.

Native factories must capture binding ID in their immutable unique PendingIntent data; backend
UUID is never sufficient. Android handling/production activation follows after this registry.
Fresh full Android suite before commit; parent review, no FCM/native proof.
