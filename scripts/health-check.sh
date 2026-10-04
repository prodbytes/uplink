#!/usr/bin/env bash
# Continuous health monitor: one line per check, one emoji per service.
# Runs via `devbox services up` (see process-compose.yaml) or standalone.
# Check interval in seconds is configurable via HEALTH_CHECK_INTERVAL.
set -uo pipefail

INTERVAL="${HEALTH_CHECK_INTERVAL:-15}"

check_database() {
    if pg_isready -q -h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" \
            -U "${POSTGRES_USER:-postgres}" 2>/dev/null; then
        echo "🐘 database ✅"
    else
        echo "🐘 database ❌"
    fi
}

while true; do
    # Add more services here, one check_* call per service, joined on one line
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$(check_database)"
    sleep "$INTERVAL"
done
