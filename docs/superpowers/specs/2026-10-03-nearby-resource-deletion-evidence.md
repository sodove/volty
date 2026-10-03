# Nearby owner community deletion foundation

The last owner can now explicitly delete a community through the new repository.
This is a disabled foundation operation, not an enabled UI/HTTP action.

V9 adds actor/operation-owned deletion receipts and immutable exact cleanup
targets. The transaction requires active ownership, holds the UPDATE access fence,
collects members plus current claim actors, and locks all of them in sorted UUID
order before any claim/revision write. It cancels only exact claims in this
community, tombstones community/channels, deletes the corresponding legacy group
with its V1 cascades, and commits revision/receipt/targets together.

Matching replay returns original revision/targets even after cleanup or a new
voice connection elsewhere. Conflicting operation payload cannot delete another
resource. Tombstone UUID cannot be reused. Historical member rows deliberately
remain behind deleted community state to satisfy V7's owner/member FK; existing
detail/catalog/membership/join/voice access exclude deleted communities. This does
not authorize live access or carry legacy sharing consent into v2.

Fresh verification:

- Missing repository/API compile RED; actual PostgreSQL targeted8 passed, run
  `e3897b60-81b8-4ef1-9124-8d0092bfff6f`, zero failures/errors/skips.
- Full actual PostgreSQL131 passed, run
  `d9c74860-fef5-4339-a553-16cca15d1d72`, exactly123 previous plus8 new cases,
  zero failures/errors/skips. Final V9/repository source,6 executed Gradle tasks
  in16m44s, caches disabled/tasks rerun. Both owned fixtures/tunnels cleaned up.
- Fresh backend123 unit tests passed before full131; subsequent HTTP cleanup
  projection changed routes/DTO only and passed full124 units. It did not change
  repository/schema covered by PostgreSQL131.
- New tests cover owner alone, multi-member exact cleanup, unrelated voice,
  denied member/unknown resource, replay after completed cleanup/new voice,
  conflicting operation, forced final outbox rollback and incoherent claim.
- Narrow Luna source review covered sorted batch actor locks before the first
  persist/revision only; it was not an owner/SQL/legacy/replay review.
- Android2611 passed before subsequent independent stop-confirmation edits;
  final fresh Android2614 passed before commit, exact count and zero failures/
  errors/skips, including the independent3 stop-confirmation tests.

Deleted SQL resources are not SFU revocation ACKs. Owner channel deletion,
moderation/invitations, account-deletion fence, v2 sharing transaction integration,
legacy writer quiescence/final catch-up and HTTP/UI cutover remain required.
No production deployment, device/audio/Xiaomi proof or mutation sweep.
