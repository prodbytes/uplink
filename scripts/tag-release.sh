#!/usr/bin/env bash
# Tags the current commit with the current version and a release kind, and
# pushes the tag, which starts the matching release workflow
# (.github/workflows/release-rc.yml or release-ga.yml: the CLI binaries) and
# deploy workflow (deploy-rc.yml to https://rc.uplink.nu01.com, or deploy.yml
# to https://uplink.nu01.com: uplink in the browser). Used by
# scripts/release-rc.sh and scripts/release-ga.sh:
# `bash scripts/tag-release.sh RC|GA`.
#
# The tag is X.Y.Z-<kind>: X and Y from version.X.txt and version.Y.txt, Z
# the current time (see scripts/version.sh). The workflow builds with that
# same version. DRY_RUN=1 checks and prints the tag without creating it.
set -euo pipefail

kind="${1:-}"
case "$kind" in
  RC | GA) ;;
  *) echo "usage: $0 RC|GA" >&2; exit 2 ;;
esac

cd "$(dirname "$0")/.."
source scripts/version.sh
tag="$VERSION-$kind"

if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "error: uncommitted changes; commit and push them first" >&2
  exit 1
fi
git fetch --quiet origin
if [[ -z "$(git branch --remotes --contains HEAD)" ]]; then
  echo "error: $(git rev-parse --short HEAD) isn't pushed; push it first" >&2
  exit 1
fi
if [[ "$kind" == GA ]] && ! git merge-base --is-ancestor HEAD origin/main; then
  echo "error: a GA release must be a commit on main (check out main and pull)" >&2
  exit 1
fi
if git rev-parse --quiet --verify "refs/tags/$tag" >/dev/null ||
  git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null; then
  echo "error: tag $tag already exists; try again in a minute" >&2
  exit 1
fi

echo "Tagging $(git rev-parse --short HEAD) as $tag"
if [[ "${DRY_RUN:-}" == 1 ]]; then
  echo "Dry run: not tagged."
  exit 0
fi
workflow="release-$(tr '[:upper:]' '[:lower:]' <<<"$kind").yml"
git tag --annotate "$tag" --message "uplink $tag"
git push --quiet origin "refs/tags/$tag"
repo_url="$(git remote get-url origin | sed -e 's#^git@github.com:#https://github.com/#' -e 's#\.git$##')"
if [[ "$kind" == GA ]]; then
  deploy=deploy.yml site=https://uplink.nu01.com/
else
  deploy=deploy-rc.yml site=https://rc.uplink.nu01.com/
fi
echo "Pushed $tag. The $workflow workflow publishes it as uplink-$tag:"
echo "  $repo_url/actions/workflows/$workflow"
echo "and $deploy deploys uplink in the browser to $site:"
echo "  $repo_url/actions/workflows/$deploy"
