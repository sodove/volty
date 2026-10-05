# Android incoming notification port

Render the tested owner's actual bounded notification command using NotificationCompat on
its own high-importance incoming channel. Check app/POST_NOTIFICATIONS/channel permission;
set system timeout to the remaining original monotonic credit. Native notify submission is
not evidence that the rider saw/heard it. No full-screen/background microphone startup.

Require an explicit PendingIntent factory: acceptance must open a visible Activity directly,
decline must resolve the original opaque ticket through a nonexported action handler. No
default/no-op action binding. Per-ticket native tags and exact identity registry ensure old
cancellation cannot remove another owner's notification; retain failed native cancellation
for truthful cleanup failure. Add both Android values/values-ru strings, no user UUID/secrets.

No Koin activation until the action bridge exists. Native notification/permission/OEM behavior
requires a device; this repository has no instrumented/Robolectric UI tests. Compile via fresh
full Android suite with exact count before commit; do not add a constructor/builder-mirror test
and call it device evidence. Parent manual review, no extra agents/FCM provisioning/deployment.
