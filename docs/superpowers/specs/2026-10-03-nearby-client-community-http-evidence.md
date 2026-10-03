# Nearby client community HTTP boundary

Typed membership, public catalog, member detail, stable-ID private/public creation
and explicit public joining are implemented independently of voice and sharing.
The owned HTTPS client refuses redirects, bounds the complete request to ten
seconds, propagates cancellation and exposes typed failures without raw response,
exception or bearer text. Successful bodies require strict UTF8 and validated
canonical IDs, page order/cursor, expected detail ID and actual JSON booleans.
Catalog models contain only summary fields; PRIVATE catalog entries are rejected.

The initial eight cases passed after missing-API compile RED. A legal fifty-item
page with escaped descriptions then failed under the initial256KiB response cap.
That assumption is retracted: JSON escaping can take six bytes per character.
The response cap is512KiB and the legal-page case passes. An invalid UTF8 body
then exposed decoding exceptions being classified as network unavailability;
the pure decoding boundary now reports INVALID_RESPONSE. The same runtime RED
was reproduced and corrected in the voice HTTP boundary, retaining strict decode
and never turning replacement characters into credentials.

Fresh targeted23 PASS: ten community and thirteen voice HTTP cases. Fresh full
Android2625 PASS, zero failures/errors/skips; all35 Gradle tasks executed with
rerun-tasks, no build cache and no configuration cache. This is an ordinary test
run, not a mutation sweep. Cases assert issued paths, query, stable creation body,
privacy, cancellation, timeout, malformed fields and finite body limits.

Narrow Luna source review passed page/detail/resource provenance and summary
privacy only. No production DI, screen, auth refresh, SDK runtime, device routing
or Xiaomi audibility proof is claimed. Future channel creation must enforce the
detail decoder's128-channel limit. Backend SQL is unchanged in this slice;
preceding PostgreSQL131 evidence remains scoped to that repository/schema.
