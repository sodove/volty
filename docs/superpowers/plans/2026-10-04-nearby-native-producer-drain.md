# Native producer drain evidence

Successful SDK close does not prove that a cancelled, noncooperative connect or mute
producer has completed. Keep a separate read-only in-flight count for all app-owned
start/stop/preference jobs, removed only by actual Job completion, not cancellation,
isActive, removal from generation maps, or a Stopped event. Forward it through voice
runtime and authenticated aggregate for the future global admission/drain coordinator.

Tests first: held actual fake SDK connect survives Stop and successful release, count
remains one until producer returns; held native mute survives Stop likewise. Assert
issued/returned markers, close ACK and no late publication. Finite gates released finally.
Missing API then signature-only zero projection RED; implementation registers count before
launch and attaches completion callback (including cancellation before body starts).
Fresh full suite exact count and parent manual review; earlier Luna quota remains.

This counts app bridge producers, not undocumented SDK internal/native work. SDK close
contract/device verification, HTTP producer drain and global admission are separate work.
