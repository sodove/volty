# Own bounded backend maintenance through actual completion

Publish a negative/background work port from the assembled component set. The application
owns one serial pulse collector, one session reconciliation attempt and one webhook attempt
per pulse. A session exception cannot starve webhook work; sanitize it to a failure count.
No tight drain loop and no claim that a reported work item means remote cleanup succeeded.

Borrow the actual application root, datasource, SFU and component port. A root cancellation
or explicit stop fences new work immediately. A blocking cancellation-ignoring admitted call
remains producer-pending until it actually returns; close false is truthful. Check cancellation
between both work lanes. Close only owned Jobs, never borrowed root/ports/database.

Attach optional explicit maintenance port to AppDependencies; only the complete future v2
factory supplies it. Module uses actual application Job as parent and stops owned scope at
ApplicationStopping. Test mode starts no timer/maintenance producer. Default legacy behavior
otherwise remains; this task does not activate production Nearby or deploy.

Tests drive finite pulses and actual callback entry: session exception then webhook/retry;
held blocking callback means close false and borrowed root active, late release cannot issue
webhook; parent cancellation similarly keeps actual producer pending until release. All
fixtures release gates and close owned Jobs. Fresh backend, PG and Android before commit.
