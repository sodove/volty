# Trusted account HTTP consumers

Authentication lifetime through e5c8d7c3 is now concrete. Connect the five Nearby HTTP
adapters to SocialAuthenticatedAccount rather than copying tokens from a global flow.

1. Tests first: actual login establishes authority; same-generation refresh changes
   the actual Authorization header; retiring one HTTP consumer preserves another and
   the login; manual consumer refresh cannot replace authoritative credentials.
2. Hold an actual HTTP response across same-actor relogin. Old authority is cancelled
   before completion; old consumer cannot publish or issue another request, while a new
   authority works. Assert actual headers and returned producer, not delay alone.
3. Legacy noncanonical ids may still authenticate, but cannot create a v2 consumer;
   reject at construction before HTTP. No JWT decode or UI-supplied actor/token pairing.
4. Preserve standalone NearbyAccountCredentials construction for existing fixtures and
   explicit boundary use. Trusted construction is internal, canonical actor validated,
   token callback bound to the immutable authority. A local terminal retirement gate
   fences both constructions; retire never cancels account Job or shared HTTP client.
5. Fresh full Android suite, exact count and narrow review before commit. Luna reviewer
   unavailable after quota error: manual review ruling recorded, no independent PASS.

This stage connects HTTP only. Community composition/native DI comes next; it does not
enable product UI, automatic 401 retry on v2 responses, voice shutdown or group import.
