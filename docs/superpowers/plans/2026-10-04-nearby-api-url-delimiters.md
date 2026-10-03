# Nearby API base URL delimiter correction

The roster transport review proved empty `?`/`#` delimiters bypass parsed query/fragment
checks. Appending command paths can then address a different resource. Earlier parsed-only
URL validation reviews are retracted for this edge case; six adjacent v2 adapters share it.

1. Before changes, independently drive construction of each affected adapter against bare
   query/fragment delimiters and populated variants. Observe all six fail regression tests.
2. Reject raw query/fragment delimiters before parsing in community reads/removal, channel
   commands, invitation commands/listing and voice session HTTP. Retain HTTPS/credential
   checks; percent-encoded path data remains legal. No LiveKit WebSocket query restriction.
3. Full fresh Android suite, exact counts, narrow Luna review, evidence and separate commit.
   No backend or production wiring change.
