# Retained authenticated voice control

Bind one control transport to a verified SocialAuthenticatedAccount without binding
full voice cleanup to its cancelled Job. Positive join/status/heartbeat permissions
are fenced before and after account/local retirement. Tokens come from the original
authority; same-generation refresh updates the private retained cleanup token. No
global current-account supplier or replacement-login token may be used.

Retirement preserves cancel/disconnect only for this owner's recorded join intents
and validated session tags. A pure default intent-recording hook on the control port
is invoked by VoiceControlOwner before scheduling its join producer. This is necessary:
a queued join can be cancelled before its coroutine enters the transport, but the
owner still sends cancellation for that operation. The original intent must already
be recorded; no synthetic remote-stop ACK and no unrestricted cleanup capability.
Direct transport join also records its own intent. Exact operation/generation/channel/
replace binding is first-writer immutable. Reply-derived session identity is retained
for cleanup even when positive result publication is rejected after logout.

Two owned raw adapters keep positive lease ordinals separate from terminal cleanup.
Suspend creation validates the account/root and captures its original bearer before
allocating adapters or accepting intent. This also permits cleanup when a registered
join producer never enters transport. Cleanup never waits for auth refresh; its token
is only the original authority token observed at creation or a positive request.
The terminal adapter uses the original account's last observed bearer after retirement. Such
requests may receive 401, remain unconfirmed, and do not prove media revocation.
Close is explicit only after the enclosing voice drain is done; admission retirement
alone never closes the cleanup port or cancels native/full voice cleanup. No implicit
close at auth cancellation. Private retained tokens/maps are cleared on explicit close.

Tests first: actual login/logout/relogin with observed headers, held actual HTTP join,
cleanup before late reply, unknown/wrong-generation cleanup denied, active refresh
then retired cleanup401, and owner cancellation before producer starts. Finite gates
and no delayed runTest loop. Missing API then runtime RED, fresh full suite exact count,
parent manual narrow review after exhausted Luna quota. Native runtime/drain factory,
foreground, process-death persistence, audio and physical stop evidence follow separately.
