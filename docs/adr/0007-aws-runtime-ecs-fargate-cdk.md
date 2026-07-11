# ADR-0007: Runtime on ECS Fargate, infrastructure as code with AWS CDK (Java)

- **Status:** Proposed
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** aws, infra

## Context

Runtime is AWS by requirement. Services are long-running JVM containers with steady
streaming workloads (poor fit for Lambda). This is a solo/small-team project: operational
overhead must stay low, and dev-environment cost matters. Infrastructure must be
reproducible from code.

## Decision

- **Compute:** ECS on **Fargate**. Each service (ADR-0003) is a task definition in one
  ECS cluster; Service Connect for east-west discovery. Dev runs single tasks; production
  can scale per service.
- **Ingress:** ALB → `ui-gateway` (REST + WebSocket); UI static assets on **S3 +
  CloudFront**.
- **Data plane:** MSK Serverless (ADR-0004), Aurora Serverless v2 (ADR-0005), all in
  private subnets of one VPC.
- **IaC:** **AWS CDK in Java** — same language and repo as the services (`infra/` Gradle
  module), typed constructs, diffable deploys. Secrets in Secrets Manager; parameters in
  SSM.
- **CI/CD:** GitHub Actions — build/test on PR; on merge to `main`, push images to ECR and
  `cdk deploy` to dev. Production deploys are manually approved.
- **Local dev is first-class:** Docker Compose (Redpanda, Postgres, services) replicates
  the topology; nothing may depend on AWS-only features without a local substitute.

## Alternatives considered

**EKS (Kubernetes).** More control, huge ecosystem — and a control plane to pay for plus
cluster ops burden that a solo project doesn't need. The container images are identical;
migrating later is mechanical. Rejected for now.

**EC2 + self-managed.** Cheapest raw compute, most ops. If GC tuning or network pinning
ever demands host control (or the C++ path arrives), revisit per service. Rejected.

**Lambda.** Wrong shape for persistent Kafka consumers and WebSocket fan-out. Rejected.

**Terraform for IaC.** Excellent and portable, but a second language; CDK-in-Java keeps
the whole platform in one toolchain. Rejected.

## Consequences

- Positive: no servers or clusters to manage; one language everywhere including infra;
  clean path from dev → prod.
- Negative: Fargate charges a premium per vCPU and can't pin CPUs — acceptable at this
  latency tier (ADR-0002); MSK + Aurora + NAT are the cost floor (~low hundreds USD/month
  dev) — mitigate with serverless tiers and shutting dev down when idle.
- Follow-ups: `infra/` CDK module skeleton; cost alarm + budget as the first stack.
