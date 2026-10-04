# Authentication ownership before Nearby wiring

The community aggregate is complete at 1a0d297a. Existing authentication is still
global: DefaultSocialRepository can publish delayed login/profile/refresh results
after logout or replacement login. Do not feed that global token/profile pairing
into the new account aggregate. First establish ownership at the actual auth boundary.

1. Tests first against actual DefaultSocialRepository orchestration: login/profile
   held across logout; earlier login held across newer login; restore held across
   logout/login; refresh held across logout or replacement login. Assert issued token,
   returned producer, published session and secure-store writes, not timing alone.
   Constructor restore runs on Default dispatcher: coordinate explicit store read
   barriers instead of guessing when it finished. Release all held work in finally.
2. Authentication generations are terminal capabilities. Start login/register/logout
   before network waits; retire the previous account permission immediately. Restore
   must capture its ticket before its first storage wait. Every publication/store
   commit/refresh retry is bound to its original generation and token provenance.
3. Serialize credential persistence/clear so a previously issued write cannot finish
   after logout clear and restore credentials. Immediate permission withdrawal must
   not wait for persistence or remote logout. Never hold the state publication lock
   across remote I/O. Already-issued remote auth operations may commit; local stale
   results return Unauthorized and do not resurrect a newer/logged-out account.
4. Then expose a trusted authenticated account establishment/refresh boundary that
   pairs actor profile with the token used to retrieve it. Own a fresh account Job
   per successful establishment; terminal logout/replacement cancels that Job.
   No unverified JWT decode; no mutable global token supplier for new Nearby.
5. Parent performs the coupled authentication orchestration work; Luna agents may
   handle narrow fixture/source questions and small isolated changes with supplied
   context. Separate review and full uncached Android suite with exact XML counts
   before each commit. Stage sequentially if needed; no legacy group import.

No production Nearby UI/DI cutover, voice drain cancellation, secure-storage hardware
proof, or backend auth-policy changes in this slice. Existing users/friendships remain.
Cancellation/retirement does not prove durable logout across physical power loss.
