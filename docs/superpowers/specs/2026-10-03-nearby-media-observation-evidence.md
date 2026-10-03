# Nearby live SFU observation foundation

Protocol3 adds a room-admin authenticated, node-incarnation fenced current
participant observation. Registration alone returns an absent participant. Lookup
uses the gate's current captured participant SID and the live RoomManager room,
under the same cancellable entry lock as admission/revoke. It never renews a lease.
Terminal/expired/unknown admission returns rejection, not an absent-success reply.
The bounded native Kotlin HTTP adapter preserves these categories and rejects
inconsistent SID/state, unknown states and mismatching response incarnations.
Production session and roster dependencies remain disabled.

An earlier proposal used RoomService.GetParticipant between readiness checks.
Withdrawn after reading pinned1.13.5 roomservice.go: it defaults to LoadParticipant
from a persisted store unless enable_psrpc_for_get_list_participants is enabled.
A cache read cannot establish live transport provenance. The new extension reads
the gate-owned live room participant and verifies its SID matches the captured
handle; no assumption about that optional configuration is needed.

Pinned participant.go onPrimaryTransportInitialConnected sets ACTIVE. This means
an SFU primary transport established, not microphone capture, Android playback,
instantaneous reachability during disconnect grace, or device audibility. JOINED
and JOINING remain distinct and are not promoted to ACTIVE by the adapter.

## Fresh verification

- Go missing-API compile RED before implementation; four new observation tests
  cover admission-only absence/no renewal, new SID/terminal rejection,
  deliberately incoherent handle provenance, and cancellation behind issued join.
- Kotlin missing-API compile RED; protocol3 readiness tests produced runtime RED
  (three failures) against the former protocol2 client. Fresh backend unit94 PASS.
- Owned pinned SFU and probe binaries compiled in a new nonce directory.
  Full real synthetic RTP matrix PASS nonce64d6599f-5e50-4cc1-98f2-9ffc318f9e89,
  run c245e9c8-bf6e-428c-80b1-890400e0d732. Registration absent, live ACTIVE SID
  matches connected SDK participant, same logical session reconnect produces a
  different SID, participant/wrong-room credentials and stale epoch denied.
  Revocation observation rejected; received/forwarded RTP delta0 after ACK;
  surviving peers retain duplex, revoked original/refreshed tokens denied.
- Actual Kotlin client SFU2 PASS run9d237d70-0fa5-48e6-aeca-1ec9c99b9f62.
  Registered-only observation absent, terminal/stale epoch rejected.
- Supervised actual restart PASS run7fa25a1c-9eca-41b3-a744-2d78097d9dce:
  old epochf6b7ac0b56b2e71a9c04bef3276b1140, new
  3c320c8e0adb67d4d9e21e772f6c5ea7, old media delta0, captured credentials
  rejected, fresh immutable sessions retain duplex. Owned processes stopped.
- Fresh Android2573 PASS. These checks do not measure Xiaomi audio/routing.

Evidence root, git-ignored and preserved:
`.superpowers/sdd/2026-10-03-nearby-backend-session-lifecycle/sfu-observation-a9623772-b90b-439e-a0c4-176629ebc2ad`.
SFU SHA2564C745D9103EB620B6DC3B321D14FDBB53BBF457E8468B3A46BAB447906E59382;
probe SHA2568607EAAA69D95830B530BE2FD8BB8CFDC38AE7D7EB02E9A9784FF339E9B667BA.
No production process, credentials or database participated.

This is Task3's observation foundation only. Durable media state/webhook dedup,
lease renewal gating, authorized communities, client monotonic remaining-grant
integration and the product UI still require implementation. A passing transport
probe is not completion of Nearby or evidence of audible phone calls.
