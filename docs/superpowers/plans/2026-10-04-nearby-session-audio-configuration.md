# Session audio configuration

Continue the approved Nearby voice contour without UI cutover. The existing Android factory
is appropriate for the single-tag diagnostic controller, but its fixed preferences/diagnostics
must not be reused as a multi-session production factory.

Add an internal common VoiceSessionAudioFactory implementing VoiceSdkSessionFactory. Capture
the immutable current preferences once at each admitted allocation; create fresh diagnostics
with the exact generation and preferences before handing them to the native allocator.
Publish a read-only latest successful allocation (exact tag + diagnostics) after allocation
returns. This is diagnostic provenance, not proof the session is active or audio is audible.
Supplier/allocator failure must neither publish a new allocation nor overwrite the previous.
No arbitrary callback after allocation that could throw and abandon the returned resource.

First write tests for changing input without changing output, fresh per-session diagnostics
with old late writers, and allocator failure preserving previous allocation. Observe missing
API RED then deliberately static configuration runtime RED; implement and run fresh full suite.
Add Android allocation-provider binding using application Context and an explicit ready
preference supplier; input and output remain independent.
Keep the existing single-tag diagnostic factory unchanged. Configuration is sampled for the
next allocation; live route reconfiguration remains explicit future work.

No native allocation on Koin construction, no global diagnostics singleton, no permission
grant outside the existing authenticated engine gate, no foreground/UI/physical-route claim.
An earlier draft proposed directly binding AppPrefs.voiceMicrophoneSource. Its initial AUTO
is not evidence persistent storage has finished loading. The provider instead accepts an
explicit ready supplier; production start composition must wait for settings readiness.

Evidence: missing API RED 24s/33 executed tasks. Static-configuration runtime RED 3 tests/3 failures/0 errors, 31s/35 executed, exact XML: resampling read count, diagnostic identity reuse, failed allocator overwriting prior allocation. Final fresh full rerun/no caches 2874 tests, zero failures/errors/skips, 37s/35 executed. Parent manual review checked no constructor allocation, once-per-allocation capture, fresh exact-generation diagnostics, publication only after returned resource, and separate Android allocation provider. No native device claim or independent review.
