#!/usr/bin/env bash
# Deploys uplink in the browser (uplink-web) to production,
# https://uplink.nu01.com, or with STAGE=rc to the release-candidate site,
# https://rc.uplink.nu01.com (its own stack, uplink-rc-web):
#   1. builds the site (./make.sh web -> uplink-web/target/web/) for this version
#   2. deploys, as prodbytes/dsp's gitops does, one stack per piece, each
#      importing the previous one's exports (all prefixed with the tenant,
#      uplink or uplink-rc):
#        <tenant>-acm-cert  infra/acm-cert.cform.yaml: the certificate, in the
#                           uplink-zone stack's zone (infra/zone.cform.yaml)
#        <tenant>-web       infra/site.cform.yaml: the bucket, CloudFront with
#                           origin access control, the alias records, /health
#                           and the Route 53 health checks of / and /health,
#                           which alarm to the --emails addresses
#   3. uploads the site and invalidates the CloudFront cache
#   4. smoke-tests the live site: / must carry this version, the
#      WebAssembly module and its loader must be served (the module as
#      application/wasm, which browsers require), and /health must pass
#
# Usage: scripts/deploy.sh [--emails a@example.com,b@example.com]
#   --emails     who gets the health alarms, comma-separated (default:
#                $HEALTH_EMAILS, else julio+health@nu01.com). Each address
#                must confirm the subscription email AWS sends it.
#
# Run by .github/workflows/deploy.yml on *GA tags and deploy-rc.yml on *RC*
# tags, or by hand with credentials that can manage the stack. Settings,
# from the environment:
#   TAG          the release tag, X.Y.Z-RC or X.Y.Z-GA: its X.Y must match the
#                version files and its Z becomes the build's Z (default:
#                none, so Z is the current time)
#   STAGE        prod (default) or rc
#   AWS_REGION   default us-east-1 (CloudFront certificates live there)
#   SKIP_BUILD   1 to deploy an existing uplink-web/target/web/ of this version
#   HEALTH_EMAILS  the default of --emails
# The build needs Oracle GraalVM 25.3+ and binaryen (see make.sh); the rest,
# the AWS CLI, curl and python3.
set -euo pipefail

HEALTH_EMAILS="${HEALTH_EMAILS:-julio+health@nu01.com}"
while (($#)); do
  case "$1" in
    --emails) [[ $# -ge 2 ]] || { echo "error: --emails needs a value" >&2; exit 2; }
      HEALTH_EMAILS="$2"; shift 2 ;;
    --emails=*) HEALTH_EMAILS="${1#--emails=}"; shift ;;
    -h | --help) sed -n '2,/^set -euo/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "error: unknown argument '$1' (see --help)" >&2; exit 2 ;;
  esac
done
IFS=, read -r -a emails <<<"$HEALTH_EMAILS"
((${#emails[@]})) || { echo "error: --emails is empty" >&2; exit 2; }
for email in "${emails[@]}"; do
  [[ "$email" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] \
    || { echo "error: '$email' isn't an email address (--emails)" >&2; exit 2; }
done

cd "$(dirname "$0")/.."
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="$AWS_REGION"
STAGE="${STAGE:-prod}"
case "$STAGE" in
  prod) TENANT_ID=uplink DOMAIN=uplink.nu01.com ;;
  rc) TENANT_ID=uplink-rc DOMAIN=rc.uplink.nu01.com ;;
  *) echo "error: STAGE must be prod or rc (got '$STAGE')" >&2; exit 2 ;;
esac
ZONE_STACK=uplink-zone
CERT_STACK=$TENANT_ID-acm-cert
SITE_STACK=$TENANT_ID-web
WEB=uplink-web/target/web

if [[ "${TAG:-}" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-.*)?$ ]]; then
  export VERSION_Z="${BASH_REMATCH[3]}"
  tag_xy="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}"
elif [[ -n "${TAG:-}" ]]; then
  echo "error: TAG '$TAG' isn't X.Y.Z[-suffix]" >&2
  exit 2
fi
source scripts/version.sh
if [[ -n "${tag_xy:-}" && "$tag_xy" != "$VERSION_X.$VERSION_Y" ]]; then
  echo "error: tag $TAG is version $tag_xy, but version.X.txt/version.Y.txt say $VERSION_X.$VERSION_Y" >&2
  exit 1
fi
echo "==> deploying uplink-web $VERSION to https://$DOMAIN/ ($STAGE, $AWS_REGION)"

deploy() { # deploy <stack> <template> <parameter overrides...>
  local stack="$1" template="$2"
  shift 2
  echo "==> deploying $stack"
  aws cloudformation deploy --stack-name "$stack" --template-file "$template" \
    --no-fail-on-empty-changeset --capabilities CAPABILITY_AUTO_EXPAND --parameter-overrides "$@"
}

stack_output() { # stack_output <stack> <output key>
  aws cloudformation describe-stacks --stack-name "$1" \
    --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue | [0]" --output text
}

# The version index.html was built with, from <meta name="uplink-version" content="...">.
page_version() {
  sed -n 's/.*name="uplink-version" content="\([^"]*\)".*/\1/p' | head -1
}

# 1. The site
if [[ "${SKIP_BUILD:-}" != 1 ]]; then
  echo "==> building uplink-web"
  ./make.sh web -q
fi
for file in index.html app.js style.css uplink-web.js uplink-web.js.wasm; do
  test -s "$WEB/$file" || { echo "error: $WEB/$file is missing; build with ./make.sh web" >&2; exit 1; }
done
built="$(page_version < "$WEB/index.html")"
if [[ "$built" != "$VERSION" ]]; then
  echo "error: $WEB is version $built, not $VERSION" >&2
  exit 1
fi

# 2. The stacks. A new certificate's DNS validation takes a few minutes.
deploy "$CERT_STACK" infra/acm-cert.cform.yaml \
  "TenantId=$TENANT_ID" "DomainName=$DOMAIN" "ZoneStackName=$ZONE_STACK"
echo "    certificate: $(stack_output "$CERT_STACK" CertificateArn)"
deploy "$SITE_STACK" infra/site.cform.yaml \
  "TenantId=$TENANT_ID" "ZoneStackName=$ZONE_STACK" "NotificationEmails=$HEALTH_EMAILS"
echo "    health alarms to $HEALTH_EMAILS (new addresses must confirm AWS's email)"
bucket="$(stack_output "$SITE_STACK" SiteBucketName)"
distribution="$(stack_output "$SITE_STACK" DistributionId)"
echo "    bucket: $bucket, distribution: $distribution"

# 3. The content. No file name is content-hashed, so browsers revalidate
# everything (no-cache); CloudFront is invalidated below. The module goes up
# on its own with its media type, which browsers need to compile it streaming.
echo "==> uploading"
aws s3 sync "$WEB/" "s3://$bucket/" --delete --exclude '*.wasm' \
  --cache-control no-cache --only-show-errors
aws s3 cp "$WEB/uplink-web.js.wasm" "s3://$bucket/uplink-web.js.wasm" \
  --cache-control no-cache --content-type application/wasm --only-show-errors
invalidation="$(aws cloudfront create-invalidation --distribution-id "$distribution" \
  --paths '/*' --query Invalidation.Id --output text)"
echo "    waiting for invalidation $invalidation"
aws cloudfront wait invalidation-completed --distribution-id "$distribution" --id "$invalidation"

# 4. Smoke test (retried: on a first deploy DNS and the edge take a moment)
echo "==> checking https://$DOMAIN/"
check() {
  local live type
  live="$(curl -fsS --max-time 20 "https://$DOMAIN/?deploy=$VERSION_Z" | page_version)" || return 1
  [[ "$live" == "$VERSION" ]] || { echo "    / is version '${live:-?}', want $VERSION"; return 1; }
  curl -fsS --max-time 20 -o /dev/null "https://$DOMAIN/uplink-web.js" || { echo "    /uplink-web.js failed"; return 1; }
  type="$(curl -fsS --max-time 60 -o /dev/null -w '%{content_type}' "https://$DOMAIN/uplink-web.js.wasm")" \
    || { echo "    /uplink-web.js.wasm failed"; return 1; }
  [[ "$type" == application/wasm* ]] || { echo "    /uplink-web.js.wasm is $type, want application/wasm"; return 1; }
  [[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "http://$DOMAIN/")" == 301 ]] \
    || { echo "    plain http isn't redirected to https"; return 1; }
  # Cached up to 30 s, so a failure shows a little after it is fixed.
  local health
  health="$(curl -sS --max-time 30 "https://$DOMAIN/health")" || { echo "    /health failed"; return 1; }
  grep -q '"status": "ok"' <<<"$health" || { echo "    /health is not ok:"; sed 's/^/      /' <<<"$health"; return 1; }
}
for attempt in $(seq 1 30); do
  if check; then
    echo "==> https://$DOMAIN/ serves uplink-web $VERSION"
    exit 0
  fi
  echo "    not yet (attempt $attempt/30); retrying in 20 s"
  sleep 20
done
echo "error: https://$DOMAIN/ didn't serve uplink-web $VERSION" >&2
exit 1
