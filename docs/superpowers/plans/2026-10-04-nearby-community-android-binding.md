# Android binding for trusted community factory

Trusted common composition is complete at 1f673219. Register the factory lazily in
Android DI with a named app-owned OkHttp client, bounded timeouts, actor-specific
AndroidInvitationDraftStore and AndroidInvitationSecretGenerator. Koin release closes
only the named root client; consumer retirement closes its configured adapters.
Production root is https://volty.sodove.ru/v2/nearby, matching the current social
HTTPS host and actual backend v2 route source; no probes, deployment or UI switch.

Configuration-only wiring: common factory has actual lifecycle/HTTP/draft tests already.
Compile Android and run the fresh full suite with exact totals before commit. Do not
fake AndroidContext/Keystore execution in JVM: native DI resolution, preferences/key
provider, process death and audio remain device verification work. No new UI strings.

Koin 4.2 release callback API verified in primary documentation:
https://insert-koin.io/docs/reference/koin-core/definitions/#release-callback
The named singleton owns its client; no client ownership is transferred to an account.
