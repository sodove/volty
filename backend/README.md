# Volty Nearby backend

This is a standalone Ktor/JVM service. It is separate from the Android Gradle project and has no Redis dependency. The public API origin is `https://volty.sodove.ru`.

Offline navigation files are served by the same Ktor process at `/offline/`.
Docker mounts `VOLTY_OFFLINE_HOST_DIR` read-only at `VOLTY_OFFLINE_ROOT`; the
native nginx proxy needs no special location while it forwards the existing
`volty.sodove.ru` vhost to the app. Publish a verified package and catalog with
`bash deploy-offline.sh --package <package> --catalog <catalog>` after the
backend stack is running. The catalog is replaced last, so a failed publish
does not advertise a partial release. Component URLs must be signed for the
configured `VOLTY_OFFLINE_PUBLIC_BASE_URL` prefix.

## Local development

From the repository root:

```powershell
Copy-Item .env.example .env
# Replace POSTGRES_PASSWORD and VOLTY_JWT_SECRET in .env for anything shared.
docker compose up -d db
.\gradlew.bat -c backend\settings.gradle.kts test --no-configuration-cache
.\gradlew.bat -c backend\settings.gradle.kts installDist --no-configuration-cache
```

Run the complete stack with `docker compose up -d --build`.

Migrations run automatically on app startup. They are tracked in `schema_migrations`; the service keeps only current live sharing rows and never stores route history or BLE identifiers.

For a development account, register through the API. Registration does not require email verification:

```powershell
curl.exe -X POST https://volty.sodove.ru/v1/auth/register `
  -H "Content-Type: application/json" `
  -d '{"email":"rider@example.com","password":"correct horse battery staple","displayName":"Rider"}'
```

Retraction: an earlier README claimed recovery tokens were logged; the request route actually discarded them and sent no email. Recovery now sends a one-time link valid for one hour through authenticated SMTP. Tokens are stored only as SHA-256 hashes and are never logged.

Set `VOLTY_SMTP_SECRET_FILE` to a protected JSON file mounted read-only and readable by the backend UID. Its fields are `host`, `port` (587 with required STARTTLS, or 465 with TLS), `username`, `password`, and `from`. Keep this file outside Git and image build inputs. TLS certificates and server identity are verified. Without this setting recovery returns 503; successful requests return the same 202 receipt for known and unknown accounts. Each mailbox is limited to one request per minute.

The email opens `https://volty.sodove.ru/reset-password#token=…`. The page removes the fragment from browser history and posts the token with the new password; a token is consumed once. `smtpTest` is an explicit integration check that sends one recovery message to the configured SMTP account's own mailbox and verifies it through IMAPS, without changing a production account.

## Verification

Run `clean test`, `installDist`, and `docker compose config` from the repository root using the commands above. The test suite includes pure validation/security/share-rule tests and a Ktor API smoke integration test. A PostgreSQL integration run can target a disposable database by setting `VOLTY_DATABASE_URL`, `VOLTY_DATABASE_USER`, and `VOLTY_DATABASE_PASSWORD` for a deployment-specific test job.

## LiveKit production slice

Voice is disabled unless `VOLTY_VOICE_PROVIDER=livekit` and all of `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `VOLTY_VOICE_TOKEN_TTL_SECONDS`, and `VOLTY_PUBLIC_IP` are present. Missing LiveKit credentials fail backend startup clearly instead of silently advertising voice.

The production Compose stack now includes:

- `app`: Ktor backend on `volty.sodove.ru`
- `db`: PostgreSQL
- `livekit`: `livekit/livekit-server:v1.13.5`, single-node, no Redis
- external nginx: TLS termination and reverse proxy for both
  `volty.sodove.ru` and `voice.sodove.ru`; the Compose stack no longer binds
  ports 80/443

`livekit.yaml` keeps signaling inside the container on `7880` (published on the
host loopback as `17880` for nginx), ICE/TCP on `7881`, embedded TURN on
`3478/udp`, and a small UDP media range on `50000-50019/udp`. The backend API
uses container port `8080` (published on host loopback as `18080` for nginx).
The backend mints short-lived LiveKit room tokens; the API secret never leaves
the server/container environment.

The `livekit` Compose entrypoint validates the values before rendering `livekit.yaml`: `LIVEKIT_API_KEY` and `LIVEKIT_API_SECRET` must use the base64url alphabet (`A-Z`, `a-z`, `0-9`, `-`, `_`, with optional `=` padding), and `VOLTY_PUBLIC_IP` may contain only digits and dots (the deployment expects a public IPv4 literal). This preserves generated base64url secrets while failing fast on `sed` metacharacters instead of silently producing a corrupted config. Do not quote, escape, or add shell syntax to these three `.env` values.

## Nearby voice spaces cutover

`VOLTY_NEARBY_MODE=legacy` is the default, including when LiveKit is configured.
For the new API, set `VOLTY_NEARBY_MODE=voice_spaces`, configure the existing
LiveKit credentials and `VOLTY_NEARBY_SFU_CONTROL_ORIGIN` to the HTTP(S) origin
of the **protocol-4 patched SFU**. The checked-in stock LiveKit Compose image
does not implement this protocol: do not activate the new mode against it.
The complete server/channel/call/roster/sharing/webhook graph and its bounded
maintenance worker are installed together. Credentials alone do not prove
that Android audio works.

`VOLTY_NEARBY_SINGLETON_ORIGIN=false` keeps conservative cleanup semantics.
Set it to `true` only when deployment guarantees one SFU origin and that the
previous process is gone; readiness from one node behind a load balancer
cannot prove this. This is an operator guarantee, not automatic discovery.

During coordinated cutover, stop old backend writers and the old SFU process
before exposing the new backend/SFU and compatible clients. Startup revokes
legacy sharing sessions and removes their ephemeral live positions in one
transaction. It retains account/friend/group history and native new-server
consent; legacy groups are not imported. Old voice/sharing write endpoints
return `409 client_upgrade_required`. Database locks serialize the startup
transaction but cannot prevent an old backend node from writing afterwards.
Restarting the patched SFU retires the previous media process; the backend's
SQL cleanup alone cannot retire already connected legacy audio. Rollback to
legacy does not restore revoked consent: users must explicitly share again.

This configuration is prepared for review; no production cutover is implied.

## One-command VPS deploy

1. Point both `volty.sodove.ru` and `voice.sodove.ru` at the VPS public IPv4.
2. Open inbound `80/tcp`, `443/tcp`, `7881/tcp`, `3478/udp`, and `50000-50019/udp`.
3. Install `nginx/volty.conf.example` into the host nginx configuration,
   ensure its certificate covers both hostnames, and run `nginx -t &&
   systemctl reload nginx`.
4. Run `bash deploy.sh` from the repository root. It creates `.env`, generates
   safe secrets, and starts the Compose services. Existing Caddy orphans are
   removed automatically.

The manual equivalent is:

```powershell
docker compose up -d --remove-orphans --build
```

Useful checks after boot:

```powershell
docker compose ps
curl.exe https://volty.sodove.ru/health
curl.exe -H "Authorization: Bearer <access-token>" https://volty.sodove.ru/v1/voice/provider
```

This Windows host can verify config, token issuance, and packaging. Real media connectivity still requires a public VPS plus two real Android devices on separate networks.
# Automatic offline region delivery

The optional private package service verifies and acquires signed Valhalla, FTS4
and PMTiles regional releases from the release pipeline. Ktor exposes the existing
`/offline/catalog.json` contract plus coverage lookup and acquisition status.
See [OFFLINE.md](OFFLINE.md) for setup and lifecycle details.


## Nearby incoming-call push

In voice_spaces mode, set both VOLTY_FCM_PROJECT_ID and
VOLTY_FCM_SERVICE_ACCOUNT_FILE to enable the optional FCM delivery transport.
The file path must be absolute inside the backend container and point to a
read-only private service-account JSON mount readable by the backend user.
Keep the key outside the checkout/APK/image. Android google-services.json is
separate and cannot authorize the backend. Both settings absent disables push;
partial/invalid settings or an invalid key fail startup before database access.

The application owns one separate interruptible delivery worker and HTTP client.
OAuth and sends have bounded deadlines and responses; a SQL lease never spans
network I/O. PostgreSQL persists hint/device retry state; late receipts cannot
retire a newer binding. API acceptance is not proof of handset notification.
See [provisioning](../docs/superpowers/specs/2026-10-08-nearby-firebase-provisioning.md)
for current native/device acceptance status. This wiring is local until deployed.

The optional `docker-compose.fcm.yml` override adds these settings and a read-only
bind mount. Set `VOLTY_FCM_PROJECT_ID` and the absolute
`VOLTY_FCM_SERVICE_ACCOUNT_HOST_FILE` in the private deployment environment, then
include this override after `docker-compose.yml` and before any immutable release
override. Both inputs are required; a missing host file is not created as a directory.
Compose also accepts relative host paths and resolves them against the first Compose
file; the override enforces presence, not absoluteness. Use an absolute path in the
deployment environment to avoid ambiguous private-key resolution.
The image runs as UID10001, so a host key readable only by UID1000 needs a separate
private UID10001-owned copy or a narrowly scoped read ACL. Keep mode600; do not
make the key world-readable. Omitting the override preserves disabled transport.
