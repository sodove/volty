# Authentication ownership — login/logout stage

This is the first stage of authentication ownership, not a trusted Nearby auth bridge.
Login/register now create an authentication generation, clear old memory state, and
pair the profile with the exact token used to request it. Delayed old success cannot
persist credentials or publish profile after logout/replacement login. Finite state
publication mutex is separate from login credential persistence/clear mutex.
Logout retires local ownership before credential clear and remote logout waits.
Conditional failure clear cannot erase another login's newer generation.

Three controlled tests use explicit constructor-restore read barriers and held profile
responses. Both assert the late producer created its result, Unauthorized outcome,
session ownership and token-store values after logout/newer login. The third holds an
already-issued credential write and leaves newer login pending until stale persisted
token cleanup is asserted. It proves actual write commit and one clear, before newer
login cancellation can perform cleanup for the old writer. Restore read takes
its snapshot before completing the barrier. Finally releases profile gates.

Actual initial RED2tests2fail28s35executed; refined marker/snapshot fixtures then actual
RED2tests2fail29s35executed, exact XML counts. Fresh full2818 exact0failures/errors/skips,
32s35executed rerun/no build/config caches. Review found that a generation changing
during the write denied memory publication but left the persisted token. Actual new
regression RED1test1fail31s35executed with exact XML count. Retraction: memory guard
alone was insufficient; rejected publication now clears its token before releasing
the credential lock. Newer login writers remain serialized behind that lock.
Fresh reviewed full2819 exact0failures/errors/skips43s35executed rerun/no build/config
caches. Narrow Luna review PASS on the stale write fix and its actual driver.

Retraction/remaining scope: restoreSession, refreshAccessToken, and other profile/
sharing publication paths still use legacy ownership. Credential ordering in this
stage covers login and clear, not yet all writers. Do not wire new Nearby to global
credentials or claim complete authentication isolation until those paths are gated.
No new account Job/trusted factory integration, legacy group migration, native
secure-store/power-loss proof, or device audio proof in this stage.
