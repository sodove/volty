# Owner channel creation evidence

Current active owner authorization, immutable receipt replay and cap128 happen
inside one UPDATE-fenced transaction. Stable channel UUID plus actor/community/
normalized-name hash identifies the request. Exact replay returns the original
revision without creating another channel. Foreign parent, default/deleted IDs,
different payload and former-owner requests cannot mutate the resource.
Creation writes channel/outbox/receipt atomically and touches no voice claim,
membership or sharing. V10 adds receipts; V1..V9 are unchanged. Three migration
count expectations explicitly moved9 to10.

Six tests produced missing-API compile RED before implementation. Actual PG6
PASS16651 run5f3c87b7-1b73-4871-908a-5290517cd727, exactzero. Broader PG36 PASS25338
run522dadd5-b982-45d1-bbf7-0f158ad7c18f covered creation6, repository21, historical
community migration4 and startup retirement5, all count expectations and V10.
The owned harness now accepts multiple test filters with repeated --tests args;
this run proves all four selected classes executed, while exact counts and owned
cleanup checks remain required.

Narrow Luna review found default+matching-receipt incoherence could replay Created.
Added deliberately producer-impossible seventh fixture; actual PG1 RED87840
run67165e74-43c2-4dcd-baab-6dc583498242 observed expectedConflict/actualCreated with
isDefaulttrue. Added !is_default to the replay predicate. Final fresh PG7
PASS57924 run644d92d7-1ddb-4b04-91cc-ac0b58126bc3, exactzero. Review fix-loop passed.
PG36 predates this one-predicate fix; the final source verification claim is PG7,
not a fresh combined37/full-PG run. All four disposable runs stopped their owned
containers; all6 Gradle tasks executed per run.

Fresh backend143 PASS23109 and Android2680 PASS92118, exactzero, all6/35 tasks
executed with rerun-tasks and build/configuration caches disabled. Android sources
were unchanged during the backend-only review fix. These are ordinary tests,
not an audited mutation sweep or device proof.

Channel deletion, HTTP/client mutation commands, invitations, moderation,
production sharing/realtime/Android wiring and UI remain pending. Detailed UI
planning is deferred to assembly by the user; no production deployment occurred.
