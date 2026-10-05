# Root location capture bound to original sharing consent

Create a unique per-sharing-generation owner capturing exact account/runtime/voice tag/control,
mode/TTL, borrowed actual RideLocationRepository and its matching process demand coordinator.
Its own root producer requires actual location foreground acknowledgement before consent and
native demand. UI owns only the begin waiter. Capture resources record native ownership before
issue and remain in flight through actual mutator completion; hide clears data immediately,
cancels positives, submits original revoke first and releases only its demand ticket. Native
failure remains truthful/retryable; borrowed voice/auth/control/navigation remain untouched.

Observe real repository state. Emit only fresh Available fixes captured after this generation's
native-demand issuance in both epoch/elapsed clocks; reject absent elapsed provenance, stale,
future, overflow and duplicate fixes. Preserve earned zero coordinates. LOCATION no telemetry,
RIDE/FULL use existing earned TelemetryShareMapper through mandatory supplied producer. Sequence
does not wrap. Latest packet StateFlow is cleared on hide, account/session/foreground/consent
loss and root shutdown. Actual packet publisher follows; do not claim automatic upload yet.

Actual voice HTTP fixture + recording location native facade tests first: no consent/native
before FGS ack; root begin survives cancelled UI waiter; earned fresh fix only; owned hide and
original revoke preserve navigation/voice; held partial native activation/release truth; native
failure retry. Fresh Android. No public UI/DI capture activation/device/audibility claims.
