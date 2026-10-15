# Direct history-port linking review — open release gate

Checked 2026-10-15. This records a concrete compatibility question, not a legal
conclusion that the existing Volty APK is unlawful or that every Google SDK
component has the same license.

## Observed inputs

- The pinned Vescape tree `2527c346bbdb25d4e018fdd68e916621e20fac27`
  declares GPL-3.0-or-later. Its root LICENSE is the standard GPLv3 text. No
  project-specific Google linking permission was found in the inspected root
  LICENSE/README or the previously inventoried history candidates.
- The existing resolved runtime POM inventory contains seven artifacts declaring
  the **Android Software Development Kit License**, rather than Apache/MIT:

  | Group | Artifact | Version |
  |---|---|---|
  | com.google.android.gms | play-services-base | 18.9.0 |
  | com.google.android.gms | play-services-basement | 18.9.0 |
  | com.google.android.gms | play-services-cloud-messaging | 17.4.0 |
  | com.google.android.gms | play-services-stats | 17.0.2 |
  | com.google.android.gms | play-services-tasks | 18.4.0 |
  | com.google.firebase | firebase-iid-interop | 17.1.0 |
  | com.google.firebase | firebase-measurement-connector | 19.0.0 |

  Exact cached POM references remain in
  [the resolved inventory](ride-history-runtime-licenses.md).
- Actual `debugRuntimeClasspath` dependency insight on this checkout confirms
  the five Play-services artifacts through Firebase Messaging 26.0.0, including
  routes through the two named Firebase interop/connector artifacts. Evidence:
  `.superpowers/sdd/2026-10-13-ride-history-rebuild/h158-dependency-insight.log`.
  Its 1s successful configuration report is **not** a test or a new build sweep.
- The pinned Vescape Android module itself declares
  `com.google.android.gms:play-services-wearable:18.2.0`. That observation is not
  proof of an exception covering Volty's different dependency set.

## What remains to establish

Google's [SDK terms, sections 3.4/3.5](https://developer.android.com/studio/terms)
restrict SDK use/modification/redistribution while separately deferring to open
source licenses for components actually licensed that way. A POM inventory alone
does not establish an overriding open source grant for each artifact above.

The [GPLv3 definitions](https://www.gnu.org/licenses/gpl.en.html) distinguish
System Libraries from other linked code. The [FSF linking FAQ](https://www.gnu.org/licenses/gpl-faq.en.html#GPLIncompatibleLibs)
describes additional permission for incompatible libraries outside that exception;
permission for another author's GPL material must come from its copyright holders.
Whether a particular Google artifact qualifies for an exception is not established
here. Google's [architecture description](https://developers.google.com/android/guides/overview)
also distinguishes client libraries included in an app from the separate installed
Play-services implementation; do not equate those two in this review.

**Retraction/qualification:** the earlier "312 runtime modules, zero missing POM
license declarations" result was inventory completeness, not GPL compatibility.
The seven SDK-license declarations were present in that inventory and must now
be resolved before certifying a directly ported GPL APK. Source/binary notice
checks for the two local archives remain a separate gate.

## Prepared paths

1. Preserve the approved direct port and obtain an applicable permission or a
   substantiated compatibility determination for the actual dependency set.
   [A maintainer request is drafted](vescape-permission-request-draft.md), **not sent**.
2. Independently implement Volty's history UI and artwork without transferring
   Vescape code/assets; this changes the user's approved direct-transfer choice
   and needs a product decision.

No dependency, notification behavior, application license or UI has been changed
to resolve this question. No Vescape product source/assets have been imported.
The original recording/read foundation can continue independently.
