# Original capture packet publisher

Own a queued root producer consuming the exact capture's latest packet StateFlow. One HTTP
publication at a time; samples arriving during a request conflate to the latest original sample,
without rewriting sequence/capture time. No UI waiter owns this loop. Require exact account and
unique publisher admission before allocating. Capture admission rechecks actual consent/session,
foreground and current fresh sample immediately before HTTP and session again after await.

Capture retirement/account loss/root shutdown fences acknowledged state immediately and cancels
positive work; count the actual HTTP producer until its finally even if cancellation is ignored.
Publisher close releases only its own producer and never hides borrowed capture/voice/navigation.
No automatic retry/rebase or offline revoke success claim. Transient typed HTTP failure remains
visible; terminal denial hides the original capture. Test actual held packet HTTP, conflation,
original sequence, stale sample, cancelled UI waiter, hide with held response and borrowed owners.
Fresh full Android before commit; no native/product activation claims.

Review correction: an expired sample acknowledgement is not expired consent. Retain typed
EXPIRED failure and accept the next newly captured sample under healthy original consent;
only actual access/auth/retirement denial hides capture. Keep terminal failure reason visible.
