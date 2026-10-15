# Draft only — not sent

Subject: GPLv3 linking permission for a Volty history-screen port

Hello Vescape maintainers,

We are developing Volty, a Kotlin/Compose Android personal-EV telemetry app.
We would like to port the history screens and related history logic from Vescape
revision `2527c346bbdb25d4e018fdd68e916621e20fac27`, principally
`src/modules/history/` and `src/screens/main/history/`. We intend to preserve
attribution, identify our changes, keep the resulting port under GPL-3.0-or-later
and provide its corresponding source.

Volty uses Firebase Cloud Messaging for incoming voice-call notifications. Its
current Android dependency set includes the following client libraries whose
published Maven POMs declare the Android SDK License:

- Google Play services: base 18.9.0, basement 18.9.0,
  cloud-messaging 17.4.0, stats 17.0.2 and tasks 18.4.0.
- Firebase iid-interop 17.1.0 and measurement-connector 19.0.0.

We have not established an applicable GPL System Libraries exception or an
additional linking permission for these artifacts. We noticed Vescape's own
Android module uses Play-services Wearable, but do not want to infer permission
for a different combination from that alone.

Would the relevant copyright holders be willing to grant an explicit GPLv3
section 7 additional permission to combine the ported Vescape material with the
libraries listed above, while retaining GPLv3-or-later for the covered work and
the libraries' own applicable terms? If possible, please also clarify whether
that permission would cover future versions of the same libraries. If this
requires consent from other contributors, please let us know whose consent is
needed and the scope you are able to authorize.

No Vescape product code or assets have been imported or distributed by this port
yet. We can provide the complete resolved dependency/license inventory and the
planned transferred-file list before proceeding.

Thank you.
