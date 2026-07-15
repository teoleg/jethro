# Deploying Jethro to AWS (single EC2 node)

This is the ADR-0013 dev/demo shape: **one EC2 instance running the whole `docker-compose`
stack** — Redpanda, Postgres, Ollama, the app, and Caddy (TLS + basic auth) in front.
Nothing managed yet (no Fargate/Aurora/ALB — that's the production shape, gated on the
ADR-0015 order-JVM extraction and real auth). Deploys run from GitHub Actions
(`.github/workflows/deploy.yml`): build the app image → push to ECR → roll out on the node
via SSM. **No SSH keys and no static AWS credentials** — GitHub authenticates to AWS via
OIDC, and the node is reached through SSM.

There is **no data to migrate** off the Pi: Postgres starts empty, Flyway runs V1→latest,
and the sim regenerates everything.

## Chosen shape (defaults — change to taste)

| Piece | Default | Notes |
|---|---|---|
| Instance | `m6i.xlarge` (4 vCPU / 16 GB), Ubuntu 24.04 x86-64 | Comfortable for CPU-only Ollama + Redpanda + Postgres + JVM together. Graviton `m7g.xlarge` is ~20% cheaper and all images are multi-arch — swap the AMI + type if you prefer ARM. |
| Storage | one `gp3` EBS volume (~100 GB) | Postgres + LMDB + Redpanda live on local block storage (LMDB needs a real filesystem — never EFS, ADR-0014). |
| Inference | `qwen2.5:1.5b` CPU-only | Bump to `qwen2.5:3b` if you want; GPU is a separate (pricey) decision. |
| Edge | Caddy, auto TLS + HTTP basic auth | The app has no auth yet (review finding #5); the basic-auth credential guards everything. |
| Cost | ~$40–70/mo, ≈half with stop-when-idle | EventBridge Scheduler stop/start; the systemd unit restores the stack on boot. |

## One-time AWS setup

Run these once (adjust region/account). They create the pieces the workflow needs; codifying
this in CDK (`infra/`, build-order step 9) is the tracked follow-up.

```bash
export AWS_REGION=eu-west-1
export ACCOUNT=$(aws sts get-caller-identity --query Account --output text)

# 1. ECR repository for the app image
aws ecr create-repository --repository-name jethro-app --region "$AWS_REGION"

# 2. GitHub OIDC provider (skip if it already exists in the account)
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1

# 3. Deploy role GitHub assumes (trust policy scoped to THIS repo; least privilege:
#    push to the ECR repo + send SSM commands to the one instance). See policy JSON below.
#    aws iam create-role --role-name jethro-deploy --assume-role-policy-document file://trust.json
#    aws iam put-role-policy --role-name jethro-deploy --policy-name deploy --policy-document file://deploy-policy.json

# 4. Instance role for the node: SSM managed + ECR pull + read /jethro/prod/* params
#    (AmazonSSMManagedInstanceCore + AmazonEC2ContainerRegistryReadOnly + an SSM getParameters policy)

# 5. Secrets in SSM Parameter Store (SecureString for the sensitive ones)
aws ssm put-parameter --name /jethro/prod/POSTGRES_USER       --type String       --value jethro
aws ssm put-parameter --name /jethro/prod/POSTGRES_PASSWORD   --type SecureString  --value "$(openssl rand -base64 24)"
aws ssm put-parameter --name /jethro/prod/JETHRO_TRADING_PROVIDER --type String    --value sim
aws ssm put-parameter --name /jethro/prod/JETHRO_AI_MODEL     --type String        --value qwen2.5:1.5b
aws ssm put-parameter --name /jethro/prod/JETHRO_DOMAIN       --type String        --value jethro.example.com
aws ssm put-parameter --name /jethro/prod/JETHRO_BASIC_AUTH_USER --type String     --value oleg
aws ssm put-parameter --name /jethro/prod/JETHRO_BASIC_AUTH_HASH --type SecureString \
  --value "$(docker run --rm caddy:2.8-alpine caddy hash-password --plaintext 'a-strong-password')"
# only if provider=finnhub:
# aws ssm put-parameter --name /jethro/prod/FINNHUB_TOKEN --type SecureString --value "$FINNHUB"

# 6. Launch the instance (public subnet, strict SG: 443/80 from your IP or the world for the
#    ACME challenge, nothing else; SSM needs no inbound). Attach the instance role, an Elastic
#    IP, and pass deploy/bootstrap.sh as user-data. Point JETHRO_DOMAIN's DNS at the EIP.
```

**GitHub repo settings → Actions → Variables** (not secrets — none are sensitive):
`AWS_REGION`, `ECR_REPOSITORY=jethro-app`, `EC2_INSTANCE_ID`, `SSM_PREFIX=/jethro/prod`,
`AWS_DEPLOY_ROLE_ARN=arn:aws:iam::<acct>:role/jethro-deploy`.

Minimal trust policy for the deploy role (`trust.json`):

```json
{ "Version": "2012-10-17", "Statement": [{
  "Effect": "Allow",
  "Principal": { "Federated": "arn:aws:iam::ACCOUNT:oidc-provider/token.actions.githubusercontent.com" },
  "Action": "sts:AssumeRoleWithWebIdentity",
  "Condition": {
    "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com" },
    "StringLike": { "token.actions.githubusercontent.com:sub": "repo:teoleg/jethro:*" }
  }
}]}
```

## Deploying

1. GitHub → Actions → **Deploy** → Run workflow. Pick the **component** (`all` for the full
   stack, or one of `app`/`ollama`/`redpanda`/`postgres`/`caddy`) and the **ref**.
2. The workflow builds + pushes the app image (when `app`/`all`), then SSM-runs
   `deploy/remote-deploy.sh` on the node: it pulls secrets from SSM into `deploy/.env`, logs
   in to ECR, `docker compose pull`+`up -d` the chosen component(s), ensures the Ollama model
   is present, and waits for the app healthcheck. The remote stdout/stderr is echoed back
   into the Actions log.

Reboots (including the stop-when-idle schedule) are handled by the `jethro.service` systemd
unit the bootstrap installs — it brings the stack back up from `deploy/.env`.

## What each file is

| File | Role |
|---|---|
| `docker-compose.prod.yml` | The five components; app from ECR, Caddy the only public port, everything else internal. |
| `Caddyfile` | Auto-TLS + basic auth reverse proxy to `app:8080`. |
| `remote-deploy.sh` | Runs on the node (via SSM): SSM secrets → `.env`, ECR login, compose pull/up, model pull, health-wait. |
| `bootstrap.sh` | EC2 user-data: install Docker + AWS CLI, clone repo, install the boot-restore systemd unit. |
| `.env.example` | Documents the settings `remote-deploy.sh` writes from SSM (the real `.env` is never committed). |
| `../.github/workflows/deploy.yml` | The CI/CD entrypoint: build → ECR → SSM rollout, per-component. |

## Not included yet (deliberate)

- **CDK** to codify all of the above (`infra/`, step 9) — the runbook is the interim.
- **Managed services** (Fargate/Aurora/MSK) — the production shape, gated per ADR-0007/0015.
- **App-level auth** — Caddy basic-auth is the stopgap until the order-module JVM split.
