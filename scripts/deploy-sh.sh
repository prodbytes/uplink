#!/usr/bin/env bash
# Deploys https://sh.uplink.nu01.com, which serves scripts/run.sh:
#   1. deploys the uplink-zone stack (infra/zone.yaml: the uplink.nu01.com
#      zone, delegated from nu01.com)
#   2. deploys the uplink-sh stack (infra/sh.yaml: certificate, bucket,
#      CloudFront, DNS)
#   3. uploads scripts/run.sh and invalidates the CloudFront cache
#   4. smoke-tests the live URL: / and /run.sh must return the uploaded
#      script byte for byte, and plain http must be refused
#
# Run by hand with credentials that can manage those stacks. Settings, from
# the environment:
#   AWS_REGION             default us-east-1 (CloudFront certificates live there)
#   PARENT_HOSTED_ZONE_ID  the Route 53 zone of nu01.com (default: looked up
#                          by name)
set -euo pipefail

cd "$(dirname "$0")/.."
export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="$AWS_REGION"
PARENT=nu01.com
ZONE=uplink.nu01.com
DOMAIN=sh.uplink.nu01.com
ZONE_STACK=uplink-zone
STACK=uplink-sh
SCRIPT=scripts/run.sh

if [[ -z "${PARENT_HOSTED_ZONE_ID:-}" ]]; then
  PARENT_HOSTED_ZONE_ID="$(aws route53 list-hosted-zones-by-name --dns-name "$PARENT." \
    --query "HostedZones[?Name=='$PARENT.' && Config.PrivateZone==\`false\`].Id | [0]" --output text)"
  PARENT_HOSTED_ZONE_ID="${PARENT_HOSTED_ZONE_ID#/hostedzone/}"
fi
if [[ ! "$PARENT_HOSTED_ZONE_ID" =~ ^Z[A-Z0-9]+$ ]]; then
  echo "error: no public Route 53 zone for $PARENT; set PARENT_HOSTED_ZONE_ID" >&2
  exit 1
fi

stack_output() { # stack_output <stack> <output key>
  aws cloudformation describe-stacks --stack-name "$1" \
    --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue" --output text
}

# 1. The zone
echo "==> deploying $ZONE_STACK ($ZONE, delegated from $PARENT_HOSTED_ZONE_ID)"
aws cloudformation deploy --stack-name "$ZONE_STACK" \
  --template-file infra/zone.yaml \
  --parameter-overrides "ZoneName=$ZONE" "ParentHostedZoneId=$PARENT_HOSTED_ZONE_ID" \
  --no-fail-on-empty-changeset
echo "    name servers: $(stack_output "$ZONE_STACK" NameServers)"

# 2. The site. The certificate's DNS validation needs the delegation above.
echo "==> deploying $STACK (https://$DOMAIN/)"
aws cloudformation deploy --stack-name "$STACK" \
  --template-file infra/sh.yaml \
  --parameter-overrides "DomainName=$DOMAIN" "ZoneStackName=$ZONE_STACK" \
  --no-fail-on-empty-changeset
bucket="$(stack_output "$STACK" ScriptBucketName)"
distribution="$(stack_output "$STACK" DistributionId)"
echo "    bucket: $bucket, distribution: $distribution"

# 3. The script. text/plain so a browser shows it rather than downloading
# it; edge caches keep it 5 minutes, and the invalidation clears them now.
echo "==> uploading $SCRIPT"
aws s3 cp "$SCRIPT" "s3://$bucket/run.sh" \
  --content-type "text/plain; charset=utf-8" --cache-control "public, max-age=300" \
  --only-show-errors
invalidation="$(aws cloudfront create-invalidation --distribution-id "$distribution" \
  --paths '/*' --query Invalidation.Id --output text)"
echo "    waiting for invalidation $invalidation"
aws cloudfront wait invalidation-completed --distribution-id "$distribution" --id "$invalidation"

# 4. Smoke test
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
