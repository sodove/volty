# Trusted HTTP consumer evidence

Five adapters now support internal construction from SocialAuthenticatedAccount.
Canonical v2 actor is validated before adapter creation; header token is obtained from
that immutable authority and its original generation. Pre/post checks use the actual
borrowed account Job and a local terminal retirement gate. Consumer retirement closes
its own adapters without cancelling the account or retiring other consumers. Consumer
manual refresh is denied for authoritative construction. Standalone credential/Job
construction remains supported and covered by existing tests.

Three fixtures assert actual headers before/after owned auth refresh; one consumer
retired while another and login remain usable; held actual HTTP response across same-
actor relogin cannot publish, old consumer issues no new request; legacy u1 can login
but cannot construct a v2 adapter. No unverified JWT or global-generation callback.

Missing API RED24s33tasks; signature-only placeholder runtime RED3tests2fail32s35tasks,
exact XML, canonical denial control passed. Placeholder removed in final source.
First full2840 onefailure39s35tasks: second request returned UNAVAILABLE before retire,
with MockEngine real dispatcher racing the virtual timeout. Scheduler alignment alone
gave scoped3 zero bad31s35tasks and actual expected header/response assertions. Fresh
full2840 zero failures/errors/skips40s35executed, rerun/no build/config caches, exact XML.
Parent manual review; no independent PASS after Luna quota. Ordinary tests, no mutation.

Next trusted community composition/native factory. No UI cutover, automatic v2 401
retry, voice/audio proof, native power-loss proof or legacy group migration.
