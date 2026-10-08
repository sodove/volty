# Nearby: decoded SFU answer boundaries

Measured 2026-10-08. This adds an observation mechanism for the rare subscriber
failure described in [live transport evidence](2026-10-07-nearby-live-transport.md).
It does not claim that failure is fixed, reproduced here, or caused by poor
connectivity. [Degraded-network checks](2026-10-08-nearby-degraded-network-evidence.md)
remain separate evidence; Android audibility and Xiaomi are still unverified.

## Source-confirmed path

Read the actual generated production snapshot
`.superpowers/sdd/2026-10-04-nearby-authentication-ownership/c94194-production`,
not an assumed current upstream version. The deployed image remains
`sha256:bba3a6329d7b3ae09e3094e642136ba17ba1d33545f735a400b8f589bae682b6`.

| Boundary | Source and observation |
|---|---|
| WebSocket decode | `pkg/service/wsprotocol.go`, `ReadRequest`: binary protobuf or text JSON is decoded; an error ends the service read loop |
| Service receipt | `pkg/service/rtcservice.go`: existing DEBUG `received answer` precedes `RequestSink.WriteMessage` |
| Request routing | `pkg/routing/messagechannel.go`: a full/closed channel rejects a write; the service logs a fixed message and terminates that connection. A successful write is not participant processing |
| Participant dispatch | `pkg/service/roommanager.go`, `rtcSessionWorker` consumes the request; `pkg/rtc/signalling/signalhandler.go` calls `Participant.HandleAnswer`; `pkg/rtc/participant.go` emits a second DEBUG `received answer` |
| Transport queue | `pkg/rtc/transportmanager.go` always sends this answer to the subscriber PC; `PCTransport.HandleRemoteDescription` posts a queued event in ordinary dual-PC mode |
| Processing begins | `pkg/rtc/transport.go`, `handleRemoteAnswerReceived`: DEBUG `processing answer` runs before `SetRemoteDescription`. Zero answer ID is accepted; nonzero mismatch logs a warning rather than filtering the answer |
| Application | `handleRemoteAnswerReceived` clears the signaling-state timer before `setRemoteDescription`. Successful processing or a retry negotiation follows. The DEBUG receipt alone does not prove application, binding, RTP delivery or audibility |

A later timeout can belong to a subsequent offer; neither timer ordering nor
equal aggregate counts prove that a particular pending offer was answered.
The pinned Go SDK sends subscriber answers with ID zero, so do not invent an
ID-matching rejection fix. The passive wiretrace still observes loopback bytes,
which is a weaker boundary than these decoded events.

## Positive control and privacy

New `tools/nearby-sfu-gate/signal-boundary.py` recognizes only exact source
caller/message pairs. It exports constrained timestamps, per-run participant
ordinals, fixed stages, and integer answer IDs only when explicitly present.
Missing IDs stay absent. Raw participant IDs, room names, SDP, ICE credentials,
JWTs, arbitrary fields and error messages are never exported. Input records are
bounded to 1 MiB, peers to64, events to2048; malformed/oversized/limit failures
invalidate capture admission. This supports positive observation, not inference
that an unseen answer was never delivered.

`run-signal-boundary-probe.py` uses a fresh owned `/tmp` workspace and the exact
production image in an isolated loopback-only SFU. Its DEBUG setting applies only
to its private fixture. Docker log driver is `none`; attach output is parsed in
volatile memory, and synthetic probe stdout/stderr go to `/dev/null`. No raw log
file or pcap is written. The parser emits only the semantic `boundary.json`.
The reader marks premature EOF and must remain live until probe completion;
cleanup checks container/image/task identity and removes only that container.
Regular-file, symlink, hard-link and resolved-parent checks guard fixture writes.
The persisted result explicitly includes `accepted` and the actual attach exit
code. A nonzero attach exit, capture error, failed probe or incomplete positive
peer observations cannot produce an accepted artifact.

Actual RED: initial eight tests/seven failures (`c941e8`); missing-ID review
regression ten tests/one failure (`c941ec`); reader/workspace six tests/five
failures (`c941ed`); hard-link regression17 tests/one failure (`c941eh`);
persisted-admission regression19 tests/five subcase failures (`c941el`). Final
19 tests passed on Windows (`c941em`) and Linux (`c941en`), no skips. A narrow
Luna/xhigh review checked the parser/privacy/observer lifetime; it did not review
the whole SFU or establish Android behavior.

First remote attempt `c941ea` failed its fresh-workspace precondition because
Python import created `__pycache__`; no container was created, independently
checked absent. Bytecode writes are now disabled before import. Fresh workspace
`/tmp/volty-nearby-boundary-c941eb` then ran control `c941eg` with transferred
artifact hashes verified. Linux synthetic probe hash was
`f7103ada862dfdbba47e6817461c5698b0e83599143682a1be5b6a3ac0a859bc`.

Actual runner exit0, probe exit0. Service receipts20, participant dispatches20,
transport-processing starts20. All six participant ordinals had matching counts:
6/5/3/2/2/2. Parser and reader health counters were all zero; attach completed
normally, exact owned container was independently confirmed absent, and no raw
log files existed. The final hard-link guard was added after this control;
the actual four control inputs were independently checked to have link count1,
and the final guard passed both platform test suites. No repeated media run is
claimed for that additional input guard.

Final runner control `c941eo` used a fresh workspace and verified transferred
artifacts after the explicit persisted-admission repair. Actual runner exit0,
probe exit0, attach exit0, persisted `accepted:true`;21 service receipts,
21 participant dispatches and21 processing starts. Per-peer counts matched
6/6/3/2/2/2, with every parser/reader health counter zero. Both owned containers
were independently confirmed absent and no raw log files existed. The copied
semantic JSON hash matched its server source:
`3b806d089e8f19567d8e28e9dcdf296ac740be91ec9c5281192cb752676da3c3`.

Final full Android regression gate `c941ep` exited0/43s,35 tasks executed,
3407 tests in336 XML suites, zero failures/errors/skips. Every suite XML timestamp
was checked against this fresh nonce log creation time. Earlier `c941ek` was an
intermediate gate before the last diagnostic reporting change. Kotlin/native
sources, dependencies and signed APK contents are unchanged by this diagnostic.

Post-control production audit confirmed the same app/SFU/DB container IDs and
images, healthy app, exclusive local control/signaling origin, one SFU process,
debug disabled, zero active rooms and readiness protocol4. No production
configuration, image, firewall, routing, DB or account mutation occurred.

Next diagnostic step is a bounded reproduction with this decoded-boundary
observation on a disposable SFU. Do not enable production DEBUG or interpret
this positive control as a fix for the earlier Internet subscriber failure.
