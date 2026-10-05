#!/usr/bin/env bash
# Continuous health monitor: one line per check, one emoji per service.
# Runs via `devbox services up` (see process-compose.yaml) or standalone.
# Check interval in seconds is configurable via HEALTH_CHECK_INTERVAL.
set -uo pipefail

INTERVAL="${HEALTH_CHECK_INTERVAL:-15}"

check_web() {
    if curl -fsS -o /dev/null --max-time 3 "http://127.0.0.1:${WEB_PORT:-8000}/uplink-web.js" 2>/dev/null; then
        echo "🌐 uplink-web ✅"
    else
        echo "🌐 uplink-web ❌"
    fi
}

while true; do
    # Add more services here, one check_* call per service, joined on one line
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$(check_web)"
    sleep "$INTERVAL"
done
