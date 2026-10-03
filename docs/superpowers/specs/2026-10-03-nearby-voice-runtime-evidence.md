# Nearby composed voice runtime evidence

VoiceRuntime composes the actual mailbox owner, SDK effect runner and pulse
scheduler on one required queued serial dispatcher. A mutex orders public
commands across bounded-mailbox suspension. Engine effects and three explicit
position-demand effects have separate required sinks. The runtime cannot remove
membership: no community mutation port is supplied.

Joining never enables position. Explicit sharing checks current earned lease
against the clock even before the next pulse. Hiding position keeps voice.
Disconnect releases local audio and requests server disconnect independently.
beginRetirement permanently fences new joins/consent while retaining scheduling
for original native generations and remote session identities. There is no
dispose shortcut that infers release from coroutine cancellation.

Five finite issued-path cases produced missing-API compile RED67939 before
implementation, targeted5 PASS5297 afterwards. Added rejection of inline
dispatchers before ownership starts and sharing at inclusive expiry without a
pulse; final targeted7 PASS3595, exact zero failures/errors/skips.

Fresh full Android2680 PASS95730: exact2680, zero failures/errors/skips, all35
tasks executed with rerun-tasks and build/configuration caches disabled. Narrow
Luna source review passed admission ordering, effect routing, explicit consent
and independent retry lifetime. No reviewer builds or wider device proof.
Backend143 and real PostgreSQL131 evidence remain unchanged.

The injected port must remain bound to one immutable authenticated principal.
This requirement is not yet enforced by production DI. Android foreground and
including-sleep pulse ownership, durable process-death cleanup, final retirement
drain and production sharing admission remain pending. Parent cancellation can
still terminate this composition; it is not an acknowledgement of resource
release. There is no production screen cutover or Xiaomi/audio routing proof.
