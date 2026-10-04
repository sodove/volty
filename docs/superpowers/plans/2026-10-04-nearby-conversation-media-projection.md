# Earned media projection for the root conversation

Expose the actual VoiceSessionState alongside selected target/operation in the root state.
PREPARING has no media snapshot. JOIN_SUBMITTED means intent accepted, never audible voice.
Publish only matching operation state while the exact foreground ticket/original account remain
current. Transport connected, capture started, microphone published, subscribed peers and render
started remain separate evidence fields; no successful SDK method return fabricates callbacks.

Replacing/stopping/logout/foreground loss clears this positive snapshot immediately. Late native
callbacks from an older allocation cannot populate the pending/new target. This is an active
intent projection, not a resource drain or authoritative roster/membership count. The foreground
service still owns independent cleanup evidence and retains notification while required.

Tests before source: actual root/account/Mock/SDK success without callbacks has no media evidence;
controlled tagged callbacks progressively earn fields; old callback during new preparation and
after replacement cannot cross targets; logout clears earned snapshot without retiring login on
normal stop. Missing API/runtime RED then exact fresh full suite. Native device/UI not simulated.
