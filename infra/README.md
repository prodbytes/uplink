# infra

CloudFormation for uplink's sites, all in us-east-1 (CloudFront
certificates must live there) and in the `uplink.nu01.com` zone.

| Template | Stack | What | Deployed by |
|----------|-------|------|-------------|
| [zone.yaml](zone.yaml) | `uplink-zone` | The `uplink.nu01.com` zone, delegated from `nu01.com` | [scripts/deploy-sh.sh](../scripts/deploy-sh.sh), by hand |
| [sh.yaml](sh.yaml) | `uplink-sh` | https://sh.uplink.nu01.com, the `curl \| sh` run script | [scripts/deploy-sh.sh](../scripts/deploy-sh.sh), by hand |
| [site.yaml](site.yaml) | `uplink-web` | https://uplink.nu01.com, uplink in the browser (GA) | [scripts/deploy.sh](../scripts/deploy.sh), from [deploy.yml](../.github/workflows/deploy.yml) on `*GA` tags |
| [site.yaml](site.yaml) | `uplink-rc-web` | https://rc.uplink.nu01.com, the release candidate | [scripts/deploy.sh](../scripts/deploy.sh) with `STAGE=rc`, from [deploy-rc.yml](../.github/workflows/deploy-rc.yml) on `*RC*` tags |
| [github-deploy.yaml](github-deploy.yaml) | `uplink-github-deploy` | The IAM roles those two workflows assume through GitHub's OIDC | by hand, once |

## Letting GitHub Actions deploy

The deploy workflows hold no AWS keys: they exchange GitHub's OIDC token
for a role from [github-deploy.yaml](github-deploy.yaml). Production's
role trusts only `*GA` tag runs; the RC role trusts `*RC*` tag runs and
manual runs from `main`. Each may manage only its own stack and change
only its own DNS name (and that name's certificate-validation record).
The account's GitHub OIDC provider already exists (presence created it),
so the template doesn't create another unless `CreateOidcProvider=true`.

Deploy it once with admin credentials, then set the role ARNs as
repository variables:

```bash
aws cloudformation deploy --region us-east-1 --stack-name uplink-github-deploy \
  --template-file infra/github-deploy.yaml --capabilities CAPABILITY_NAMED_IAM
out() { aws cloudformation describe-stacks --region us-east-1 --stack-name uplink-github-deploy \
  --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text; }
gh variable set AWS_DEPLOY_ROLE_ARN -R prodbytes/uplink --body "$(out DeployRoleArn)"
gh variable set AWS_DEPLOY_RC_ROLE_ARN -R prodbytes/uplink --body "$(out RcDeployRoleArn)"
```

The repository uses GitHub's immutable OIDC subjects
(`repo:prodbytes@288014477/uplink@1404673480:ref:...`); the roles trust
that form and the `owner/name` one.
