#!/usr/bin/env bash
# Builds uplink (uplink/: library and CLI) and uplink-web (uplink-web/: the
# browser version). The Makefile delegates every target here.
#
#   ./make.sh [build]    # native executable -> uplink/target/uplink (default)
#   ./make.sh jvm        # JVM build -> uplink/target/quarkus-app/
#   ./make.sh web        # browser version -> uplink-web/target/web/
#   ./make.sh web-serve  # serve uplink-web/target/web/ on http://127.0.0.1:8000 (WEB_PORT)
#   ./make.sh all        # native executable and browser version
#   ./make.sh test       # unit + end-to-end tests
#   ./make.sh install    # build native and install as "uplink" (see scripts/install.sh)
#   ./make.sh clean      # remove build output of both
#
# "web" needs Oracle GraalVM 25.3+, which ships Web Image (GraalVM CE does not):
# GRAALVM_HOME or JAVA_HOME, else the newest found in SDKMAN! or /Library/Java.
# Set WEB_IMAGE_QUICK=1 for a faster, less optimized Wasm build.
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
  sed -n '2,23p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# A JDK with Web Image: GRAALVM_HOME, JAVA_HOME, or else the newest one installed
# with SDKMAN! or in /Library/Java (devbox's JAVA_HOME is GraalVM CE, which lacks it).
web_jdk() {
  local candidate
  for candidate in "${GRAALVM_HOME:-}" "${JAVA_HOME:-}"; do
    if [[ -n "$candidate" && -d "$candidate/lib/svm/tools/svm-wasm" ]]; then
      echo "$candidate"
      return
    fi
  done
  for candidate in $(ls -d "${SDKMAN_DIR:-$HOME/.sdkman}"/candidates/java/*/ \
      /Library/Java/JavaVirtualMachines/*/Contents/Home/ 2>/dev/null | sort -rV); do
    if [[ -d "$candidate/lib/svm/tools/svm-wasm" ]]; then
      echo "${candidate%/}"
      return
    fi
  done
}

# The browser version.
web() {
  local jdk
  jdk="$(web_jdk)"
  if [[ -z "$jdk" ]]; then
    echo "make.sh: no JDK with GraalVM Web Image found; install Oracle GraalVM 25.3+ and set GRAALVM_HOME" >&2
    exit 1
  fi
  JAVA_HOME="$jdk" "${mvn[@]}" -Pweb package -pl uplink-web -am -DskipTests "$@"
}

target="${1:-build}"
if [[ $# -gt 0 ]]; then
  shift
fi

case "$target" in
  build)   "${mvn[@]}" package -Dnative -DskipTests "$@" ;;
  jvm)     "${mvn[@]}" package -DskipTests "$@" ;;
  web)     web "$@" ;;
  web-serve)
    if [[ ! -f uplink-web/target/web/uplink-web.js ]]; then
      echo "make.sh: uplink-web/target/web/ is not built; run ./make.sh web first" >&2
      exit 1
    fi
    port="${WEB_PORT:-8000}"
    echo "uplink-web: http://127.0.0.1:$port/"
    python3 -m http.server "$port" --bind 127.0.0.1 --directory uplink-web/target/web
    ;;
  all)     "${mvn[@]}" package -Dnative -DskipTests "$@" && web "$@" ;;
  test)    "${mvn[@]}" test "$@" ;;
  install) scripts/install.sh ;;
  clean)   "${mvn[@]}" -Pweb clean "$@" ;;
  help|-h|--help) usage ;;
  *)
    echo "make.sh: unknown target '$target'" >&2
    usage >&2
    exit 2
    ;;
esac
