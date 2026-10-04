# Operation-scoped conversation controls

Expose mute/deafen through the root conversation owner using the operation ID rendered by the
UI. Reject controls for a previous target, unfinished preparation, withdrawn foreground demand
or retired account. Submission is not microphone/audibility proof.

Caller checks alone are insufficient: the bounded owner mailbox can process a command after
a newer join. Carry the expected operation in the event itself and check it in the reducer.
Legacy single-session diagnostic APIs retain their existing unscoped form; production root
conversation commands always supply an operation. Wanted=false with retained session ID is
deliberately tested; no mutation/effect may escape for that obsolete control.

Tests before implementation: stale operation and unwanted same operation issue no effects;
current controls preserve deafen/mute behavior; actual root/auth/MockHTTP/SDK verifies native
commands and denies pending/stale controls across a channel switch. Missing API/runtime RED,
fresh full suite with exact XML; parent manual review. No Compose/native-device proof or UI
cutover. No sharing consent, membership or live audio-route changes added here.
