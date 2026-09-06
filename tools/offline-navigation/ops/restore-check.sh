#!/usr/bin/env bash
set -Eeuo pipefail
echo 'restore-check requires an explicit temporary PostgreSQL database and backup path.'
echo 'Run pg_restore/psql against a separately named temporary database; never use the production database.'
exit 2
