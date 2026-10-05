# Call admission must not acquire trip sharing demand

The new call adapter shares VoiceRuntime with channel sessions. The legacy untyped EnableSharing
event can acquire Nearby demand for any earned session, including a personal call. Existing call
acceptance already revokes both original trip consents; direct-call media does not identify a trip.
Preserve that boundary at actual mailbox consumption using the control's immutable destination
kind, and tag production sharing requests with the rendered/original operation like mute/deafen.
An earlier queued channel request cannot enable sharing in a newer conversation.

Tests before source: actual call HTTP grant/VoiceControlOwner then EnableSharing must issue no
AddNearbyDemand and keep sharing disabled; account facade returns false for call sharing; channel
sharing remains admitted only for matching current operation and earned lease. Preserve legacy
synthetic test commands with optional operation IDs but production emits a pinned operation.
Keep hide/stop cleanup unchanged. Parent manual review, fresh full Android suite before commit.
