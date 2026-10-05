# Original sharing owner location foreground authority

NearbyVoiceService currently acknowledges microphone FGS only. A channel sharing capture must
earn a separate location foreground acknowledgement before acquiring its owned native demand.
Use a dedicated location service/controller/authority, leaving voice cleanup independently
owned. New UI will launch it explicitly from resumed Activity after permission consent; no
background auto-restart, background-location permission or implicit startup.

Extract the existing generic foreground identity gate with compatibility voice aliases;
compose a dedicated typed location authority so DI/identity cannot collide with voice. A
pending ticket, unavailable permission/provider, retired owner, stale service or old stop
cannot admit location. On loss fence before original nonblocking root hide callback. Hold
location FGS while any original native capture is owned/in flight/failed cleanup, independently
of cleared user intent; native stop acknowledgement remains separate from remote revoke.

Android controller checks actual coarse/fine permission + location enabled, resumed Activity,
exact ticket, bounded readiness wait. Service starts LOCATION type before acknowledgement,
observes original capture resource owners, offers localized hide-position notification, and
never invokes borrowed voice disconnect. Declare LOCATION FGS type/permission. Lazy DI only;
no capture/publisher/UI activation until mandatory original owner integration.

Tests first for typed authority, exact owner/service replacement, non-live owner refusal before
replacement, permission loss and native cleanup release truth. Existing voice tests exercise
extracted authority compatibility. Native foreground/permission behavior needs device evidence,
not JVM mirroring. Fresh full Android before commit. Parent manual review only.

Reference: https://developer.android.com/develop/background-work/services/fgs/service-types
(location section; retrieved 2026-10-05). Native prerequisite coarse OR fine + location enabled;
while-in-use restrictions apply to startup. ServiceInfo location type exists from API29.
