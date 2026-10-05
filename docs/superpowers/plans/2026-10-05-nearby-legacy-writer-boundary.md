# Legacy voice/sharing writer upgrade boundary

The approved design retracts transparent v1 compatibility: v1 requests lack original SID and
sharing generation. Add explicit immutable dependency cutover mode. In VOICE_SPACES mode all
eight legacy voice/sharing writers reject authenticated requests with 409
client_upgrade_required before reading their body, membership, consent, minting voice JWT or
issuing writes. JWT authentication still runs first. LEGACY default retains existing behavior
until production v2 factory/rollout enables the full boundary. No deployment in this task.

Preserve profile/friends/legacy group reads and voice provider discovery. Do not infer cutover
from injected optional ports; tests can inject only one v2 port without activating rollout.
Actual Ktor contract tests start a v2 consent then issue all v1 writers (including malformed
bodies), assert exact upgrade code, unchanged original v2 status and zero v1 store admission.
Also test unauthenticated denial and retained read compatibility. Existing legacy suite proves
default behavior. Fresh backend and full Android before commit. This does not terminate old
live voice/sharing; explicit rollout retirement and production wiring remain necessary.
