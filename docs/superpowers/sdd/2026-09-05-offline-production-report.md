# Volty offline production execution report

Date: 2026-09-05

## Current result

This execution is incomplete. The production VPS could not be inventoried or
modified because non-interactive SSH to `sodovaya@mc.sodove.ru:22` returned
`Permission denied (publickey,password)`. No credential bypass was attempted,
and no production deployment, backup, source download, map build, or worker
restart was performed.

The public endpoints that were safely checked are reachable:

- `https://volty.sodove.ru/health` — HTTP 200, JSON.
- `https://voice.sodove.ru/` — HTTP 200, plain text.

These responses do not prove offline production readiness.

## Known repository state

The existing dirty worktree contains prior offline delivery/backend/client
changes and generated emulator diagnostics. The existing offline backend report
explicitly says it delivers already-built packages and does not prove automatic
source acquisition, country-wide coverage, a production artifact origin,
worker/scheduler persistence, or native production acceptance.

No existing changes were reset or removed.

## Blocked evidence

Still required after SSH access is restored: deployment path and Compose labels,
resource admission, PostgreSQL/config/key backups, production image/runtime
health, complete implementation gates, real source snapshots, pilot and Russia
coverage proof, four foreign cold builds, signed APK delivery, native offline
smoke, update/rollback, and restore verification.

See the ignored execution ledger and `production-target.json` for the exact
command evidence without secrets.
