# Channel sharing consent HTTP boundary

Expose authenticated v2 own-consent start/status/revoke paths bound to canonical original
session UUID and generation UUID. Actor comes exclusively from live JWT/profile validation,
never request body. Explicit LOCATION/RIDE/FULL and HALF_HOUR/TWO_HOURS/EIGHT_HOURS only.
Bounded strict body parser already used by community writes. No coordinates/navigation here.

Optional endpoint port defaults unavailable until coordinated v2 activation. Start/status
denied return generic 404; original revoke requires no current membership or voice session
preflight. JDBC adapter delegates existing fenced consent transactions on IO dispatcher.
Response checks exact actor/session/generation, valid phase/mode pairing and finite original
TTL credit; omit raw server clock/deadline and reject malformed port output with 503.
Negative acknowledgement carries zero remaining credit, never revives ACTIVE.

Tests first against actual Ktor route/auth/serialization: authentication and disabled ports,
exact actor binding/TTL/mode forwarding, malformed IDs/body bounds, generic denial, original
negative submission without positive access checks, and deliberately misbound/incoherent
port output. No mirrored constructor tests. Default backend and full Android fresh exact
counts before commit. SQL unchanged from consent stage; retain full PG304 evidence unless
adapter or repository edits justify additional PG. No deploy/new legacy group migration.
