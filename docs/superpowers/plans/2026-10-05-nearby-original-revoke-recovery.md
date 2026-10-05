# Negative-only original sharing recovery

Recover a bounded snapshot of saved intents using only the exact verified account actor.
The fresh account bearer may authenticate negative cleanup for the same actor after process
restart; it grants no original voice, membership, location or positive sharing capability.
Existing live original controls still use their captured bearer unchanged.

Mandatory protected original tags are snapshotted before any suspension. Recovery never starts
sharing, reads status, publishes packets, allocates voice/GPS or changes borrowed account jobs.
Validate every journal row belongs to the authenticated actor. Check original account admission
before and after storage/token/network suspension. Revoke exact saved SID/generation only;
conditional journal ACK keeps nonce protection. Failure retains intents and blocks completion.

Use one bounded batch of at most 64; reread after ACK and return incomplete if any unprotected
rows remain or the batch is full (cannot prove absence behind protected rows). No unbounded
background loop. Serialize recovery callers. Always dispose only the owned HTTP child.

Actual HTTP tests cover own obsolete row removal, protected live tag and foreign actor retained,
503 keeping row then retry, and original account retirement while response is held. This stage
provides recovery, not automatic production invocation: startup must next own protection and
serialize recovery against new admission. Never wire a blind periodic revoke-all worker.
