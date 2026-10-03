# Nearby conditional transport renewal foundation

Protocol4 adds renew-connected. The expected current participant SID and ACTIVE
state are checked within the SAME gate entry lock that extends its deadline.
The lookup, admission, old-SID cleanup, revocation and renewal serialize there;
an observation followed by a separate unconstrained renewal cannot substitute
for this operation. Registered-only/JOINING/JOINED/DISCONNECTED/old-SID/terminal/
expired transport cannot receive another grant. A deadline recheck after the
local observation prevents an expired entry from being renewed by a late lookup.
Failure leaves its initial deadline unchanged. Unsupported capability fails
closed in the strict protocol4 HTTP adapter; there is no generic-renew fallback.

The generic admin prototype renewal remains for existing gate/control experiments.
SessionService still uses that foundation method and production stays disabled;
the next actor-locked media integration must switch to connected renewal. ACTIVE
remains evidence that primary SFU transport established, not device audibility.

## Fresh evidence

- Three new Go tests missing-API compile RED, then total26 pass events (including
  existing subcases): registered/unconfirmed admission window, old/current SID,
  expired state and clock crossing expiry inside the local lookup.
- New Kotlin adapter test missing-API RED; protocol4 readiness runtime RED with
  three failures against the former protocol3 client; fresh backend108 PASS.
- Owned pinned SFU/probe compiled in a fresh nonce directory. Real synthetic RTP
  full matrix PASS run7a3c51e5-9393-4625-8b14-e90112812799,
  nonce6474d425-04e3-424a-a53e-c52e9d2dd495. Registered-only and replaced SID
  renewal denied; exact current ACTIVE SID accepted with bounded remaining lease;
  old incarnation rejected. Media revocation ACK200ms, post-ACK RTP delta0,
  surviving peers duplex. Same-session reconnect was issued and got a new SID.
- Actual Kotlin client2 PASS rundbd45282-c93b-4b37-ae40-d7c1e20f1c1d:
  registered-only connected renewal and old epoch rejected.
- Actual supervised restart PASS run6609de1d-0d11-4a3b-a1d9-dbe49b505d27:
  old epochde66511ada0b1871d0b38300af1a8b08, new
  44b5f4b9576267efc7892c00565a02a1, old RTPdelta0, original/refreshed tokens
  denied; new immutable sessions duplex. All owned fixtures stopped.
- Fresh Android2573 PASS. No Android/Xiaomi audibility or routing measurement.

Evidence root:
`.superpowers/sdd/2026-10-03-nearby-backend-session-lifecycle/sfu-connected-renew-a293fd98-9161-48b9-9d6b-3899629c362f`.
SFU SHA256CC5AEC8C84B23D654F40A476FB9AD9EABB9C6B8B91CDBCEF619722819BB90908;
probe SHA2561119DE7F72878D79B7B67708CE8EBC20C4D66A2BB12373F3E216FB7BADEA1511.

The bounded follow-up Luna review failed due to provider usage limit, so no
independent review is claimed for this delta. Primary source inspection verified
one entry lock covers lookup and assignment, no network under that lock, and
denial does not extend deadline; actual wire and expiry tests supply the evidence
above. The earlier completed observation-only review remains limited to that
prior slice. Media persistence/coordinator, room-finished processing, client
remaining-grant integration and product cutover remain pending.
