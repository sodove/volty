# Nearby owned engine effect runner

Continue after63074b40. Bind controller effects to the guarded SDK driver before
full timers/DI integration. The caller supplies one serial owner dispatcher
(queued Android Main, StandardTestDispatcher in tests), current VoiceSessionState,
monotonic including-sleep clock, SDK factory and suspend event sink. Calls to the
nonblocking dispatch originate from that same owner. No coroutine must execute
native allocation inline in the HTTP/control owner's dispatch call. An earlier
draft suggested an initial yield; that is not sufficient for every immediate
dispatcher. Require an explicitly queued dispatcher, reject an inline dispatcher
at construction, and launch SDK work on that dispatcher. SDK connect/close waits
must not block handling stop.

1. One driver, mandatory current-state authorization. Start samples current
   matching state preferences when the queued work begins, rather than stale
   grant-time values. Credentials stay only in a short-lived start closure.
2. Track starts by exact generation/tag; replacement/stop cancels the appropriate
   start and closes only that generation. Driver still owns release-before-create.
   Same-tag duplicated StartEngine never reconnects/allocates again.
3. Coalesce queued preference work to latest desired value, preserving tag. A
   serial dispatcher is a required contract, not inferred from a generic pool.
   SDK preferences accepted during connect remain driver-owned latest intent.
4. Forward only tagged SDK events from the driver. A successful stop emits
   Stopped even when no native handle was ever allocated, so cancellation before
   allocation cannot leave local cleanup permanently pending. Failed close keeps
   its exact generation retryable and emits no false Stopped acknowledgement.
5. Start/control failure sends EngineFailed exact tag, never generation-only.
   Parent lifetime cancellation does not imply resources have been released;
   explicit runtime retirement must request stop and preserve failed cleanup.
6. Tests assert stop during blocked connect, preallocation cancellation, stale
   preferences/current initial intent, failed release retry, duplicate start,
   expired queued grant, replacement ownership and no native work inline.

This runner accepts engine effects only. Location/share effects remain owned by
their independent runtime; never silently treat an unsupported effect as success.
No automatic heartbeat/join-retry/expiry scheduler, durable process-death cleanup,
foreground service, auth/DI or whole Nearby enablement in this slice. Those need
their own lifetime integration and tests. Android routing/Xiaomi remain measured
device acceptance. Fresh targeted/full Android and narrow source review.
