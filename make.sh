#!/usr/bin/env bash
# Builds uplink. The Makefile delegates every target here.
#
#   ./make.sh [build]    # native executable -> target/uplink (default)
#   ./make.sh jvm        # JVM build -> target/quarkus-app/
#   ./make.sh test       # unit + end-to-end tests
#   ./make.sh install    # build native and install as "uplink" (see scripts/install.sh)
#   ./make.sh clean      # remove build output
#
# Extra arguments after the target are passed through to Maven, e.g.
#   ./make.sh test -Dtest=UplinkCommandTest
#
# Builds are versioned X.Y.Z: X and Y from version.X.txt and version.Y.txt, Z
# the build time as YYYYMMDDHHMM (UTC); set VERSION_Z to override it (see
# scripts/version.sh). `uplink --version` prints it.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$repo_root"

source scripts/version.sh
mvn=(./mvnw -B "-Drevision=$VERSION")

usage() {
  sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

target="${1:-build}"
if [[ $# -gt 0 ]]; then
  shift
fi

case "$target" in
  build)   "${mvn[@]}" package -Dnative -DskipTests "$@" ;;
  jvm)     "${mvn[@]}" package -DskipTests "$@" ;;
  test)    "${mvn[@]}" test "$@" ;;
  install) scripts/install.sh ;;
  clean)   "${mvn[@]}" clean "$@" ;;
  help|-h|--help) usage ;;
  *)
    echo "make.sh: unknown target '$target'" >&2
    usage >&2
    exit 2
    ;;
esac
