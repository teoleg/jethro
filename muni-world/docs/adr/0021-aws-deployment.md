# ADR-0021 — AWS deployment on the proven jethro rails: baked AMI, replaced node, S3-carried state

Status: Accepted (2026-08-13). Supersedes this ADR's own first draft (a CDK/OIDC design, built and
then removed on this same branch): the owner pointed out the deployment path that actually WORKS for
jethro is the AMI bake (`bake.yml`/`launch.yml` — static repo secrets, hardcoded region, Packer,
`run-instances`), and the CDK stack + OIDC roles my first draft assumed were never the deployed
reality. Mirror what works.

## Context

Only muni-world + its own Postgres in AWS, separate from the trading node, fully automatic except
the deliberate data load. jethro's working pipeline: GitHub secrets `AWS_ACCESS_KEY_ID`/
`AWS_SECRET_ACCESS_KEY`, region hardcoded `us-east-2`, Packer bakes the whole compose stack into an
x86 Ubuntu AMI with a systemd unit, and the workflow `run-instances`-replaces the tagged node behind
an IP-locked security group. No CDK, no OIDC, no parameter store.

One thing muni has that jethro's stateless dev node doesn't: **a database that must survive the
replace-the-instance deploy style.**

## Decision

1. **Same rails, one new workflow (`muni-deploy.yml`), nothing existing altered.** Same secrets,
   same region, same Packer→`run-instances` mechanics, x86 `t3a.small` (~$14/mo published on-demand
   rate; size is a dispatch input). Instance tagged `Name=muni-world`, `project=muni-world` (its own
   cost line, ADR-0011 pattern).
2. **Auto on green.** Every push to the muni branch touching `muni-world/**` runs the full test
   suite (lattice identities, QuantLib cross-validation, curve gates) and only on green bakes the
   AMI and replaces the node. Manual dispatch remains for re-deploys/instance-type changes.
3. **The workflow ensures its own AWS prerequisites, idempotently** (with the same admin secrets it
   already holds): the versioned backup bucket `muni-world-backups-<account>`, the
   `muni-world-node` role/instance-profile (SSM core + that bucket only), and the `muni-world-sg`
   security group — created on first run with :8090 open ONLY to `MUNI_ALLOWED_CIDR` (a repo
   variable = the owner's IP). **The IP-locked SG is the access control, the same posture jethro's
   working node uses**; no Caddy/TLS layer on this path.
4. **State survives the replace, automatically.** Before terminating the old node the workflow
   triggers a backup over SSM (pg_dump + OS-PDF inbox → S3; the same script also runs nightly via a
   baked timer). The AMI's systemd unit runs `restore-latest.sh` before first start: an EMPTY
   database is seeded from the newest S3 dump and the newest PDF archive; a database with data is
   never touched. Fresh account, empty bucket → clean Flyway bootstrap.
5. **The Pi's data arrives via the same bucket** (`scripts/muni-migrate-to-aws.sh` → the manual
   `Muni Restore` workflow, which is deliberately destructive: `--yes` required, pre-restore safety
   dump taken). This is the ONLY manual step in steady state.
6. **Secrets posture matches the path.** DB credentials are compose defaults inside a box whose only
   open port is the app's, IP-locked — jethro's working posture. `MUNI_CONTACT_EMAIL` (the SEC
   fair-access address) is baked from a repo variable into the node's env file, never committed.

## Consequences

- Bring-up from a phone: set repo variables `MUNI_ALLOWED_CIDR` (+ optionally `MUNI_CONTACT_EMAIL`,
  `MUNI_EIP_ALLOC_ID`), run *Muni Deploy*. Everything else — bucket, role, SG, AMI, node, schema,
  ingests, backups — is automatic.
- Each deploy replaces the node (immutable AMI), so "what is running" is always exactly one baked,
  tested commit; the S3 round-trip makes that safe for the DB.
- Without an Elastic IP the address changes per deploy (the workflow prints it in the run summary);
  set `MUNI_EIP_ALLOC_ID` for a stable one.
- If the trading deployment ever moves to the CDK/OIDC design, muni can follow — this ADR's first
  draft documents that shape — but muni does not lead that migration.
