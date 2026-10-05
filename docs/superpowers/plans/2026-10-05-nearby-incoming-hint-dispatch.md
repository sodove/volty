# Account-bound incoming hint dispatch

Untrusted push/socket hints contain only a canonical call UUID. Capture the actual current
authenticated account at ingress; reject stale account objects, inactive jobs and malformed
UUIDs before runtime/factory/HTTP work. Use the existing cached voice runtime and incoming
account coordinator, never construct a separate microphone or notification owner per hint.

Account owns submitted work; UI or receiver only awaits it. Recheck exact current account
after each acquisition suspension and reject retired/misbound runtime. Late old-account
work cannot select a new account's notification owner. A submitted hint only requests actual
status verification; true does not claim ringing, notification delivery or audible media.

Actual HTTP fixture tests cover cancelled waiter while runtime acquisition is held, repeated
hints during held status with one notification owner/one original read, invalid and stale
account rejection before acquisition, and old held acquisition across same-user relogin.
Finite gates, fresh exact Android suite and parent manual review before commit. No FCM
provisioning, native delivery claim or production voice/sharing no-op binding.
