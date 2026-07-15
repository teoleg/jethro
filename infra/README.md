# infra — AWS CDK (Java) for the single-node dev stack

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
# account/region come from your AWS CLI creds (CDK_DEFAULT_ACCOUNT/REGION)
cdk diff
cdk deploy \
  -c githubBranch=claude/new-session-smb8v6 \
  -c alertEmail=you@example.com          # optional: enables the monthly budget alarm
# other context keys: githubRepo, instanceType, monthlyBudgetUsd, createOidcProvider
```

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
