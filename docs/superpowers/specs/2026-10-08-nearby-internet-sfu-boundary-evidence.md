# Nearby: Internet media with decoded SFU answer observation

Measured 2026-10-08, following the [decoded-boundary control](2026-10-08-nearby-sfu-answer-boundary-evidence.md).
Six four-participant calls used real Internet media between Windows and the
disposable SFU on the production host. The earlier rare subscriber failure was
not reproduced. These positive controls do not establish its cause or fix,
Android audibility, Xiaomi compatibility, or tolerance of the rider's network.

## Admission and results

Both fixtures used the exact production SFU image
`sha256:bba3a6329d7b3ae09e3094e642136ba17ba1d33545f735a400b8f589bae682b6`,
an isolated owned Docker bridge, fresh rooms and synthetic participants, and
30-second leases renewed every four seconds. Registration and renewal required
actual remaining credit above 25 seconds. No production account or DB was used.

Four independent SDK processes joined each call. Every receiver had to observe
at least ten packets from each other participant, then further packet growth
after two seconds. Diagnostics were captured before SDK teardown; teardown
timeouts cannot be mistaken for an active-call negotiation failure.

| Fixture | Signaling | Selected media path | Three-call result |
|---|---|---|---|
| `c941er`, run `c941ew` | Encrypted SSH tunnel to loopback HTTP | Public candidate, UDP mux17883 or TCP17882 predicate | All eight selected ICE paths confirmed; all twelve receive directions grew in every call |
| `c941ey`, run `c941f2` | Same SSH tunnel | Dedicated UDP51000–51019; TCP fallback explicitly fails admission | All eight selected paths confirmed as UDP; all twelve receive directions grew in every call |

Selected-path evidence comes from the actual typed Pion candidate pair in the
SDK's `ICE connected` callback, separately for publisher and subscriber. Export
is restricted to four booleans: expected fixture IP, expected port, UDP, TCP.
Candidate addresses, foundations and credentials are not exported. Nil or
foreign candidates cannot manufacture confirmation. This verifies the route
used by the SDK, rather than assuming public media from a successful join.

The first SDK helper had four actual tests: initial RED three failures, final
four passed. The range helper had five final passing tests, including both
range endpoints and out-of-range rejection after an actual RED run.
SDK2.13.3/Pion4.2.3/Go1.24.4 remained pinned. Executable hashes:

- Mux helper `c941eu`: `56867b518d2fd1b472ae57b64cdcf394791ad84d12b640c5e740f91d546cb7df`.
- Range helper `c941ez`: `dcddab8d594a3358b6a518d0f275719539b4be764ad959af20bf41005ba9fc4b`.

Read-only inspection of the loaded production RTC configuration confirmed
dedicated UDP50000–50019, TCP7881 and TURN UDP3478. The second fixture therefore
removes the UDP-mux difference without taking production ports. Both fixtures
omit TURN. Fixture token grants and lease registration are synthetic, not the
backend's actual application session flow. Signaling still differs materially:
SSH bypasses the working TLS/nginx/Cloudflare ingress. The successful calls do
not exclude a problem in that ingress or intermittent negotiation timing.

## Decoded observation and cleanup

Each fixture observed39 service receipts,39 participant dispatches and39
transport-processing starts. Service SDP audio-section counts in each artifact
were: zero12, one6, two9, three12. No negotiation timeout/failure was observed.
Processing starts remain weaker evidence than successful description application;
the independent RTP growth supplies the media-positive evidence for these calls.

The disposable DEBUG observer used Docker log driver `none` and volatile attach
output. Its extensions retain only numeric audio-section counts and four
numeric/null timeout description counts. Missing descriptions remain unknown,
not zero. No raw SDP, identities, credentials, arbitrary errors or raw logs were
written. Five privacy-contract assertions passed before the live controls;
a narrow Luna/xhigh review checked export and owned-launch cleanup.

Both final artifacts recorded capture completion, attach exit0 and zero parser
and reader health counters. Independent audit `c941f4` confirmed both exact owned
containers and networks absent, all fixture ports free and no raw log files.
Semantic artifact hashes:

- `c941er`: `b6dd5c24fb1dccc76728b92c191ef4d60ad3743fd8135e494bb387ebe812f0b7`.
- `c941ey`: `3b8e96ddb4c1a0e9a984901561e1d0c7b3c4306d0f8668082514a76479ccef58`.

Post-control production audit confirmed unchanged app/SFU/DB IDs and images,
healthy app, production DEBUG disabled, exclusive loopback control origin,
readiness protocol4 and zero active rooms. Local SDK processes were absent and
the tunnel port was free. No production deployment/configuration/account/DB
change occurred.

Full Android gate `c941f3` exited0/43s with35 tasks executed:3407 tests in336
XML suites, zero failures/errors/skips. Every XML timestamp was independently
checked against this nonce log's creation time. Native sources and the signed
`c941e7` APK are unchanged.

## Next discriminating check

Host nginx was independently confirmed present, version1.22.1 with the TLS
module. The existing voice configuration uses HTTP1.1, Upgrade, one-hour read
timeout and buffering off. The next disposable control should use those ingress
semantics with an owned nginx process and an ephemeral certificate trusted only
by the probe. It must not reload system nginx, reuse production private keys,
enable production DEBUG, or simultaneously add TURN. Successful shadow TLS
controls would still not prove the rare production failure fixed or establish
Cloudflare equivalence.

## Executed TLS/nginx control

The next check was executed as `c941fa` against fresh owned fixture `c941f8`.
It retained the dedicated UDP range and omitted TURN. Signaling now traversed
real Internet TLS to a separate host nginx1.22.1 process on17885, then the owned
loopback SFU port17881. The probe's loopback reverse proxy used a certificate
pool containing only this fixture's public certificate, with ordinary hostname
verification and TLS1.2 minimum. No `InsecureSkipVerify`, environment proxy or
system trust change was used. Its executable hash was
`8bb56727372b03f5d1c42be0ef715a9745237d14c2969b0be1ce5d7a127e910e`.
Actual TLS-helper RED failed the unimplemented transport; two final tests passed:
owned-certificate handshake/empty-root rejection and invalid PEM rejection.
This is not a claim of a comprehensive TLS implementation test.

The owned nginx used daemon/master off, a private prefix/config/PID path,
HTTP1.1 Upgrade, one-hour read timeout and buffering off. Access logging was
disabled and error output discarded. Its temporary private key remained on
the server and was removed during cleanup; only the public PEM reached the
probe. System nginx was neither reloaded nor replaced. A requested narrow Luna
review failed before producing findings because the agent hit its usage limit;
no successful independent review is claimed. The primary inspected the added
trust/export/lifetime boundaries before execution.

All three four-peer calls joined successfully, confirmed all eight selected
public UDP paths and observed further RTP growth in all twelve directions.
Decoded counts were again39/39/39, with the same section-count distribution
and no negotiation timeout/failure. Actual driver exit0, attach exit0, capture
complete, parser/reader counters zero. Audit `c941fc` confirmed owned container
and network absent, nginx retired, private key absent and no raw log files.
The first audit `c941fb` used a TCP bind without `SO_REUSEADDR` and hit address
in use; the corrected bind allows retired TCP TIME_WAIT sockets while still
rejecting an active listener. Local SDK/proxy processes were absent and both
probe ports were independently free. Copied semantic JSON matched the server:
`962fc8b0e9c9dbb26d56ee1b3126e3bcfb983ddbcde0e8dc0defa6883052a0a5`.

Read-only production audit again confirmed the unchanged exact containers and
images, healthy app, DEBUG disabled, exclusive origin, protocol4 and active0.
This removes the SSH-only signaling limitation for these three positive calls,
but still differs from production Cloudflare, application grants, TURN and the
rider's Android/mobile network. The rare earlier failure remains unresolved.

Final post-TLS Android gate `c941fd` exited0/1m20s,35 tasks executed,
3407 tests in336 fresh XML suites, zero failures/errors/skips. XML freshness
was checked against its own nonce log creation time. Only evidence documents
were changed; the signed native artifact remains unchanged.
