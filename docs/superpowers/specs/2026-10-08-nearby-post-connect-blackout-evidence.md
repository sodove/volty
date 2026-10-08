# Nearby: post-connect interruption control

Measured 2026-10-08, extending the [public TLS/nginx control](2026-10-08-nearby-internet-sfu-boundary-evidence.md).
One admitted four-peer call stopped receiving media during an eight-second
disposable SFU pause and resumed media in all twelve directions after unpause.
This was restoration of traffic on the retained connection, not proof of SDK
reconnect, Android audibility or a fix for the rare subscriber failure.

## Owned interruption

The fixture retained the exact production SFU image, dedicated public UDP
51000–51019 and owned TLS/nginx ingress. Synthetic participant/control tokens,
30-second leases and no TURN remained. Production services were untouched.

The new bounded helper verifies exact container ID, image and task label before
pausing. It rejects an already paused fixture, verifies actual paused state,
waits eight seconds, then unpauses in `finally` and verifies resumed state.
Even a partially successful pause command or interrupted wait must unpause
the owned fixture; changed identity prevents mutation. Four tests had actual
RED (two failures and one error), then all four passed. Inputs were transferred
into fresh0700 workspaces with byte hashes checked before execution.

Four independent SDKs first had to receive at least ten packets from every
other peer. During the pause, matrices at two and five seconds had to show no
additional packets in every direction. After unpause, every direction had to
gain more than ten new packets, then show another independent two-second
growth interval. A room join or connected label alone cannot pass this test.

## Rejected runs

Run `c941fi` reached the interruption but failed its control-lease admission.
Its inherited five-second request timeout was shorter than the deliberate
eight-second SFU pause. The media-recovery result was not emitted, so this run
is not admitted as a successful recovery or a product regression. Fixture
cleanup completed, capture was complete, and decoded counts were13/13/13.

The next harness allowed twelve seconds for disposable control requests, while
retaining actual30-second leases and the requirement for accepted renewal with
more than25 seconds remaining. It does not change the backend's six-second
timeout or prove its behavior during a server outage. Run `c941fl` failed an
SDK join before any decoded answer or blackout. Raw SDK error text was withheld;
its cause remains unclassified. It is not the previously observed missing
subscriber direction and was not counted as a successful call. Cleanup and
capture completed with zero decoded events.

For the following run, startup also required an actual successful HTTP request
through the public TLS proxy before SDK joins. SDK failures now export only a
fixed category, never the arbitrary error string. This improves observation;
it does not establish a cause for the preceding join failure.

## Admitted run and limits

Run `c941fo`, fixture `c941fm`, earned public TLS HTTP admission and all four
joins. Verified interruption duration was8107 ms. All twelve directions stalled
during interruption, all twelve gained new packets after it, and all twelve
continued growing in the subsequent interval. Selected pre-interruption ICE
paths were all public UDP. The retained route flags are not a new post-reconnect
candidate observation. Actual driver exit0; lease renewals remained admitted.

All four SDK states were `connected`. Each had two `ICE connected` events and
zero `ICE disconnected`, `ICE failed`, resume or restart events. Thus this test
does **not** exercise the SDK reconnect path. Pinned Go SDK2.13.3 `transport.go`
sets disconnected timeout10 seconds, failed timeout5 seconds and keepalive
interval2 seconds. An eight-second interruption is below the disconnected
threshold; a longer bounded interruption is the next discriminating check.
These are Go-probe timings, not assumed Android SDK timings.

Decoded events were13 service receipts,13 participant dispatches and13
processing starts; no negotiation timeout/failure was observed. Attach exit0,
capture complete and every reader/parser health counter zero. Independent audit
`c941fp` confirmed all three owned containers/networks absent, nginx retired,
private keys absent, no raw logs and all fixture ports free. Semantic hashes:

- Rejected `c941fg`: `858cbd4b16d768e76965c898e7eca74d6258ab83554f615b398820cd0d09abb2`.
- Rejected `c941fk`: `3433c72558937bda63fb55a277c8b43bba424b5d989ffb0ba49f2744aa410438`.
- Admitted `c941fm`: `4bb56954472a5851d6a1f05eb752e81c7adf7e479bfdd48e92f36a9b86becca4`.

Production audit confirmed unchanged exact app/SFU/DB IDs and images, healthy
app, DEBUG disabled, exclusive origin, active rooms0 and readiness protocol4.
No production/native/dependency changes or new APK were made. Poor mobile
connectivity remains a plausible trigger; no Android or physical claim follows
from a successful synthetic Opus packet test.

Full Android gate `c941fq` exited0/44s,35 tasks executed,3407 tests in336
fresh XML suites and zero failures/errors/skips. Every XML was checked against
this nonce log creation time. The copied successful semantic artifact matched
its server hash; local SDK/proxy processes were absent and both tunnel/proxy
ports were free. This control was reviewed by the primary; no successful
independent agent review is claimed.
