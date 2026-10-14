#!/usr/bin/env bash
# Deploys uplink in the browser (uplink-web) to production,
# https://uplink.nu01.com, or with STAGE=rc to the release-candidate site,
# https://rc.uplink.nu01.com (its own stack, uplink-rc-web):
#   1. builds the site (./make.sh web -> uplink-web/target/web/) for this version
#   2. deploys, as prodbytes/dsp's gitops does, one stack per piece (all
#      prefixed with the tenant, uplink or uplink-rc):
#        <tenant>-acm-cert  infra/acm-cert.cform.yaml: the certificate,
#                           validated in the apex zone nu01.com
#        <tenant>-web       infra/site.cform.yaml: the bucket, CloudFront with
#                           origin access control (given the certificate's
#                           ARN) and /health (checked every 5 minutes by the
#                           central nu01-monitor stack in the nu01 repo, which
#                           emails julio+health@nu01.com)
#      The DNS records (aliases of uplink.nu01.com and rc.uplink.nu01.com in
#      nu01.com) are in the nu01 repo's nu01-dns stack, not deployed here.
#      When the certificate is replaced, the old one is deleted once the
#      site stack has moved CloudFront to the new one.
#   3. uploads the site and invalidates the CloudFront cache
#   4. smoke-tests the live site: / must carry this version, the
#      WebAssembly module and its loader must be served (the module as
#      application/wasm, which browsers require), and /health must pass
#
# Usage: scripts/deploy.sh
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
# The build needs Oracle GraalVM 25.3+ and binaryen (see make.sh); the rest,
# the AWS CLI, curl and python3.
set -euo pipefail

while (($#)); do
  case "$1" in
    -h | --help) sed -n '2,/^set -euo/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "error: unknown argument '$1' (see --help)" >&2; exit 2 ;;
  esac
done

cd "$(dirname "$0")/.."
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="$AWS_REGION"
STAGE="${STAGE:-prod}"
case "$STAGE" in
  prod) TENANT_ID=uplink TENANCY=GA DOMAIN=uplink.nu01.com ;;
  rc) TENANT_ID=uplink-rc TENANCY=RC DOMAIN=rc.uplink.nu01.com ;;
  *) echo "error: STAGE must be prod or rc (got '$STAGE')" >&2; exit 2 ;;
esac
# The apex zone nu01.com (nu01 repo, stack nu01-route53-zone), where the
# certificates validate.
VALIDATION_ZONE_ID=Z04838091YTBIM6PNI1XK
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

# The project and tenancy tags (cost allocation) propagate to every resource.
deploy() { # deploy <stack> <template> <parameter overrides...>
  local stack="$1" template="$2"
  shift 2
  echo "==> deploying $stack"
  aws cloudformation deploy --stack-name "$stack" --template-file "$template" \
    --no-fail-on-empty-changeset \
    --tags project=uplink "tenancy=$TENANCY" --parameter-overrides "$@"
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
# The site stack takes the certificate's ARN as a parameter: an import would
# keep CloudFormation from replacing the certificate (a changed export value
# that another stack imports is refused).
old_cert="$(stack_output "$CERT_STACK" CertificateArn 2>/dev/null || true)"
[[ "$old_cert" == arn:* ]] || old_cert=
# Transition, once per stage (the first deploy after the subzone merge): the
# deployed site stack still imports <tenant>-CertificateArn, which the cert
# stack no longer exports. Release the import first, with the certificate it
# already uses.
if [[ -n "$old_cert" ]] && aws cloudformation list-imports --export-name "$TENANT_ID-CertificateArn" \
  --query 'Imports[]' --output text 2>/dev/null | tr '\t' '\n' | grep -x "$SITE_STACK" >/dev/null; then
  echo "    $SITE_STACK imports $TENANT_ID-CertificateArn: switching it to a parameter first"
  deploy "$SITE_STACK" infra/site.cform.yaml "TenantId=$TENANT_ID" "CertificateArn=$old_cert"
fi
deploy "$CERT_STACK" infra/acm-cert.cform.yaml \
  "TenantId=$TENANT_ID" "DomainName=$DOMAIN" "ValidationZoneId=$VALIDATION_ZONE_ID"
cert="$(stack_output "$CERT_STACK" CertificateArn)"
echo "    certificate: $cert"
deploy "$SITE_STACK" infra/site.cform.yaml "TenantId=$TENANT_ID" "CertificateArn=$cert"
# A replaced certificate is retained by the cert stack (UpdateReplacePolicy),
# since CloudFront used it until the deploy above; delete it now.
if [[ -n "$old_cert" && "$old_cert" != "$cert" ]]; then
  echo "    deleting the replaced certificate $old_cert"
  aws acm delete-certificate --certificate-arn "$old_cert" \
    || echo "    warning: couldn't delete $old_cert; delete it by hand once nothing uses it" >&2
fi
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
