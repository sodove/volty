# Current history regression evidence

Baseline: `4277ef85`, 2026-10-13. User field report: historical recording/graphs
are unreliable; no exact vehicle/sequence trace was provided for the broader report.

Three defects were reproduced with actual `DefaultGraphComponent` and an injected
history repository in `GraphComponentTelemetryTest`:

1. Opening a ride with a hidden VOLTAGE series, then adding VOLTAGE, returned an
   empty row rather than stored 63 V / 62 V. The complete historical series had
   been discarded after projecting only visible metrics.
2. Holding the load of A, opening B, then completing A replaced B with A.
   The test asserts A's load was actually entered before switching.
3. Returning to live while a historical read was pending allowed its eventual
   result to select the old historical ride again and replace live 80 V with 63 V.

Valid RED run `h2-red`: 7 tests executed, 3 failures in the intended new cases.
Earlier `h1-red` did not compile because the test lacked the Essenty destroy
extension import; it is not behavioural failure evidence.
GREEN run `h3-green`: 7 tests, zero failures/errors, 34 seconds; results cleaned
and changed production bytecode compiled, no stale result reuse.
Evidence logs live in `.superpowers/sdd/2026-10-13-ride-history-rebuild/`.

Repair: retain the complete historical metric cache, derive selected/added/XY
series from it, cancel and generation-fence old reads, clear cache on returning
to live. Lifecycle cancellation remains owned by the component context.

Other observed code limitations, not scored as field-confirmed causes:

- Recorder retains the LAST value per 5-second metric interval. Graph peak over
  stored points cannot recover an earlier short spike. New summaries must be
  computed before trace thinning; old exact peaks remain unavailable.
- Recorder starts/ends from BLE states; sustained GPS, reconnect and explicit
  manual stop require the new separate recording owner.
- Current source stores aggregate metric keys without contributor identity/GPS
  segments. Old GPS and per-cell history cannot be reconstructed later.
- Retained four-hour streams and shared mutable recording state need generation/
  admission and delayed-store fixtures. Repository clears buffers on some paths;
  contamination/races are not claimed fixed without those fixtures.

The small graph repair is not the completed history rewrite, a native visual
acceptance, or evidence that every reported recording issue is resolved.
