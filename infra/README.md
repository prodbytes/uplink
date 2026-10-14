# infra

CloudFormation for uplink's sites, all in us-east-1 (CloudFront
certificates must live there). DNS is not here: see [DNS](#dns).

| Template | Stack | What | Deployed by |
|----------|-------|------|-------------|
| [sh.cform.yaml](sh.cform.yaml) | `uplink-sh` | https://sh.uplink.nu01.com, the `curl \| sh` run script: certificate (validated in `nu01.com`), bucket, CloudFront | [scripts/deploy-sh.sh](../scripts/deploy-sh.sh), by hand |
| [acm-cert.cform.yaml](acm-cert.cform.yaml) | `uplink-acm-cert`, `uplink-rc-acm-cert` | The certificate of uplink.nu01.com / rc.uplink.nu01.com, validated in `nu01.com`; outputs `CertificateArn`, exports `<tenant>-CertificateDomainName` | [scripts/deploy.sh](../scripts/deploy.sh) |
| [site.cform.yaml](site.cform.yaml) | `uplink-web`, `uplink-rc-web` | https://uplink.nu01.com (GA) and https://rc.uplink.nu01.com (RC): S3 bucket, CloudFront with origin access control, [`/health`](#health-checks); takes the certificate's ARN as a parameter, imports its domain name and the health function's role | [scripts/deploy.sh](../scripts/deploy.sh), from [deploy.yml](../.github/workflows/deploy.yml) on `*GA` tags and [deploy-rc.yml](../.github/workflows/deploy-rc.yml) (`STAGE=rc`) on `*RC*` tags |
| [github-deploy.cform.yaml](github-deploy.cform.yaml) | `uplink-github-deploy` | The IAM roles those two workflows assume through GitHub's OIDC, and the roles the `/health` functions run as; exports `<tenant>-HealthFunctionRoleArn` | by hand, once |

The layout follows prodbytes/dsp's gitops: one stack per piece
(certificate, site), all in us-east-1 (CloudFront certificates must live
there, and imports only resolve within a region). The certificate's ARN goes
to the site stack as a parameter rather than an import, so the certificate
can be replaced (CloudFormation refuses to change an imported export). The tenant (`uplink` for production, `uplink-rc`
for the release candidate) prefixes the stack names and exports and tags
the resources. For cost allocation every stack also carries the stack tags
`project=uplink` and `tenancy` (`GA` for `uplink-web`, `uplink-acm-cert` and
`uplink-sh`, `RC` for the `uplink-rc-` stacks, `shared` for
`uplink-github-deploy`), which CloudFormation propagates to their resources.

## Health checks

Each site stack has:

- **`/health`**: a Lambda function behind the distribution, through its
  function URL, which only CloudFront may call (origin access control).
  It checks what the site and the CLI need, and answers JSON: 200 with
  `"status": "ok"` when every check passes, 503 with `"status": "fail"`
  otherwise, with each check's result:

  | Check | Passes when |
  |-------|-------------|
  | `files` | `index.html`, `app.js`, `style.css`, `uplink-web.js` and `uplink-web.js.wasm` are in the bucket, the module as `application/wasm` |
  | `certificate` | the site's ACM certificate is issued and valid for 14 more days |
  | `site` | `https://<domain>/` serves uplink-web through CloudFront |
  | `run_script` | https://sh.uplink.nu01.com serves the `curl \| sh` run script |
  | `github_release` | the repository has a latest release, which the run script downloads |

  A result is reused for 30 s and CloudFront caches it as long, whatever
  the query string, so neither monitors nor visitors can multiply
  the calls to GitHub or the function.

Monitoring and alerting are not in this repository: the central
`nu01-monitor` stack (in the nu01 repo) checks every nu01 project's
endpoints, including `/` and `/health` of both sites, every 5 minutes and
emails `julio+health@nu01.com` on failure. The site stacks used to have
their own Route 53 health checks, CloudWatch alarms and `<tenant>-health`
SNS topic; those were removed to cut cost, and the next deploy of each
site stack deletes them (the deploy roles keep the permissions for that
until then).

The functions' roles live in the `uplink-github-deploy` stack, deployed by
hand, so the deploy roles only pass them and never create IAM roles. Update
that stack before the first deploy that brings `/health`, or the
site stack cannot import `<tenant>-HealthFunctionRoleArn`.

```bash
curl -s https://uplink.nu01.com/health
```

## Letting GitHub Actions deploy

The deploy workflows hold no AWS keys: they exchange GitHub's OIDC token
for a role from [github-deploy.cform.yaml](github-deploy.cform.yaml). Production's
role trusts only `*GA` tag runs; the RC role trusts `*RC*` tag runs and
manual runs from `main`. Each may manage only its own stacks and, in the
`nu01.com` zone, change only its own name's certificate-validation record
(`_<32 hex>.uplink.nu01.com` or `_<32 hex>.rc.uplink.nu01.com`). Until
round 2 (below) has deployed on both stages they may also change their own
records in the old `uplink.nu01.com` subzone, so CloudFormation can delete
the site records from it.
The account's GitHub OIDC provider already exists (presence created it),
so the template doesn't create another unless `CreateOidcProvider=true`.

Deploy it once with admin credentials, then set the role ARNs as
repository variables:

```bash
aws cloudformation deploy --region us-east-1 --stack-name uplink-github-deploy \
  --template-file infra/github-deploy.cform.yaml --capabilities CAPABILITY_NAMED_IAM \
  --tags project=uplink tenancy=shared
out() { aws cloudformation describe-stacks --region us-east-1 --stack-name uplink-github-deploy \
  --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text; }
gh variable set AWS_DEPLOY_ROLE_ARN -R prodbytes/uplink --body "$(out DeployRoleArn)"
gh variable set AWS_DEPLOY_RC_ROLE_ARN -R prodbytes/uplink --body "$(out RcDeployRoleArn)"
```

The repository uses GitHub's immutable OIDC subjects
(`repo:prodbytes@288014477/uplink@1404673480:ref:...`); the roles trust
that form and the `owner/name` one.

## DNS

DNS for uplink lives in the nu01 repo, in the apex zone `nu01.com`
(`Z04838091YTBIM6PNI1XK`, stack `nu01-route53-zone`): the stack `nu01-dns`
owns the A/AAAA aliases of `uplink.nu01.com`, `rc.uplink.nu01.com` and
`sh.uplink.nu01.com` (its `UplinkDistribution`, `UplinkRcDistribution` and
`UplinkShDistribution` parameters are the stacks' `DistributionDomainName`
outputs; redeploy it there if a distribution is ever replaced). The
certificates here validate in `nu01.com`, and CloudFormation writes their
`_<token>` CNAMEs there.

Until October 2026 uplink had its own delegated zone, `uplink.nu01.com`
(`Z04348662JLR2LAZSOI74`, stack `uplink-zone`, template `zone.cform.yaml`,
now removed). It was merged into `nu01.com` to save a zone's monthly fee,
in two rounds:

1. **Round 1** (nu01 repo, admin): `scripts/migrate-subzone.sh uplink`
   deletes the delegation from `nu01.com`, copies the validation CNAMEs
   there and deploys `nu01-dns` with the aliases. It leaves the subzone
   alone, since resolvers may cache its NS record for 48 hours, and prints
   the earliest time for round 2.
2. **Round 2** (this repository): the stacks stop writing to the subzone
   and validate their certificates in `nu01.com`. **Never release it
   before round 1 + 48 h (the time the migrate script printed)**, and
   check that round 1 happened: `nu01.com` must hold no `uplink.nu01.com`
   NS record and must answer the aliases:

   ```bash
   aws route53 list-resource-record-sets --hosted-zone-id Z04838091YTBIM6PNI1XK \
     --query "ResourceRecordSets[?ends_with(Name,'uplink.nu01.com.')].[Name,Type]" --output text
   ```

   In this order:
   1. With admin credentials, update `uplink-github-deploy` (command in
      [Letting GitHub Actions deploy](#letting-github-actions-deploy)): the
      deploy roles get the `nu01.com` validation-record permission and stop
      importing `uplink-zone-HostedZoneId`.
   2. With admin credentials, `scripts/deploy-sh.sh`: `uplink-sh` drops its
      alias records from the subzone and replaces its certificate with one
      validated in `nu01.com` (CloudFormation swaps it into CloudFront and
      deletes the old one).
   3. An RC tag (deploy-rc.yml), then a GA tag (deploy.yml). On each stage's
      first such deploy, `scripts/deploy.sh` first redeploys the site stack
      with the certificate it uses as a parameter (dropping its import of
      `<tenant>-CertificateArn` and its alias records in the subzone), then
      the cert stack, which replaces the certificate (validated through the
      same `_<token>` CNAME round 1 copied into `nu01.com`) and keeps the
      old one (UpdateReplacePolicy Retain), then the site stack with the new
      ARN, and finally deletes the old certificate.
3. **Retiring the subzone**, after round 2 has deployed on both stages and
   `uplink-sh`:

   ```bash
   # No stack may still import the zone (the delete fails otherwise):
   aws cloudformation list-imports --export-name uplink-zone-HostedZoneId
   #   -> "Export 'uplink-zone-HostedZoneId' is not imported by any stack"
   # Records no stack owns any more (the old validation CNAMEs; nu01.com
   # has copies). A zone must be empty but for its NS and SOA to be deleted:
   aws route53 list-resource-record-sets --hosted-zone-id Z04348662JLR2LAZSOI74 --output json \
     | python3 -c 'import json,sys; print(json.dumps({"Changes": [{"Action": "DELETE", "ResourceRecordSet": r} for r in json.load(sys.stdin)["ResourceRecordSets"] if r["Type"] not in ("NS", "SOA")]}))' >empty.json
   cat empty.json   # expect only _<token> CNAMEs, no A/AAAA
   aws route53 change-resource-record-sets --hosted-zone-id Z04348662JLR2LAZSOI74 --change-batch file://empty.json
   aws cloudformation delete-stack --region us-east-1 --stack-name uplink-zone
   aws cloudformation wait stack-delete-complete --region us-east-1 --stack-name uplink-zone
   ```

   The stack also owns `Delegation`, the NS record in `nu01.com` that
   round 1 already deleted by hand. If CloudFormation doesn't take the
   missing record as deleted and the stack ends in `DELETE_FAILED` on it,
   delete again keeping it (it no longer exists, so nothing is left behind):

   ```bash
   aws cloudformation delete-stack --region us-east-1 --stack-name uplink-zone --retain-resources Delegation
   ```

   Then drop `LegacyZoneId` and the subzone statements from
   [github-deploy.cform.yaml](github-deploy.cform.yaml) and update
   `uplink-github-deploy` again.
