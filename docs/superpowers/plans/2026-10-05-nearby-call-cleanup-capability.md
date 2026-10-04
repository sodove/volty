# Original direct-call cleanup across account retirement

Prerequisite: authenticated call HTTP client and account publication facade. Add an internal
root-owned control with two HTTP children: live admission and captured original-account cleanup.
Admission retirement is irreversible and distinct from closing children after owned work drains.
Never consult a replacement account, refresh a cancelled account or permit microphone work through
cleanup. Borrowed authentication Job and parent HttpClient remain outside this owner's disposal.

Register immutable outgoing call/callee/key/operation before scheduling a producer that may never
start. Reject any rebind of an existing call or operation. Original cancel-outgoing survives
admission/account retirement and can precede server creation. Server handles the private tombstone.
Only explicit original registered incoming acceptance may earn stop/decline capability; discovery
and status alone cannot launch media. Keep the operation/generation binding through accept/resume.

Account cancellation denies every positive call request, including publication after an actually
returned late response. Original negative requests may use only captured same-account credentials
and remembered immutable intents. Unknown call/operation and mismatched outgoing payload cause
zero HTTP. Closing clears token and metadata atomically; late bearer writers cannot resurrect it.

Terminal call snapshot proves the parent terminal state only. It does not prove both SFU cleanup
ACKs, local SDK disposal, capture stopped or HTTP drain. Preserve that distinction when adapting
the call to VoiceRuntime; obtain original participant journal cleanup evidence through original
operation/session routes. Call UUID must never enter generic channel join.

Before implementation test: original cancellation before unscheduled creation; cancelled account
still allows only remembered original negative intent; mismatched/unknown intent zero HTTP; actual
issued admission response after retirement cannot publish credentials; close cannot revive from
late token supplier; borrowed parent remains usable. Finite gates and issued-request assertions.
Parent manual review and fresh full Android suite before commit. No push/native/product activation.
