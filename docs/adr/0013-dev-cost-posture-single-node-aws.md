# ADR-0013: Dev cost posture — one EC2 node runs the compose stack; managed services deferred behind triggers

- **Status:** Proposed (amends ADR-0005 and ADR-0007 for the dev environment)
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
