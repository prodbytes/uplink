#!/bin/sh
# Downloads the latest uplink release for this machine and runs it with the
# given arguments:
#
#   curl -fsSL https://sh.uplink.nu01.com | sh -s -- https://example.com
#   curl -fsSL https://sh.uplink.nu01.com | sh -s -- ./public --mode=console
#   curl -fsSL https://sh.uplink.nu01.com | sh -s -- --help
#
# (https://sh.uplink.nu01.com serves this file at every path: infra/sh.cform.yaml,
# deployed by scripts/deploy-sh.sh.)
#
# The URL argument may also be a local directory, e.g. a static site's build
# output: it's served on 127.0.0.1 (with python3) for the length of the run
# and checked there. Like a URL, it may be followed by more sites to crawl,
# separated by , or ; (./public,example.com). Every other argument goes to
# uplink unchanged; see `uplink --help`.
#
# The binary, from the GitHub release, is checked against the release's
# SHA256SUMS and kept in ${XDG_CACHE_HOME:-~/.cache}/uplink/<tag>/, so later
# runs of the same release skip the download. Builds exist for Linux x64 and
# arm64 and macOS on Apple Silicon.
#
# Environment:
#   UPLINK_TAG  release tag to run (default: the latest GA release)
set -eu

REPO=prodbytes/uplink

say() { printf 'uplink: %s\n' "$*" >&2; }
die() {
  say "$*"
  exit 2
}

# The release asset's platform name; empty if there's no build for it.
platform() {
  case "$(uname -s)/$(uname -m)" in
    Linux/x86_64 | Linux/amd64) echo linux-x64 ;;
    Linux/aarch64 | Linux/arm64) echo linux-arm64 ;;
    Darwin/arm64) echo macos-arm64 ;;
  esac
}

# The latest GA tag, from the releases/latest redirect (not rate limited
# like the API).
latest_tag() {
  curl -fsSI "https://github.com/$REPO/releases/latest" |
    tr -d '\r' | sed -n 's|^[Ll]ocation: .*/releases/tag/\([A-Za-z0-9._-]*\)$|\1|p'
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# Downloads, verifies and extracts the binary into $1 unless it's there.
install_binary() {
  dir=$1 tag=$2 plat=$3
  [ -x "$dir/uplink" ] && return 0
  asset="uplink-$tag-$plat.tar.gz"
  base="https://github.com/$REPO/releases/download/$tag"
  mkdir -p "$(dirname "$dir")"
  tmp=$(mktemp -d "$dir.XXXXXX")
  say "downloading $asset"
  if ! curl -fsSL -o "$tmp/SHA256SUMS" "$base/SHA256SUMS" ||
    ! curl -fL --progress-bar -o "$tmp/$asset" "$base/$asset"; then
    rm -rf "$tmp"
    die "release $tag has no $asset"
  fi
  want=$(awk -v name="$asset" '$2 == name || $2 == "*" name { print $1; exit }' "$tmp/SHA256SUMS")
  if [ -z "$want" ] || [ "$(sha256_of "$tmp/$asset")" != "$want" ]; then
    rm -rf "$tmp"
    die "checksum mismatch for $asset; not running it"
  fi
  if ! tar -xzf "$tmp/$asset" -C "$tmp" uplink; then
    rm -rf "$tmp"
    die "couldn't extract $asset"
  fi
  rm -f "$tmp/$asset" "$tmp/SHA256SUMS"
  chmod 0755 "$tmp/uplink"
  rm -rf "$dir"
  mv "$tmp" "$dir"
}

# Serves directory $1 on 127.0.0.1 in the background; sets server_pid and
# server_url.
serve_dir() {
  command -v python3 >/dev/null 2>&1 || die "checking a directory needs python3"
  portfile=$(mktemp)
  python3 - "$1" "$portfile" >/dev/null 2>&1 <<'EOF' &
import functools, http.server, sys
root, portfile = sys.argv[1], sys.argv[2]
handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=root)
server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
with open(portfile, "w") as f:
    f.write(str(server.server_address[1]))
server.serve_forever()
EOF
  server_pid=$!
  trap 'kill "$server_pid" 2>/dev/null || true' EXIT
  trap 'exit 130' INT TERM
  port=
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    port=$(cat "$portfile")
    [ -n "$port" ] && break
    sleep 0.25
  done
  rm -f "$portfile"
  [ -n "$port" ] || die "couldn't serve $1"
  server_url="http://127.0.0.1:$port/"
  say "serving $1 at $server_url"
}

main() {
  plat=$(platform)
  [ -n "$plat" ] || die "no uplink build for $(uname -s) $(uname -m)"
  tag=${UPLINK_TAG:-$(latest_tag)}
  case "$tag" in
    "") die "couldn't find the latest release of $REPO" ;;
    *[!A-Za-z0-9._-]*) die "invalid release tag: $tag" ;;
  esac
  dir="${XDG_CACHE_HOME:-$HOME/.cache}/uplink/$tag"
  install_binary "$dir" "$tag" "$plat"

  # Rebuild the arguments, swapping a directory at the start of the URL
  # argument (before any , or ; and the sites after it) for the address it's
  # served at. Options whose value can be the next argument
  # are skipped over, so their value is never taken for the URL.
  server_pid='' n=$# value_next='' positional='' found=''
  while [ "$n" -gt 0 ]; do
    arg=$1
    shift
    n=$((n - 1))
    if [ -n "$value_next" ]; then
      value_next=
    elif [ -z "$positional" ] && [ "$arg" = -- ]; then
      positional=1
    elif [ -z "$positional" ] && case "$arg" in -*) true ;; *) false ;; esac; then
      case "$arg" in
        -c | -t | --concurrency | --max-in-flight | --timeout | --max-pages | \
          --summary-interval | --interval | --slow | --mode) value_next=1 ;;
      esac
    elif [ -z "$found" ]; then
      found=1
      target=${arg%%[,;]*}
      if [ -n "$target" ] && [ -d "$target" ]; then
        serve_dir "$target"
        arg=$server_url${arg#"$target"}
      fi
    fi
    set -- "$@" "$arg"
  done

  # `curl | sh` leaves stdin on the pipe; the dashboard needs the terminal.
  if [ -t 1 ] && [ ! -t 0 ] && (: </dev/tty) 2>/dev/null; then
    exec <>/dev/tty
  fi
  if [ -z "$server_pid" ]; then
    exec "$dir/uplink" "$@"
  fi
  # Ctrl+C stops uplink, which prints its report; keep its exit status.
  trap : INT TERM
  status=0
  "$dir/uplink" "$@" || status=$?
  exit "$status"
}

# Everything runs from here, so a truncated download runs nothing.
main "$@"
