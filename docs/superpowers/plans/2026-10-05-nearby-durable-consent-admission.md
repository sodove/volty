# Arm durable original revoke before positive consent

Production sharing control createDurable captures mandatory journal and UUID source. Record
the exact actor/original SID/generation before start HTTP; ensure original positive permission
again after storage returns. Storage/capacity/invalid original record failure prevents positive
HTTP. Remember original intent before issuing so an ambiguous failure never loses cleanup tag.

Original captured-bearer revoke remains unchanged. Exact Negative result conditionally ACKs the
actual saved intent; failed/denied/malformed network reply retains record. Journal ACK failure
does not erase remote Negative evidence and exposes separate journal cleanup pending. Retain
original negative proof for a write completing after a concurrent revoke when caller is live;
cancellation may conservatively leave a record for later replay. No token persistence.

Startup/Android DI must use mandatory durable path. Existing isolated HTTP/control tests retain
explicit non-durable factory. No implicit production null journal. Journal rows are armed cleanup
intents while sharing is active: later recovery/replay must exclude live original generations
or run only before new admission after prior sharing withdrawal. Never blindly background-revoke
every registered row. Durable replay remains the next task, not a claim of this integration.

Actual HTTP tests held storage => zero start HTTP, then successful positive; failed negative
retains row and exact retry removes; storage failure zero positive. Existing startup fixture gets
recording journal; real SQLite journal semantics already proved. Fresh Android before commit.
