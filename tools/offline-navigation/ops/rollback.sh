#!/usr/bin/env bash
set -Eeuo pipefail
echo 'Rollback is intentionally not a filesystem overwrite. It requires the v3 publisher generation command and an explicit generation id.'
exit 2
