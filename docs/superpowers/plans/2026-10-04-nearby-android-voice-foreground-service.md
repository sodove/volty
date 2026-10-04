# Android voice foreground service

Connect the tested exact-runtime gate to a nonexported START_NOT_STICKY microphone service.
Controller starts only from a resumed visible Activity with current RECORD_AUDIO permission;
caller requests permissions separately. Intents carry only registered opaque ticket IDs.
Service acknowledges readiness only after successful startForeground. No process-death rejoin.

Gate loss submits original runtime disconnect under its existing cleanup root, entering
NonCancellable before queued suspension. Cancelled service/activity children cannot abandon
submission; expose submission failure without converting it to resource cleanup ACK.
Test actual auth/HTTP/native cleanup with a cancelled caller before implementation.

Notification opens MainActivity and offers ticket-specific disconnect. Stale intents/service
callbacks cannot stop a newer runtime. Live permission recheck revokes before cleanup. Watch ALL
bound runtimes: stop foreground only with no selected demand, all unwanted, no pending/failed
local cleanup, zero actual native producers, and no failed submission. Pure release predicate
gets incoherent multi-owner tests first. Local service release never proves remote/SFU stop.
Initial idle while preparation is pending preserves foreground. Caller/notification explicitly
releases its ticket on disconnect or failed/abandoned preparation. Immutable runtime retirement
also revokes; it cannot be reversed into a new intent on this runtime.

Register manifest/DI and paired English/Russian strings. Native Context/Lifecycle/service/
notification/permissions compile only; no JVM simulation or device proof. Missing/signature
runtime RED for common policy/ownership, then fresh full suite/exact XML/manual review.
No product UI cutover or deployment; device matrix remains required.

Retraction: an earlier draft proposed PackageManager permission-change listeners. Primary AOSP
requires privileged OBSERVE_GRANT_REVOKE_PERMISSIONS, unavailable to this ordinary app.
Use a live grant check on visible launch/admission and one-second service-owned pulses instead;
no privileged permission is added. Source checked:
https://android.googlesource.com/platform/frameworks/base/+/6c9c6cb626f16c491131e73fd6957d261edf2533/core/java/android/permission/PermissionManager.java

Retraction: an earlier draft tried to infer completion from a wanted true-to-false observation.
StateFlow can conflate that transition; an old terminal state can also race a new preparation.
Use the explicit ticket demand instead. Conversation composition must release the ticket on
failed/abandoned joins; current selection cannot be inferred from voice-state timing.
