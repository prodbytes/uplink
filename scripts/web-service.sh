#!/usr/bin/env bash
# Serves uplink-web for `devbox services up` (see process-compose.yaml) on
# http://127.0.0.1:${WEB_PORT:-8000}/. Builds it first (./make.sh web) when
# uplink-web/target/web/ has no build, or when a source file of uplink or
# uplink-web is newer than it.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

built=uplink-web/target/web/uplink-web.js
if [[ ! -f "$built" ]]; then
  echo "uplink-web: not built yet, building (takes a minute)"
  ./make.sh web -q
elif [[ -n "$(find uplink/src/main uplink/pom.xml uplink-web/src uplink-web/pom.xml -newer "$built" -print -quit)" ]]; then
  echo "uplink-web: sources changed since the last build, rebuilding"
  ./make.sh web -q
fi

exec ./make.sh web-serve
