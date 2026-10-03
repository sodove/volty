# Nearby pure client full-snapshot replica

Each observer is tagged by channel and its own subscription generation,
independent of the voice engine. Wrong-channel, retired-generation and older
revision responses cannot replace current rows. Newer full snapshots replace
all rows; an empty full snapshot clears them. Row order does not affect replay.

Equal revision with different peer content, duplicate actor/session identities,
or intentionally incoherent CONNECTED without confirmed SID requires resync.
The replica retains its last valid revision and suppresses currentPeers as null,
meaning unknown rather than an earned zero. Repeated malformed/disconnected
notifications request one resync; a coherent matching snapshot restores it.

Evidence: missing-API compile RED; fresh targeted5 PASS. Full fresh Android2584
PASS (previous2579 plus five replica tests). Narrow Luna source review PASS only
for the listed replica invariants, with no tests/builds run by reviewer.
This is a pure component,
not Compose testing. No backend change belongs to this client slice.

This is replica consistency only: production observer ownership, snapshot
transport/decoding, authorization, peer lease expiry and UI count rendering are
pending. In particular a syntactically CONNECTED row does not establish current
lease freshness or device audibility; future UI must apply those separate gates.
