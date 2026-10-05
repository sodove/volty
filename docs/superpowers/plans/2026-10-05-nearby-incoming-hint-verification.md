# Incoming call hint verification

Push supplies only a call UUID. Verify it through the original authenticated runtime's
read-only status API before offering an incoming ring; require the exact captured account
object, not matching user ID after login replacement. Require actual RINGING, actual own
callee and another caller. Accepted/terminal/outgoing calls are gone for this notification;
HTTP failure remains a typed failure, not acceptance or fabricated ringing. Server ring
deadline remains display metadata; do not compare it to phone wall clock.

The verifier owns no coroutine scope, microphone, foreground ticket, roster or acceptance
operation. Guard account/runtime/caller before and after suspension. Actual status observation
remains in the same call control, so later explicit visible-Activity acceptance uses earned
revision. Notification publication/expiry and FCM provisioning follow separately; this task
does not install Firebase or claim background delivery. Tests use actual HTTP through runtime
and root fixture, including held status across logout and same-user account replacement.
Fresh exact Android suite before commit; parent review, no extra agents.
