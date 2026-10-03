# Nearby transactional media observation foundation

V5 adds participant SID, SFU state and an observation version to the existing
immutable session operation. Media SID/state changes append roster revision and
outbox in the same actor-locked transaction. Repeated identical observations
advance the observation version to fence competing network replies, but do not
invent another visible roster revision. Terminal/expired/foreign-session/epoch/
noncurrent-claim operations cannot earn presence. A late reply captured at an old
observation version cannot overwrite a newer SID.

Connected heartbeat receipts require the same current ACTIVE media SID/version
under the actor lock. Generic foundation heartbeat remains separate until the
next SessionService integration; these repository methods do not enable production.
All SFU network requests belong outside SQL locks.

The repeatable-read roster now exposes current SID and CONNECTED only for an
ACTIVE operation with observed ACTIVE media. JOINED/unknown remains CONNECTING;
pending remains JOINING. Deadlines and server time remain explicit, with expiry
persisted through lifecycle transactions. No state asserts device audibility.
Roster outbox includes SID/state but contains no JWT/body/location.

## Evidence

- Missing-API compile RED before implementation.
- Initial actual PostgreSQL8 run df178e8b-204d-4016-a444-0899c693bae8 produced
  runtime RED in the deliberately incoherent SID-nonnull/state-null fixture:
  PostgreSQL permits CHECK results of NULL. The first-draft V5 constraint was
  corrected to explicitly require media_state IS NOT NULL in the paired branch.
  This was an undeployed draft, not a claim about existing production data.
- Corrected fresh actual PostgreSQL8 PASS c5f32fd3-aa9d-4a88-97b6-1752f8061b0f.
- Two additional integration cases force a media outbox constraint failure AFTER
  media save and revision advance, proving full rollback of SID/version/clock;
  V4→V5 preserves pending lease/current claim and undelivered signed hook, with
  unknown media instead of fabricated confirmation.
- Actual pinned-revision SELECT barrier plus a concurrent committed media writer
  yields revision2/unknown in the old snapshot and revision3/connected later.
- Late old SID/version, expired confirmation, renewal version/SID mismatch,
  unconfirmed admission deadline, and incoherent current-claim epoch are covered.
- Fresh backend109 PASS including confirmed SID HTTP boundary. Full actual
  PostgreSQL63 PASS run50d40661-8ea3-4389-b60f-43b91ca426e7 (prior53+10media),
  zero failures/errors/skips,6 executed Gradle tasks,5m3s; pool and fixture cleaned.
- Fresh Android2573 PASS. Counted priorV1..V4; default migration count4→5
  deliberately updated. Previous legacy operation/inbox tests still pass.

Primary source review checked actor→revision lock order, immutable session/epoch
guards, post-lock expiry clock, observation CAS and media field preservation
through lifecycle writes. No independent reviewer result is claimed for this
slice after the earlier provider usage failure. Tests do not establish real
community authorization, webhook consumption, client integration or phone audio.

Next: coordinator current-SID reconciliation, heartbeat switching to conditional
SFU renewal, then room-finished events/remaining-grant client adaptation and the
remaining roster delivery/community/UI work. Session, roster and webhook
production dependencies remain null.
