# Encrypted invitation draft storage — evidence

Implemented strict bounded versioned payload, immutable account/slot binding, atomic encrypted
record retain, cache confirmation after failed writes, account-specific Android Keystore AES-GCM
and synchronous preferences adapter on IO. Native store remains outside production DI.

Verification: full Android unit suite2778 tests, failures/errors/skips0, BUILD SUCCESSFUL32s,
35tasks executed, --rerun-tasks --no-build-cache --no-configuration-cache. Ordinary tests,
not mutation evidence. Backend unchanged; no backend tests required for this client-only slice.

Review defects corrected:
- Duplicate canonical/Unicode-escaped JSON names: actual RED1failure, streaming field tracking.
- Confirmation ordinal overflow: actual RED2tests1failure24s, alternating1/2 marker instead.
  Retraction: ordering was unnecessary; only changing memory generation is needed.
- Provider default GCM tag: helper MissingAPI RED21s33tasks, explicit emitted body128bit tag
  guard used by real encryption; exact output asserted with real JVM JCA.
- Fixture retraction: digit-only actor uppercase was unchanged; alphabetic actor plus unequal
  precondition now tests canonicality. No production guard changed for this test correction.

Luna narrow source reviews and fix loops PASS: common codec/store, preferences/native wrapper,
actual JCA integration. Reviews did not run builds. No Astra repeat.

Limits: JVM fixtures verify record protocol/policy and actual JVM cryptography. Android
Keystore/provider/preferences, process death, power-loss behavior need device evidence.
No physical persistence proof, production UI/DI, logout retirement or storage deletion claimed.
No secret in diagnostics; corruption/key loss fail closed rather than regenerating a token.
Next: account lifecycle retirement and cleanup before enabling factory/DI.
