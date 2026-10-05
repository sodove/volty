# Canonical original incoming action address

Native PendingIntent data encodes only original opaque binding/ticket and ACCEPT/DECLINE:
volty://incoming-call/{binding}/{ticket}/{action}. Canonical lowercase UUID/path, no query,
fragment, escaping, user/call identity or credentials. Parsing malformed native payload yields
absent rather than throwing. The address is lookup metadata, never microphone authorization.

Registry can issue native action binding only for the exact ticket currently being published
or actually published by its bound owner. Set a short synchronous publication slot around
the notification port call (before output state exists), clear it in finally; historical,
forged or failed tickets cannot mint native actions. This breaks the factory/owner construction
cycle without fallback/global token suppliers. Same queued serial owner throughout.

Tests canonical round trip/rejections and actual native-port callback fixture obtaining binding
during verified show, then refusing original ticket after consumption. Android factories and
nonexported receiver follow. Fresh full Android suite before commit; parent review only.
