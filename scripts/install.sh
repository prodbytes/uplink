#!/usr/bin/env bash
# Builds the uplink native executable and installs it as "uplink".
#
#   scripts/install.sh                      # installs into ~/.local/bin
#   PREFIX=/opt/homebrew/bin scripts/install.sh
#   SKIP_BUILD=1 scripts/install.sh         # reuse an existing target/uplink
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
prefix="${PREFIX:-$HOME/.local/bin}"
binary="$repo_root/target/uplink"

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  "$repo_root/make.sh" build -q
fi

if [[ ! -x "$binary" ]]; then
  echo "install: $binary not found; build it with ./mvnw package -Dnative" >&2
  exit 1
fi

mkdir -p "$prefix"
install -m 0755 "$binary" "$prefix/uplink"
echo "Installed $prefix/uplink"

case ":$PATH:" in
  *":$prefix:"*) ;;
  *) echo "Note: $prefix is not on your PATH" >&2 ;;
esac
