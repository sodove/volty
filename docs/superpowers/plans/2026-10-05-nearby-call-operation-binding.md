# Original operation binding across calls and channel-only join

Prerequisite fc2c7ca5. Call API remains unbound. A caller operation is permanently bound
by its call row even before accepted media exists. A terminal ringing call cannot let
channel begin reinterpret that same caller nonce. Channel-only SessionService.join must
reject a nonterminal DIRECT_CALL operation before room resolution/SFU or any stop, even
when call UUID equals channel UUID. A repository denial without a row is a stable denial,
never a checkNotNull failure or credential grant.

Generic cancellation while the original caller is still RINGING must finish that original
ring and write the caller-operation tombstone in the same pair transaction, releasing only
the exact reservation. Do not create an unrelated operation and leave voice busy for 45s.
If an original destinationless cancellation arrives before call creation, later create
stores a caller-private never-rang CANCELLED binding. Known old channel/media operations
cannot be reused as a new call; conflict is immutable. No phantom incoming notification.

Lock order remains access fence -> all pair actors -> call/resource -> revision. No nested
transaction from an actor lock, no extra media or SFU effect in cancellation. Call/channel
namespace guards do not depend on UUID collision probability or producer behavior.

Tests before source on real PostgreSQL: terminal ringing operation cannot start channel;
actual SessionService CALL/channel UUID collision preserving both claims and issuing zero
SFU/credentials; null-state repository denial; generic caller stop during ringing; stopped
caller operation arriving before creation producing private cancellation. Fresh whole PG,
backend unit and Android suites plus parent manual review. Positive call authorization and
callback terminal pair ownership remain next; no product/native activation claim.

Review additions: private cancellation construction samples one server instant; two samples
could turn a never-rang stop into timeout and violate the private-row CHECK. Known old media
operations cannot be reinterpreted as destinationless cancellation. The cancellation/creation
race uses a narrowly scoped JDBC proxy to observe the actual single-actor SQL execution while
create holds pair locks. The fallback must hand off without mutation, release its actor/fence,
then close the original pair and tombstone together; scheduled tasks alone prove nothing.
