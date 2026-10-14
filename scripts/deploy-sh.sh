#!/usr/bin/env bash
# Deploys https://sh.uplink.nu01.com, which serves scripts/run.sh:
#   1. deploys the uplink-sh stack (infra/sh.cform.yaml: certificate,
#      validated in the apex zone nu01.com, bucket, CloudFront)
#   2. uploads scripts/run.sh and invalidates the CloudFront cache
#   3. smoke-tests the live URL: / and /run.sh must return the uploaded
#      script byte for byte, and plain http must be refused
#
# The A/AAAA records of sh.uplink.nu01.com are in the nu01 repo's nu01-dns
# stack (parameter UplinkShDistribution); if the distribution is ever
# replaced, redeploy nu01-dns with the new DistributionDomainName output.
# The stack is tagged project=uplink tenancy=GA for cost allocation.
#
# Run by hand with credentials that can manage those stacks. Settings, from
# the environment:
#   AWS_REGION             default us-east-1 (CloudFront certificates live there)
#   VALIDATION_ZONE_ID     the Route 53 zone of nu01.com, where the
#                          certificate validates (default: looked up by name;
#                          Z04838091YTBIM6PNI1XK)
set -euo pipefail

cd "$(dirname "$0")/.."
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="$AWS_REGION"
PARENT=nu01.com
DOMAIN=sh.uplink.nu01.com
STACK=uplink-sh
SCRIPT=scripts/run.sh

if [[ -z "${VALIDATION_ZONE_ID:-}" ]]; then
  VALIDATION_ZONE_ID="$(aws route53 list-hosted-zones-by-name --dns-name "$PARENT." \
    --query "HostedZones[?Name=='$PARENT.' && Config.PrivateZone==\`false\`].Id | [0]" --output text)"
  VALIDATION_ZONE_ID="${VALIDATION_ZONE_ID#/hostedzone/}"
fi
if [[ ! "$VALIDATION_ZONE_ID" =~ ^Z[A-Z0-9]+$ ]]; then
  echo "error: no public Route 53 zone for $PARENT; set VALIDATION_ZONE_ID" >&2
  exit 1
fi

stack_output() { # stack_output <stack> <output key>
  aws cloudformation describe-stacks --stack-name "$1" \
    --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue" --output text
}

# 1. The site. Changing the validation zone replaces the certificate (see
# infra/sh.cform.yaml); CloudFormation swaps it into CloudFront itself.
echo "==> deploying $STACK (https://$DOMAIN/, certificate validated in $VALIDATION_ZONE_ID)"
aws cloudformation deploy --stack-name "$STACK" \
  --template-file infra/sh.cform.yaml \
  --parameter-overrides "DomainName=$DOMAIN" "ValidationZoneId=$VALIDATION_ZONE_ID" \
  --tags project=uplink tenancy=GA --no-fail-on-empty-changeset
bucket="$(stack_output "$STACK" ScriptBucketName)"
distribution="$(stack_output "$STACK" DistributionId)"
echo "    bucket: $bucket, distribution: $distribution ($(stack_output "$STACK" DistributionDomainName))"

# 2. The script. text/plain so a browser shows it rather than downloading
# it; edge caches keep it 5 minutes, and the invalidation clears them now.
echo "==> uploading $SCRIPT"
aws s3 cp "$SCRIPT" "s3://$bucket/run.sh" \
  --content-type "text/plain; charset=utf-8" --cache-control "public, max-age=300" \
  --only-show-errors
invalidation="$(aws cloudfront create-invalidation --distribution-id "$distribution" \
  --paths '/*' --query Invalidation.Id --output text)"
echo "    waiting for invalidation $invalidation"
aws cloudfront wait invalidation-completed --distribution-id "$distribution" --id "$invalidation"

# 3. Smoke test
echo "==> checking https://$DOMAIN/"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for path in / /run.sh; do
  ok=
  for _ in $(seq 1 30); do # DNS for a new zone and record can take a while
    if curl -fsS -o "$tmp/got" "https://$DOMAIN$path" 2>/dev/null && cmp -s "$tmp/got" "$SCRIPT"; then
      ok=1
      break
    fi
    sleep 10
  done
  if [[ -z "$ok" ]]; then
    echo "error: https://$DOMAIN$path doesn't serve $SCRIPT" >&2
    exit 1
  fi
  echo "    $path: ok"
done
status="$(curl -s -o /dev/null -w '%{http_code}' "http://$DOMAIN/")"
if [[ "$status" != 403 ]]; then
  echo "error: http://$DOMAIN/ answered $status, not 403" >&2
  exit 1
fi
echo "    http: refused (403)"
echo "==> done: curl -fsSL https://$DOMAIN | sh -s -- <url or directory>"
