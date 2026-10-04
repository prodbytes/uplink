#!/usr/bin/env bash
# Tags the current commit as a release candidate, X.Y.Z-RC (Z is the current
# time), and pushes it; the Release RC workflow publishes it as a prerelease.
# DRY_RUN=1 only prints the tag. See scripts/tag-release.sh.
set -euo pipefail
exec bash "$(dirname "$0")/tag-release.sh" RC
