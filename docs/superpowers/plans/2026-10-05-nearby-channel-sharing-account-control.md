# Original account/channel-session sharing control

Capture exact authenticated account, borrowed voice runtime and earned original VoiceSessionTag
before allocating sharing HTTP children. Require runtime/account identity and actual wanted
CHANNEL intent/current session+voice generation with fresh lease on queued serial dispatcher;
do not reinterpret channel UUID as session or let accepted CALL share. No location allocation.

Positive start/status guard original account/runtime/session before and after bearer waits and
HTTP. Record canonical explicit sharing generation before sending positive request: an unknown
network result may still have committed, so original negative capability cannot depend on a
successful response. Retain original bearer in one atomic capability, never consult new login.

Use separate bounded positive and negative HTTP children. Account cancellation fences positives,
not original root-owned negative producer. requestRevoke admits only remembered original
generations and is owned by original application root; UI cancellation/logout cannot cancel it.
Explicit admission retirement preserves negatives; explicit close after drain closes only
owned children and fences future work, leaving account/borrowed audio runtime untouched.

Actual existing HTTP conversation fixture: earned channel consent then original revoke across
disconnect/logout with cancelled UI waiter, held positive response across replacement/login
cannot return permission but original generation remains revocable, wrong account/tag/CALL
rejected before allocation or sharing HTTP. Finite barriers and fresh exact full Android suite
before commit. Parent manual review only; no publisher/foreground/location/device proof here.
