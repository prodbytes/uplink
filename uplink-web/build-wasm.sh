#!/usr/bin/env bash
# Compiles uplink-web to WebAssembly with GraalVM Web Image. Maven runs it in the
# package phase (see pom.xml):
#
#   build-wasm.sh <classpath> <output dir>
#
# Needs Oracle GraalVM 25.3+ with the Web Image tool (lib/svm/tools/svm-wasm),
# found through GRAALVM_HOME or else JAVA_HOME, and binaryen's wasm-as on PATH
# (devbox.json installs it). Set WEB_IMAGE_QUICK=1 for a faster, less optimized
# build (-Ob) while developing.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: build-wasm.sh <classpath> <output dir>" >&2
  exit 2
fi
classpath="$1"
out_dir="$2"

graalvm="${GRAALVM_HOME:-${JAVA_HOME:-}}"
if [[ -z "$graalvm" || ! -d "$graalvm/lib/svm/tools/svm-wasm" ]]; then
  echo "build-wasm: no GraalVM with Web Image at '${graalvm:-<unset>}'." >&2
  echo "build-wasm: set GRAALVM_HOME to Oracle GraalVM 25.3 or later (GraalVM CE does not ship lib/svm/tools/svm-wasm)." >&2
  exit 1
fi
if ! command -v wasm-as >/dev/null 2>&1; then
  echo "build-wasm: wasm-as not found; install binaryen (it is in devbox.json: run inside 'devbox shell')." >&2
  exit 1
fi

flags=(--tool:svm-wasm --enable-url-protocols=http,https)
if [[ "${WEB_IMAGE_QUICK:-}" == 1 ]]; then
  flags+=(-Ob)
fi

mkdir -p "$out_dir"
"$graalvm/bin/native-image" "${flags[@]}" -cp "$classpath" org.uplink.web.Main -o "$out_dir/uplink-web"

# The text form of the module is an intermediate of the build (well over 100MB),
# not something the page loads.
rm -f "$out_dir/uplink-web.js.wat"
ls -l "$out_dir"
