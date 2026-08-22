# Volty Nearby backend

This is a standalone Ktor/JVM service. It is separate from the Android Gradle project and has no Redis dependency. The public API origin is `https://volty.sodove.ru`.

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

For a development account, register through the API. Verification and password-reset tokens are logged by the backend because no mail provider is configured in this MVP:

```powershell
curl.exe -X POST https://volty.sodove.ru/v1/auth/register `
  -H "Content-Type: application/json" `
  -d '{"email":"rider@example.com","password":"correct horse battery staple","displayName":"Rider"}'
```

For production, provide a mailer implementation at the one-time-token log boundary before enabling user-facing email delivery. Tokens are stored only as SHA-256 hashes.

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
