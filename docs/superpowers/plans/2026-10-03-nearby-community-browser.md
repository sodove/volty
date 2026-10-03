# Nearby community browser ownership

Follow approved server/channel UX with a separate testable read-state owner before
Compose integration. Do not expand the legacy NearbyComponent. Permanent
membership/catalog/detail requests never start voice or location sharing.

Own independent memberships, catalog and selected-detail request generations.
Search/refresh replaces its page and cancels the previous request; a response
surviving cancellation still cannot update the replacement. Selection change or
close immediately clears the former details. Account/root retirement explicitly
closes this owner and removes all exposed data; late responses cannot restore it.

Represent not-loaded pages with null items, distinct from a confirmed empty list.
Keep existing rows after failed pagination, with a retryable typed error. Cursor
comes only from the last accepted row. Duplicate load-more while in flight issues
one request; exhausted pages issue none. Refresh resets the cursor. Page size is
bounded1..50. Membership and catalog projections remain independent.

Implement coroutine/state ownership only, not UI, DI, create/join mutation,
roster/live subscriptions, account auth or SDK timers. Future screen adapter uses
this owner and typed mutation actions; it must not equate browsing with consent.
Use finite deferred fake responses that deliberately survive request cancellation
to test old-query/selection/retirement races. Assert issued cursor/query/limit,
not elapsed time. Target RED/green then fresh full Android and narrow Luna review.
