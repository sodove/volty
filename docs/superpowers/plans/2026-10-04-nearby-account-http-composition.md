# Account-bound Nearby HTTP composition

1. Add a common account factory for community read/create/join, membership removal,
channel create/delete, invitation commands and owner metadata listing. Five existing bounded
HTTP adapters remain authoritative for wire parsing. One immutable NearbyAccountBinding.
Expose domain interfaces only, never raw mutable-global bearer injection.
2. Every exposed method checks caller cancellation and active binding before invocation and
again on successful/typed-failure return or thrown ordinary exception. An already-issued
operation may commit; terminal account is cancellation, never a new-account result.
Sameactor refresh applies to future requests; crossactor refresh rejected, retire permanent.
3. Own only its five configured HTTP children, parent remains usable. Retire all children even
if an earlier close throws; safe generic failure if cleanup errors. No voice/SFU cleanup claim.
Validate common root URL before allocating any child. Root is /v2/nearby; communities paths
append /communities; invitation adapters receive root. Voice and roster excluded: retirement
must preserve dedicated native/remote stop drain rather than generic HTTP shutdown.
4. Actual MockEngine tests: all13 methods reach correct route/method with bound authorization;
refresh sameactor then foreignactor rejection; all13 retired methods issue no HTTP; late
response after retire cannot publish including typederrors; new independent account still
works and parent client survives. Assertions cover actual issue, not timestamps. MissingAPI
RED then full exact-count suite and Luna source review. No UI/legacy repository DI yet.

Future aggregate lifecycle must retire owned browser/draft/voice owners as well as HTTP;
this factory guarantees its own return boundary, not results already handed to consumers.
No legacy migration, native deletion or rollback of remotely committed commands.
