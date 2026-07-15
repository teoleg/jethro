# ADR-0013: Dev cost posture — one EC2 node runs the compose stack; managed services deferred behind triggers

- **Status:** Accepted (amends ADR-0005 and ADR-0007 for the dev environment)
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** aws, cost, infra, finops

## Context

The as-designed dev environment (ADR-0005/0007) costs ~$650–750/month before any real
work: MSK Serverless ~$550 (removed by ADR-0012), Aurora Serverless v2 ~$44+ at its
0.5-ACU floor, NAT Gateway ~$32 + data, ALB ~$16 + LCU, Fargate ~$50–100 for six
always-on JVM tasks, Secrets Manager $0.40/secret. Meanwhile the local-dev-first
invariant (ADR-0007) already requires the entire platform to run as one Docker Compose
stack — meaning the cheapest possible AWS dev runtime is *that same stack on one box*.
AWS's genuinely free tiers cover several needs outright: CloudFront (1TB/month
always-free), SSM Parameter Store (standard params free), Glue/Redpanda schema registry
(free), AWS Budgets (first two free), SQS/SNS free tiers for alerting.

## Decision

We will run **dev as the Docker Compose stack on a single EC2 node**, and reserve the
managed-service architecture (Fargate, Aurora, ALB, managed Kafka) as the **production
shape**, adopted per component when its trigger fires:

1. **Compute:** one Graviton instance (t4g.large, 2 vCPU/8GB, ~$50/month; resize as
   measured) running the same `docker-compose.yml` as local dev: Redpanda (ADR-0012),
   Postgres, all services. EventBridge schedule stops it nightly/weekends —
   stop-when-idle roughly halves the bill. EBS gp3 ~100GB ≈ $8/month.
2. **Database:** **Postgres in the compose stack** for dev — it is plain Postgres with
   Flyway either way, so moving to RDS/Aurora later is a connection string + dump.
   Aurora trigger: live trading, or need for managed HA/backups beyond nightly
   pg_dump-to-S3 (which dev gets instead).
3. **Networking:** dev node in a **public subnet** with a strict security group
   (owner-IP allowlist) — **no NAT Gateway, no ALB**. Caddy/nginx on the node fronts
   `ui-gateway` with TLS. ALB + private subnets + NAT (or fck-nat ~$3/month) arrive with
   production.
4. **Stays managed because it's ~free:** S3 (UI hosting + Parquet archive), CloudFront
   (always-free tier), **SSM Parameter Store instead of Secrets Manager**, ECR (prune
   images), CloudWatch with 7-day log retention and INFO-level sampling (log ingest at
   $0.50/GB is the sneaky line — JVMs are chatty).
5. **CDK still provisions all of it** (ADR-0007 unchanged in spirit): the dev stack is
   small but codified, tagged (ADR-0011), and torn down with one command. The prod-shape
   stacks live in the same `infra/` module behind a stage flag, so promotion is a
   deploy, not a redesign.

Net dev bill: **~$40–70/month** (≈$25–35 with stop-when-idle), vs ~$650–750 as-designed.

## Alternatives considered

**Keep the managed dev environment (ADR-0005/0007 as written).** Closest to prod parity
and zero server ops, at ~10–15× the cost — parity we don't need until there's production
to be parallel to. Deferred per component with triggers (live trading, HA requirement,
throughput beyond one node).

**Spot instance for the dev node.** ~70% cheaper still, but interruptions mid-session
are hostile to stateful Redpanda/Postgres volumes for savings of ~$30/month. Rejected
for the always-on dev node; fine later for backtest batch workers.

**Free-tier RDS (t4g.micro, 12 months) instead of compose Postgres.** Free year, then
~$12–15/month, but splits the stack across the network boundary and adds a second thing
to stop/start. Rejected — compose Postgres is simpler and equally portable.

**Lightsail / fixed-price VPS.** Comparable price to EC2+CDK but a walled garden off the
IAM/tagging/CDK path the rest of the platform uses (ADR-0007, 0011). Rejected.

## Consequences

- Positive: dev burn drops ~90%, under the ADR-0011 budget without heroics; dev == local
  topology exactly (one compose file, fewer "works locally" surprises); stop-when-idle
  is one EventBridge rule.
- Negative: dev is a pet server — single AZ, no autoscaling, we patch it (mitigated:
  cattle via CDK — rebuildable from code + S3/pg_dump in minutes); public-subnet posture
  is only as good as the security group + Caddy config (owner-IP allowlist, no wildcard
  ingress — violations are defects); prod-shape stacks stay unexercised until promotion,
  so some ECS/Aurora issues surface late.
- Follow-ups: mark ADR-0005/0007 status lines "amended for dev by ADR-0013"; EventBridge
  stop/start schedule + nightly pg_dump-to-S3 in the first CDK stack (with the budget
  backstop, ADR-0011); running-resources panel in the Costs view shows the node state.

## Implementation note — CI/CD deploy to the single node (2026-07-15, task #33)

The dev-node shape is now executable from `deploy/` + a GitHub Actions workflow, moving off
the Pi. Two refinements to the decision above, both from things learned since:

- **Instance size**: `t4g.large` (2 vCPU/8GB) is too tight once Ollama shares the box with
  Redpanda + Postgres + the JVM — the same 2-vCPU squeeze that flakes CI's Ollama. Default
  is now `m6i.xlarge` (4 vCPU/16GB, Ubuntu x86-64; Graviton `m7g.xlarge` is a cheaper
  drop-in since all images are multi-arch). Architecture parity with the Pi is no longer a
  goal — any fitting Linux image is fine.
- **Access/auth**: the app still has no authentication (review finding #5), so the public
  edge is **Caddy with automatic TLS + HTTP basic auth** in front of `ui-gateway`; that one
  credential is the gate until the ADR-0015 order-JVM extraction enables real auth. The
  SG/owner-IP-allowlist idea is superseded by "one password over TLS".

Deploy mechanism (new, not in the original decision): **GitHub Actions → build the app
image → push to ECR → roll out via SSM Run Command**. No SSH keys and no static AWS
credentials (OIDC federation); the node is reached only through SSM. Secrets stay in SSM
Parameter Store and are written into `deploy/.env` on the node at rollout, never committed.
Every component (redpanda/postgres/ollama/app/caddy) comes up from `deploy/
docker-compose.prod.yml` and is individually targetable from the workflow. A `jethro.service`
systemd unit restores the stack across the stop-when-idle reboots. **CDK codification of the
one-time AWS setup (`infra/`, build-order step 9) remains the tracked follow-up** — the
`deploy/README.md` runbook is the interim.

## Implementation note — baked-AMI path is the DEFAULT (2026-07-15)

The **default deploy is the immutable baked AMI**; the compose-rollout and manual quickstart
are alternatives (all target the same single-node ADR-0013 shape):

- **Packer** (`infra/packer/jethro.pkr.hcl` + `provision.sh`) bakes the whole stack — the
  same `docker compose` structure run on the Pi, but x86-64 — into an Ubuntu AMI, with the
  app image built and the **Ollama model pre-pulled**, plus a `jethro.service` systemd unit.
  An instance launched from the AMI boots straight into the running stack on `:8080`, no
  build and no downloads (fast, self-contained — and it sidesteps the boot-time model-pull
  that flaked CI).
- The **`Bake AMI` GitHub workflow** builds the AMI and optionally spawns a fresh instance
  from it in the same account (kappara's), associating a stable IP and terminating the
  previous instance — classic immutable rollout.

Auth for this path is IAM-user access keys as repo secrets (simplest to set up from mobile);
the OIDC deploy role from the CDK stack is the hardening alternative. Cost control is
t3a.xlarge + stop-when-idle + the single account-wide $100 budget (the budget/schedule live
in the CDK stack and can be deployed on their own). TLS/auth are NOT in the AMI (`:8080` is
IP-locked via the security group); the Caddy edge in `deploy/docker-compose.prod.yml` is the
productionised alternative when a public authenticated URL is wanted.

`infra/packer/README.md` is the runbook; `deploy/README.md` is the entry point that steers
here and lists the alternatives.
