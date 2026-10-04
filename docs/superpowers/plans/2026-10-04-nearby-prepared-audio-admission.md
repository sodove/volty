# Prepared audio admission

Attach actual async preference preparation to the authenticated voice runtime. A prepared
audio factory admits no join before a successful ready snapshot. Reserve a monotonically
ordered local preparation ticket on the queued owner before reading storage; load outside
that finite registration. Recheck exact account/admission/current ticket/caller cancellation
before installing the immutable snapshot and enqueueing join. Direct join/disconnect supersede
pending reads; retirement/account logout already fence positive admission. A later user intent
must not be replaced by an older slow read. Failure is surfaced; never fabricate AUTO.

The configured factory captures prepared preferences per allocation. Active SDK resources
keep their own immutable preferences. This does not implement live audio route switching.
Android provider gains an unprepared factory creation path; no SDK allocation during DI.

Tests first with actual repository account, MockEngine and issued SDK allocation: held read
prevents join/HTTP until ready; disconnect invalidates it without cancelling login; later
read/join supersedes an earlier held one. Observe missing API RED, signature runtime RED,
then source and fresh full suite exact count/manual review. No UI/foreground/Xiaomi claim.

Evidence: missing API RED 23s/33 executed; signature runtime RED 3 tests/3 failures/0 errors, 32s/35 executed, exact XML. Initial full 2880 tests/1 failure (36s): existing cancelled-caller disconnect contract regressed when NonCancellable and dispatcher change were combined. Retained the outer NonCancellable boundary and nested the queued owned command; source records why. Repaired full 2880/0 bad, 37s/35 executed. Supplemental auth logout, noncooperative cancelled reader, and failed storage/recovery tests cover existing postguards; no separate mutant RED claim for these. Final fresh full rerun/no caches: 2883 tests, zero failures/errors/skips, 36s/35 executed. Parent manual review only: finite queued ticket registration, no mutex across storage, canonical IDs before read, active original authority/global permission before publication, readiness in native predicate, independent output snapshot, disconnect supersession and cancelled-caller cleanup. No UI cutover, foreground permission proof, physical routing/Xiaomi proof, independent review or mutation claim.
