# ADR-0011: FinOps in the platform — finops-service, tagged AWS costs, Costs tile in the UI

- **Status:** Proposed
- **Date:** 2026-07-11
- **Deciders:** Oleg
- **Tags:** finops, aws, ui, cost

## Context

Development runs on a personal budget, and the platform's cost profile has three legs
that can each run away independently: **(1) AWS infrastructure** — MSK + Aurora + NAT +
Fargate form a floor in the low hundreds USD/month (ADR-0007), and forgetting to shut
down dev is the classic leak; **(2) AI token spend** — ADR-0010 makes model calls the
heart of the algo engine, with a defined trigger (~$700/month) that must be *measured*
to fire; **(3) market data subscriptions** — usage-based provider tiers (ADR-0009).

The owner wants cost visibility embedded in the platform's own UI, not buried in the AWS
console. Relevant AWS facts: Cost Explorer (`ce:GetCostAndUsage`) gives cost by tag/service
at daily granularity with **~24h data lag** at **$0.01 per API call**; AWS Budgets (first
two free, then ~$0.02/day each) fires SNS notifications at thresholds and can even
auto-stop resources; CloudWatch `EstimatedCharges` is free but coarse (~6h, no breakdown).
LLM token usage, by contrast, is already ours in real time — every `ai.decisions` event
carries token counts (ADR-0010 invariant).

## Decision

We will treat cost as **platform telemetry** and build a small **`finops-service`** plus
a fifth UI tile:

1. **Tag everything at birth.** The CDK app (ADR-0007) applies `project=jethro` and
   `service=<name>` tags globally; cost allocation tags activated in Billing. Untagged
   spend is a defect visible in the Costs view.
2. **`finops-service`** (Java, same monorepo):
   - Polls Cost Explorer **4×/day** (~$1.20/month in API calls), grouped by the
     `service` tag → writes daily cost rows to Postgres, publishes `cost.snapshots`.
   - Consumes `ai.decisions` and prices token usage per model in **real time** — LLM
     spend needs no AWS API and no lag. This is the sensor for ADR-0010's embedded
     trigger.
   - Holds **budget thresholds in config** (monthly total + per-leg); threshold breaches
     are published as alert events and shown in the UI.
3. **AWS Budgets as the independent backstop:** one account-level monthly budget with
   SNS→email at 80%/100% — deliberately *outside* the platform, so a broken
   finops-service can't silence the alarm. Automated budget *actions* (auto-stopping
   resources) are deferred until dev/prod are separate accounts — an auto-stop must
   never be able to touch a live trading process.
4. **UI: Costs tile → `/costs`** (ADR-0006 pattern): month-to-date spend by service
   (stacked, vs budget line), burn-rate projection, real-time LLM token spend today,
   active alerts, and a "what is currently running" panel (ECS services + their hourly
   cost) to make shutdown-when-idle actionable.
5. **Local dev:** finops-service runs with a stub cost source (fixture data) — the
   local-dev-first invariant (ADR-0007) holds; only the deployed instance talks to
   Cost Explorer.

## Alternatives considered

**AWS Console + Budgets only (no in-platform FinOps).** Zero build cost and honestly
sufficient for pure infra spend — but it cannot see LLM token spend in real time (24h+
lag on Bedrock line items), can't correlate cost to platform activity, and the owner
explicitly wants it in the UI. Rejected as the whole answer; kept as the backstop (point 3).

**CUR/Data Exports to S3 + Athena.** The heavyweight FinOps pipeline: hourly-granularity
billing data, resource-level detail. Right when multi-account/team reporting arrives;
for one account it's setup and query plumbing that Cost Explorer's API already answers.
Deferred — revived if per-resource drill-down or >1 account materializes.

**Third-party FinOps tooling (Vantage, CloudZero, Kubecost...).** Solves multi-cloud
enterprise problems for a per-seat/percentage fee larger than our whole bill. Rejected.

## Consequences

- Positive: one screen shows infra + AI + data costs against budget; LLM spend visible
  in real time; ADR-0010's cost trigger is measured, not guessed; tagging discipline
  from day one is nearly free now and painful to retrofit.
- Negative: a sixth service to build and run (kept deliberately tiny — pollers + one
  view); Cost Explorer's ~24h lag means infra numbers are "yesterday's", only LLM spend
  is live — the UI must label this; budget thresholds in config are advisory, nothing
  in-platform stops spend automatically yet.
- Follow-ups: `cost.snapshots` Avro schema alongside ADR-0004 schemas; per-model price
  table maintained in finops-service config (update on provider price changes); ADR when
  dev/prod accounts split (enables budget auto-stop actions safely).
