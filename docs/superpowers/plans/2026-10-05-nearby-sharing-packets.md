# Session-authorized sharing packets and same-channel audience

Use existing LocationDto and schema-1 earned telemetry DTO; apply v2 consent-mode sanitizer
inside the owning SQL transaction before storage. Publication requires exact current ACTIVE
CHANNEL session, current membership/ACL, live sharing generation and finite original consent.
Never persist packets from a CALL or reinterpret destination UUID as session UUID.

V17 adds consent started_at and a packet table keyed by original actor/session/generation.
New starts capture server time; existing pre-V17 ACTIVE receipts without a provable start are
revoked on upgrade instead of inventing one. Legacy group sharing/accounts/friends untouched.
Store sequence, normalized payload digest, original packet captured epoch and finite freshness
deadline. Exact same sequence/payload replay returns original packet without refreshing it;
lower sequence or conflicting same sequence cannot overwrite. Validate source capture within
actual consent window and not in server future, max existing fifteen-second freshness; bound
location stale interval and finite coordinates/accuracy. Skewed/stale capture denies publication
rather than relabelling old data as fresh. No server claim about clock synchronization.

Hide/voice disconnect/ACL loss/TTL remove live access immediately through consent/session
filters; delete packet rows on revoke/terminal persistence so later status cannot expose hidden
values. Pending/expired packet absent means unknown, never fabricated zero. No automatic restore.

Audience snapshot requires viewer's actual current CHANNEL session; membership alone denied.
Viewer authorization and single SQL audience selection run on the existing fenced actor
transaction. Other publishers are read in one statement against original current claims,
typed channel, active ACL/user, live consent and packet deadlines; never acquire peer actor
locks after viewer lock or reconcile CALL pair ownership inside this query. No network/SFU IO.
Return own channel UUID, server observation time, original peer session/generation/sequence,
payload and min viewer/publisher lease, consent, sample deadline. Client derives finite read
credit at original request issuance later; old offline points may still exist locally as stale.

Actual PG tests first: publication/replay/conflict ordering, membership-only/wrong channel and
wrong-session denial, new generation and old negative isolation, hide/disconnect packet purge,
lease/TTL/sample expiry, current-peer ACL loss, telemetry incoherent flags and tier filtering,
invalid/future/stale captures. Count migration17 and exact PG suite; backend+Android before
commit. No HTTP/native/publisher/device/deploy claim until their later boundaries are wired.
