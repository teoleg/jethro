# infra — AWS CDK (Java) for the single-node dev stack

> **The default deploy is the baked AMI → [`packer/README.md`](packer/README.md).** This CDK
> stack is optional: use it for the **cost guardrails** ($100 account budget + stop-when-idle
> schedule) and, if you want them, the ECR/OIDC/instance pieces the *managed compose-rollout*
> alternative uses. You don't need it for the baked-AMI path.


Codifies the `deploy/README.md` runbook (ADR-0007/0013). One CDK app, one stack
(`JethroDev`): the EC2 host + its IAM/networking, the ECR repo, the GitHub OIDC deploy
role, stop-when-idle schedules, an optional budget alarm, and the non-secret SSM
parameters. The production-shape stacks (Fargate/Aurora/ALB) will live here behind a stage
flag when their ADR-0015/0007 triggers fire; only the dev stage exists today.

**Standalone build on purpose:** this is a separate Gradle project (its own
`settings.gradle.kts`, not in the root), so `aws-cdk-lib` never enters the app's build or
module graph — CDK is a deploy-time tool.

## Prerequisites

- Node + the CDK CLI: `npm i -g aws-cdk`
- JDK 21 and the AWS CLI, authenticated (`aws sts get-caller-identity` works)
- First time in the account/region: `cdk bootstrap`

## Deploy

```bash
cd infra
# account/region come from your AWS CLI creds (CDK_DEFAULT_ACCOUNT/REGION) — point these at
# the account you want to run in (e.g. the same one your other Claude project uses).
cdk diff
cdk deploy \
  -c githubBranch=claude/new-session-smb8v6 \
  -c alertEmail=you@example.com \        # REQUIRED for the budget alarm
  -c createOidcProvider=false            # if the GitHub OIDC provider already exists (see below)
# other context keys: githubRepo, instanceType, monthlyBudgetUsd
```

## Running in your existing account (single control point)

CDK deploys to whatever account your AWS CLI credentials point at — just use that account's
profile; no code change. The budget is **account-wide** ($100), so it's the one cost control
point over everything in the account (the other demo project is negligible). Resources are
still tagged `project=jethro` (ADR-0011) so you can see the split in Cost Explorer if you
ever want to, but nothing depends on activating cost-allocation tags.

One AWS hard constraint to know: the **GitHub OIDC provider** for
`token.actions.githubusercontent.com` can exist only once per account. If anything else in
the account already created it, pass `-c createOidcProvider=false` and the stack imports it
instead of failing. Quick check:

```bash
aws iam list-open-id-connect-providers   # look for token.actions.githubusercontent.com
```

**Cost note:** a 16 GB instance is **not** free-tier eligible (free tier is `t3.micro`), so
this is real — but small — spend. Default `t3a.xlarge` (4 vCPU/16 GB x86) is ~$0.15/hr; with
the stop-when-idle schedule (weekday daytime only, off nights/weekends) that's ~$45–65/mo,
well under the **$100** cap. Left running 24/7 it would be ~$110/mo — which is why the
schedule exists and the budget alerts fire at 50/80/100%. Swap to `m6i.xlarge`
(`-c instanceType=m6i.xlarge`) for predictable non-burstable CPU if Ollama feels throttled;
still under $100 with the schedule.

The stack outputs the values you wire into **GitHub → Actions → Variables**
(`EcrRepositoryUri` → `ECR_REPOSITORY`, `DeployRoleArn` → `AWS_DEPLOY_ROLE_ARN`,
`InstanceId` → `EC2_INSTANCE_ID`), plus `ElasticIp` (point `JETHRO_DOMAIN`'s DNS at it) and
a reminder of the SecureString params to set.

## Set the secrets (SecureString — NOT in the template, by design)

```bash
aws ssm put-parameter --type SecureString --name /jethro/prod/POSTGRES_PASSWORD  --value "$(openssl rand -base64 24)"
aws ssm put-parameter --type SecureString --name /jethro/prod/JETHRO_BASIC_AUTH_HASH \
  --value "$(docker run --rm caddy:2.8-alpine caddy hash-password --plaintext 'a-strong-password')"
# private-repo clone at boot:
aws ssm put-parameter --type SecureString --name /jethro/prod/GITHUB_TOKEN --value "<fine-grained read token>"
# only if provider=finnhub:
aws ssm put-parameter --type SecureString --name /jethro/prod/FINNHUB_TOKEN --value "<token>"
```

The CDK creates the **non-secret** params (`JETHRO_TRADING_PROVIDER`, `JETHRO_AI_MODEL`,
`JETHRO_DOMAIN`, `JETHRO_BASIC_AUTH_USER`) with defaults — edit them in the console or via
`aws ssm put-parameter --overwrite`.

## Then

Trigger the **Deploy** workflow (see `deploy/README.md`). The instance's user-data already
ran `deploy/bootstrap.sh` at launch (installing Docker, cloning the repo, installing the
boot-restore systemd unit); the workflow's SSM rollout pulls the image + secrets and brings
the stack up.

## What this does NOT do yet

- **Production-shape stacks** (Fargate/Aurora/MSK) — gated per ADR-0007/0015.
- **DNS/ACM** — bring your own domain; Caddy provisions the cert via Let's Encrypt once
  `JETHRO_DOMAIN` resolves to the Elastic IP.
