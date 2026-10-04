# Earned persisted audio preferences

AppPrefs.voiceMicrophoneSource starts with AUTO before DataStore has emitted. Native
preparation must not treat this UI default as the rider's saved microphone choice.
Add suspend readVoiceAudioPreferences reading one actual DataStore snapshot. Map the
existing microphone enum independently to input; persist output under a new separate key.
Unknown/missing values default AUTO only after the actual snapshot has been read; storage
failure/cancellation propagates instead of fabricating defaults. Add a suspend output setter.
Existing UI StateFlows and microphone setter remain unchanged.

Tests before implementation: held initial read stays pending then returns saved PHONE/HEADSET;
actual writes and fresh snapshots preserve output when input changes; storage failure propagates.
Use finite cold fake DataStore snapshots and gates, not Default-dispatcher StateFlow timing.
Missing API RED, default-only signature runtime RED, production and fresh exact full suite.
Preparation/native integration follows separately; this adds no UI or live routing claim.

Evidence: missing-API RED 24s/33 executed tasks. Default-only runtime RED 3 tests, 3 failures, zero errors in 31s/35 executed, exact XML asserted (early completion, lost PHONE/HEADSET choice, swallowed storage failure). Final fresh rerun/no caches full suite: 2877 tests, zero failures/errors/skips, BUILD SUCCESSFUL in 39s, all 35 tasks executed. Parent manual review checked one actual snapshot, unchanged legacy input key, separate output key/setter, no failure fallback or shared auth/lifetime side effect. Native preparation/UI/device proof remains separate; no independent review or mutation claim.
