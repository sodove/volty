# Authenticated roster owner

Bind the read-only roster transport/runtime to SocialAuthenticatedAccount. Preserve
the existing revision/ordinal/lease evidence and actual including-sleep clock. Use
one queued serial dispatcher for roster state. No copied global bearer, SDK counts or
membership mutations. This lifetime owns only roster; full voice cleanup must survive
auth retirement separately and is not acknowledged by cancelling an account Job.

1. Tests first against actual auth and MockEngine: earn connected peer state, hold next
   response and real account child, logout then assert retired/unknown before completion;
   relogin creates fresh owner, late old response cannot restore old count. Assert headers.
2. Explicit retirement from a cancelled caller clears roster but preserves login and
   permits another owner. Same-generation auth refresh supplies the new actual header.
3. Invalid actor/channel/root/dispatcher rejects before allocating roster/guardian children
   or issuing HTTP. No delayed test loop; finite Channel pulses and gates released finally.
4. Aggregate requires actual account context/Job, guarded pre/post snapshot and token
   source, local retirement fence, owned raw port, existing VoiceRosterRuntime. Guardian
   clears read state on auth cancellation; explicit cleanup does not cancel auth/shared
   client and attempts port/runtime cleanup independently. Native factory/DI is later.
5. Fresh full Android suite with exact totals and manual narrow review after Luna quota.

No physical audibility, microphone permission, native stop ACK, new voice engine,
foreground ownership, backend deployment or UI cutover claim.
