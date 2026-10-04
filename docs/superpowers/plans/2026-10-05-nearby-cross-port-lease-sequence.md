# One lease request sequence across call admission and session maintenance

Integration prerequisite before routing direct calls into VoiceRuntime. DirectCallHttpPort
and VoiceSessionHttpPort currently allocate independent request ordinals. Initial call grant
can carry ordinal0, then a fresh original-session heartbeat also carries ordinal0 and is
ignored by VoiceSessionReducer as a duplicate. Same clock tick cannot distinguish requests.

Introduce an explicit shareable VoiceLeaseRequestSequence. Preserve mutex serialization,
nonnegative monotonic request-start time and overflow protection. Ports sample receipt on
their injected same monotonic clock. Default individual HTTP adapters retain private sequences;
the composite owner supplies one sequence to call and session children, including cleanup.
Account control factories accept that shared sequence; no current account/product activation.

Before source test actual HTTP call credentials then generic session heartbeat with the same
sequence: heartbeat ordinal must advance and actual reducer must extend the earned deadline.
No inferred identity/credit from clock or body. Also exercise overlapping finite requests at
equal clock ticks and existing per-port ordering behavior. Missing-API compileRED is separate
from signature-ignore runtimeRED. Full fresh Android tests and parent manual review before commit.
