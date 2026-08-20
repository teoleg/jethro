# ADR-0021 — AWS deployment: own stack, own node, own pipeline

Status: Accepted (2026-08-13)

## Context

The owner wants muni-world running in AWS — **only muni-world and its database**, with design, build and
deployment fully separate from the trading platform's earlier deployment (`JethroDev` stack +
`deploy.yml`). The two systems already share nothing at runtime (ADR-0001); the deployment must preserve
that boundary: deploying, breaking, or tearing down one must never touch the other, and their costs must
be separable lines in billing.

jethro's deployment pattern is proven and fits: a single-node CDK stack (EC2 + compose + Caddy edge),
ECR for the app image, GitHub OIDC (no static creds), manual `workflow_dispatch` deploys via SSM Run
Command, secrets in SSM Parameter Store. Reusing the *pattern* while duplicating none of the *resources*
is the cheapest reliable path.

## Decision

### 1. Separate CloudFormation stack, same CDK app

`MuniWorldStack` lives beside `JethroDevStack` in `infra/` (same tooling, one `cdk deploy MuniWorld`),
but is an independent stack: its own VPC, node, ECR repo, IAM roles, and S3 bucket. Tags on the stack
scope override the app-level ones: `project=muni-world` — a **separate FinOps line** (ADR-0011 pattern).
Destroying either stack leaves the other untouched.

### 2. One small ARM node, ALWAYS ON — a deliberate divergence from ADR-0013

- Default `t4g.small` (2 vCPU / 2 GiB Graviton) + 20 GiB encrypted gp3 root. Approximate on-demand cost
  ≈ **$12–14/month + ~$1.60 EBS** (owner dial: `-c muniInstanceType=...`; prices are AWS's published
  on-demand rates, not a chosen number). The workload is one Spring jar (≤512 MiB heap) + Postgres —
  a fraction of the trading node.
- **No stop-when-idle schedules.** jethro's node stops nightly (ADR-0013) because it is expensive and
  session-bound; muni-world's *value is its daily cadence* (curve refresh, fund pass, backfills), and an
  idle-stopped node silently skips ingests. At ~2% of the trading node's cost, always-on is the right
  default; the account-wide budget alarm in `JethroDev` (ADR-0011) still covers the account.

### 3. Database: Postgres in compose, durability via S3 — not RDS

Postgres 16 runs as a compose service with a named volume, **its own database (`muni`)** — no shared
instance, nothing of jethro's on the node. Durability comes from **nightly `pg_dump` + the OS-PDF inbox
synced to a versioned S3 bucket** (and a backup before every deploy), because the data's real shape says
so: the N-PORT universe and curve history are re-derivable from public sources; the irreplaceable bytes
are the hand-fetched OS PDFs and the parsed terms — megabytes, perfectly served by dump+S3. RDS
(~$15+/month for the smallest useful instance) buys managed failover the workload doesn't need; it stays
the upgrade path if the DB ever becomes primary-source.

### 4. Edge: Caddy, TLS, basic auth — nothing else listens

Same proven shape as jethro: security group opens 80/443 only (SSM needs no inbound), Caddy terminates
TLS (ACME) and basic-auth-gates the UI, muni-world's :8090 is never published on the host. The
coverage/ingest endpoints stay behind that auth wall.

### 5. Secrets and settings: SSM Parameter Store under `/muni/prod/*`

SecureStrings set out-of-band (never in the repo): `POSTGRES_PASSWORD`, `MUNI_BASIC_AUTH_HASH`, and
`MUNI_CONTACT_EMAIL` (the SEC fair-access contact — kept a secret parameter so the owner's address never
enters git, same rule as before). Non-secret params (domain, basic-auth user, backup bucket name) are
created by the stack. `remote-deploy.sh` materialises the whole prefix into `deploy/.env` on the node.

### 6. Build + deploy pipeline: separate workflow, separate role — CONTINUOUS on green

`.github/workflows/muni-deploy.yml` has two ways in (the continuous mode is the owner's direction —
muni-world is analytics, not the trading book, so the deliberate-manual-only posture jethro's deploy
keeps is not required here):

- **Auto:** every push to the designated muni branch that touches `muni-world/**` runs the FULL muni
  test suite (lattice identities, QuantLib cross-validation, curve-validation gates) and, **only on
  green**, deploys that exact commit. The deploy job skips cleanly while the AWS infra doesn't exist
  yet (the tag lookup finds no node), so the pipeline is safe on the branch before `cdk deploy`.
- **Manual:** `workflow_dispatch` stays for redeploying any ref or a single component.

Mechanics in both modes: build `:muni-world:bootJar` natively on the runner, wrap it in a thin
`linux/arm64` JRE image (the Dockerfile only COPIES the jar — nothing compiles under emulation), push
to the muni ECR repo, then SSM Run Command on the muni node runs `muni-world/deploy/remote-deploy.sh`
(backup first, then pull + up, health-gated). **Zero muni-only GitHub configuration** (owner
directive — the jethro variables were configured once and are THE config): the workflows assume
jethro's existing `AWS_DEPLOY_ROLE_ARN`, to which this stack ADDITIVELY attaches the muni
permissions (push the muni image; SendCommand to the muni node; read-only `ec2:DescribeInstances`)
— JethroDev's template is untouched. The muni node is discovered **at deploy time by its
`project=muni-world` tag**, so not even an instance-id variable exists; while the stack is not yet
deployed, the lookup comes back empty and the deploy job skips cleanly (tests still gate every
commit). The ONLY manual act in steady state is loading the collected data (Muni Restore) — the
Postgres container, its `muni` database and the whole Flyway schema come up automatically on the
first deploy.

## Consequences

- First-time bring-up is a short runbook (`muni-world/deploy/README.md`): `cdk deploy MuniWorld`, set the
  three SecureStrings, point DNS at the Elastic IP, run the deploy workflow.
- The trading platform's `deploy.yml`, stack, ECR repo, IAM roles and node are untouched by any of this.
- Backup/restore becomes S3-first in AWS (the Pi's `backups/` discipline continues locally); the bucket
  is versioned and `RETAIN`ed, so even a stack teardown keeps the data.
- The muni node runs the ingest schedule continuously — the curve stays current daily and quarterly
  N-PORT filings are picked up within a day, without anyone's laptop being on.
