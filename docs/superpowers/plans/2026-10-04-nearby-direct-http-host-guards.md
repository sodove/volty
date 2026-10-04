# Nearby direct HTTP root validation

Account factory investigation measured that Ktor normalization can synthesize hosts for
empty authority or empty userinfo. Existing seven direct HTTP adapters must reject these
before parsing/child allocation too; production factory alone is insufficient.
1. Add seven direct-constructor runtime regressions for missing raw host, empty/userinfo
host and port-only authority; auth reader must remain untouched; close accidentally-created
children in assertion finally. Preserve actual HTTPS IPv6/port and encoded path delimiters.
2. Extract internal shared nearbyHttpsApiBaseUrl in data/nearby. Require raw scheme separator,
nonblank original host and no userinfo before URL parsing; retain existing HTTPS/noquery/
fragment checks and slash normalization. Seven adapters plus accountfactory use same helper.
No WSS/livekit credential policy change, no URLs auto-rewritten to anotherhost.
3. Watch runtimeRED, implement source only; narrow Luna review; fresh full exactcount suite,
record ordinarytest evidence and commit. No provider/device/native or production DI claim.

Changed sources: CommunityHttpPort, CommunityRemovalHttpPort, ChannelCommandsHttpPort,
InvitationHttpPort, InvitationListingHttpPort, VoiceSessionHttpPort, VoiceRosterHttpPort,
NearbyAccountHttpSession, plus shared helper. Existing wire formatting/codecs unchanged.
