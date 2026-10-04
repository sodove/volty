# Voice account coordinator evidence

Implemented one serial account selector with shared pending acquisition and a mandatory
native admission gate. Selection revokes older positive permission before suspension.
Original account/root identity stays fixed; waiter cancellation cannot abandon construction.
Retirement submits cleanup without joining held producers or cancelling authentication.

Five tests exercise actual authentication, MockEngine requests/headers, and issued fake SDK
work. Successful close with a still-running cancelled connect blocks replacement; failed
close blocks until actual retry succeeds; duplicate acquisition shares one runtime;
cancelled refresh waiter leaves root-owned construction alive; reverse builder scheduling
cannot retire a newer selected runtime.

Observed missing-API compile RED, then signature-only runtime RED (4 tests, 4 failures).
An initial full run exposed a fixture error: Channel.receiveAsFlow distributes ticks among
owners. Replaced it with finite broadcast pulses; production requires cold or broadcast ticks.
Manual review then found predecessor sampling happened too late. The fifth test failed
after the newer runtime had been delivered because the older builder retired it (5 tests,
1 failure). Capturing predecessors at selection fixed this; the source records the retraction.

Final full command: `:composeApp:testDebugUnitTest --rerun-tasks --no-build-cache
--no-configuration-cache`. BUILD SUCCESSFUL in 37s; 35 tasks executed. Exact XML assertion:
2868 tests, zero failures, errors or skips. Parent manual review only; no independent reviewer
or mutation-sweep claim. Review checked selection-before-suspension, lazy builder assignment,
root identity, failure isolation, captured predecessor ordering, retirement snapshot and
actual local producer completion gates.

Retired entries remain retained until a separate HTTP/native drain can safely dispose them.
Remote stop evidence is separate from local native completion. No Android DI/UI cutover,
physical LiveKit internal-thread proof, foreground-service proof or Xiaomi/audio-route claim.
