# Nearby direct HTTP host guards — evidence

Shared internal nearbyHttpsApiBaseUrl validates original authority/hostname before Ktor
normalization, rejects literal userinfo and raw query/fragment delimiters, retains parsed
HTTPS rules and trailing-slash handling. Seven direct adapters plus accountfactory use it
before HTTPchildallocation. No wire/DTO or WSS/LiveKit credential changes.

Actual direct-constructor RED7tests7fail27s35tasks. After common guard integration, freshfull
Android2802 tests, exact0failures/errors/skips, BUILD SUCCESSFUL31s35tasksexecuted,
rerun-tasks/no-build-cache/no-configuration-cache. Ordinarytest evidence, no mutationsweep.
Tests drive all7constructors with emptyauthority, missinghost, emptyuserinfo, portonly;
assert authunread; preserveIPv6port/localport/encodedpath. Existingfull13account-method
HTTPintegration tests staygreen. Luna source review PASS; no reviewerbuilds/nativeclaims.

Ruling: accountfactory anddirectguards committedatomically tokeep factory/helper tree
independentlybuildable. Their prior2795 fullPASS+rawhost regressions recordedin separate
account-composition evidence. Backendunchanged, no backend rerunrequired/claimed.
Next: browserparentlifetime tests/cleanup before aggregateaccount composition andUIwiring.
