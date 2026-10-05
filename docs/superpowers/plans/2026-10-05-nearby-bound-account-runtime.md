# Bound account voice and sharing acquisition

Every account runtime builder installs its owned ChannelSharingCoordinator before publishing
the result. Return one immutable VoiceAccountRuntimeBinding (voice + sharing), cached and
coalesced across waiters; UI cancellation cannot discard construction. Existing explicit effect
observer remains an observer for legacy tests; production acquireBound supplies no external
callback and always dispatches to the original entry's actual coordinator.

During idle construction no positive sharing is possible. Initial ungranted cleanup may have
no original session and no adopted captures; reject a positive/earned callback before binding.
Obsolete builders and retireAll fence sharing synchronously without awaiting held native GPS
cleanup; original root guardian drains it separately. Preserve native voice replacement gates
and captured predecessor ordering. Failed partial builder retires already-built runtime.

Tests: duplicate bound result identity without audio/HTTP; held refresh and cancelled waiter;
actual voice/foreground/consent/native/fresh-fix HTTP publication without UI relay; old bound
sharing retired on logout/replacement. Fresh full Android. No public UI/device/production switch.

Bind VoiceConversationStartup lazily in Android DI with the actual foreground controller,
call roster factory and prepared Android audio factory through acquireBound. DI construction
does not create accounts/SDK rooms/start services; public Nearby screen cutover remains later.
