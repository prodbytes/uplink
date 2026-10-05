# infra

CloudFormation for uplink's sites, all in us-east-1 (CloudFront
certificates must live there) and in the `uplink.nu01.com` zone.

| Template | Stack | What | Deployed by |
|----------|-------|------|-------------|
| [zone.cform.yaml](zone.cform.yaml) | `uplink-zone` | The `uplink.nu01.com` zone, delegated from `nu01.com`; exports `uplink-zone-HostedZoneId` and `-HostedZoneName` | [scripts/deploy-sh.sh](../scripts/deploy-sh.sh), by hand |
| [sh.cform.yaml](sh.cform.yaml) | `uplink-sh` | https://sh.uplink.nu01.com, the `curl \| sh` run script | [scripts/deploy-sh.sh](../scripts/deploy-sh.sh), by hand |
| [acm-cert.cform.yaml](acm-cert.cform.yaml) | `uplink-acm-cert`, `uplink-rc-acm-cert` | The certificate of uplink.nu01.com / rc.uplink.nu01.com; exports `<tenant>-CertificateArn` and `-CertificateDomainName` | [scripts/deploy.sh](../scripts/deploy.sh) |
| [site.cform.yaml](site.cform.yaml) | `uplink-web`, `uplink-rc-web` | https://uplink.nu01.com (GA) and https://rc.uplink.nu01.com (RC): S3 bucket, CloudFront with origin access control, alias records, [`/health`](#health-checks) and the Route 53 health checks that email alarms; imports the certificate, the zone and the health function's role | [scripts/deploy.sh](../scripts/deploy.sh), from [deploy.yml](../.github/workflows/deploy.yml) on `*GA` tags and [deploy-rc.yml](../.github/workflows/deploy-rc.yml) (`STAGE=rc`) on `*RC*` tags |
| [github-deploy.cform.yaml](github-deploy.cform.yaml) | `uplink-github-deploy` | The IAM roles those two workflows assume through GitHub's OIDC, and the roles the `/health` functions run as; exports `<tenant>-HealthFunctionRoleArn` | by hand, once |

The layout follows prodbytes/dsp's gitops: one stack per piece (zone,
certificate, site), each importing the previous one's exports, all in
us-east-1 (CloudFront certificates must live there, and imports only
resolve within a region). The tenant (`uplink` for production, `uplink-rc`
for the release candidate) prefixes the stack names and exports and tags
the resources.

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
  the query string, so neither health checkers nor visitors can multiply
  the calls to GitHub or the function.
- **Two Route 53 health checks** of the domain, from three regions every
  30 s: `/` (HTTPS) and `/health` (HTTPS, matching `"status": "ok"`).
- **An alarm for each**, after two failing minutes, to the
  `<tenant>-health` SNS topic, which also announces recovery. The
  `NotificationEmails` addresses are subscribed: `scripts/deploy.sh
  --emails a@example.com,b@example.com`, else the `HEALTH_EMAILS`
  repository variable in the deploy workflows, else
  `julio+health@nu01.com`. AWS emails each new address a confirmation
  link; it gets no alarms until it is confirmed.

The functions' roles live in the `uplink-github-deploy` stack, deployed by
hand, so the deploy roles only pass them and never create IAM roles. Update
that stack before the first deploy that brings the health checks, or the
site stack cannot import `<tenant>-HealthFunctionRoleArn`.

```bash
curl -s https://uplink.nu01.com/health
```

## Letting GitHub Actions deploy

The deploy workflows hold no AWS keys: they exchange GitHub's OIDC token
for a role from [github-deploy.cform.yaml](github-deploy.cform.yaml). Production's
role trusts only `*GA` tag runs; the RC role trusts `*RC*` tag runs and
manual runs from `main`. Each may manage only its own stack and change
only its own DNS name (and that name's certificate-validation record).
The account's GitHub OIDC provider already exists (presence created it),
so the template doesn't create another unless `CreateOidcProvider=true`.

Deploy it once with admin credentials, then set the role ARNs as
repository variables:

```bash
aws cloudformation deploy --region us-east-1 --stack-name uplink-github-deploy \
  --template-file infra/github-deploy.cform.yaml --capabilities CAPABILITY_NAMED_IAM
out() { aws cloudformation describe-stacks --region us-east-1 --stack-name uplink-github-deploy \
  --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text; }
gh variable set AWS_DEPLOY_ROLE_ARN -R prodbytes/uplink --body "$(out DeployRoleArn)"
gh variable set AWS_DEPLOY_RC_ROLE_ARN -R prodbytes/uplink --body "$(out RcDeployRoleArn)"
```

The repository uses GitHub's immutable OIDC subjects
(`repo:prodbytes@288014477/uplink@1404673480:ref:...`); the roles trust
that form and the `owner/name` one.
