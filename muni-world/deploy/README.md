# muni-world on AWS — runbook (muni ADR-0021)

Only muni-world + its own Postgres, on its own node, with its own pipeline. Nothing here touches the
trading platform's stack, workflows, or node — and vice versa.

## What exists after `cdk deploy MuniWorld`

One CloudFormation stack (`infra/…/MuniWorldStack.java`, tagged `project=muni-world` = its own cost
line): a `t4g.small` ARM node (Ubuntu 24.04, 20GiB encrypted gp3, Elastic IP, **always on** — muni's
value is its daily ingest cadence), an ECR repo `muni-world`, a **versioned S3 backup bucket**
(RETAINed — survives teardown), and the node IAM role (SSM, ECR pull, `/muni/prod/*` params, the bucket). Deploy permissions are
attached ADDITIVELY to jethro's existing `jethro-deploy` role — no new role, no new GitHub
variables. Security group: 80/443 only; SSM needs no inbound.

On the node (via user-data → `bootstrap.sh`): Docker + compose, the repo at `/opt/muni-world`, a
systemd unit restoring the stack on reboot, and a nightly backup timer (`backup-to-s3.sh`: pg_dump +
the OS-PDF inbox → S3; also runs before every deploy).

Compose (`docker-compose.aws.yml`): `postgres` (own database `muni`), `muni` (the jar; :8090 never
published on the host), `caddy` (TLS + basic auth, the only public surface).

## First bring-up

```bash
# 1. Synthesise + deploy the stack (from the repo root; AWS creds configured)
cd infra && npx cdk deploy MuniWorld
#    -c muniInstanceType=t4g.small   -c muniGithubBranch=master     # owner dials

# 2. Set the three SecureStrings the stack CANNOT create (secrets never enter git):
aws ssm put-parameter --type SecureString --name /muni/prod/POSTGRES_PASSWORD    --value '...'
aws ssm put-parameter --type SecureString --name /muni/prod/MUNI_BASIC_AUTH_HASH --value '<bcrypt>'
#    bcrypt: docker run --rm caddy:2-alpine caddy hash-password --plaintext 'yourpassword'
aws ssm put-parameter --type SecureString --name /muni/prod/MUNI_CONTACT_EMAIL   --value 'you@...'
#    (SEC fair-access contact — sec.gov 403s the EDGAR fetch without it)
#    private repo? also: /muni/prod/GITHUB_TOKEN

# 3. Point DNS (or use <eip-with-dashes>.nip.io) and set the domain param:
aws ssm put-parameter --overwrite --name /muni/prod/MUNI_DOMAIN --value 'muni.yourdomain.com'

# 4. GitHub variables: NOTHING TO CREATE. The workflows reuse jethro's existing AWS_REGION and
#    AWS_DEPLOY_ROLE_ARN (the stack additively grants that role the muni permissions), and the
#    muni node is found at deploy time by its project=muni-world tag.

# 5. Push to the muni branch (or Actions -> "Muni Deploy" manually) — tests gate, then the whole
#    stack (Postgres + app + Caddy) comes up by itself; Flyway creates the muni schema on first
#    boot. The ONLY manual act left afterwards is loading your collected data (next section).
```

## Moving the data you collected locally (Pi → AWS)

The transfer medium is the S3 bucket, **not git** — a dump + PDFs committed to a branch would sit in
repo history forever and can exceed GitHub's limits; both ends already have credentials for the bucket.

```bash
# On the Pi (fresh backup + upload; needs the same AWS creds you deploy with):
scripts/muni-migrate-to-aws.sh

# Then: Actions -> "Muni Restore" -> Run workflow (s3_key: latest-migrate)
```

The restore is deliberately destructive (drops + replaces the `muni` schema, overlays the PDFs into
`/data/os-inbox/processed`), so: it is manual-only, requires `--yes` on the node script, and takes a
**pre-restore safety dump to S3 first** — a mistaken restore is reversible. The archive carries its own
`flyway_schema_history`, so the app boots consistent on the restored state.

## Day 2

- **Deploy a change — AUTOMATIC:** push to `claude/muni-world-aws-deploy` touching `muni-world/**` →
  the full test suite runs → **only on green**, that exact commit deploys (backup first, health-gated).
  The node is found at deploy time by its `project=muni-world` tag; until the stack exists the
  lookup is empty and the deploy job skips cleanly — only tests run. Manual redeploys of any ref/component: Actions → *Muni Deploy*.
- **Backups:** nightly timer + pre-deploy, to `s3://<bucket>/db/` and `/os-inbox/`; bucket versioned.
- **Restore/DR:** *Muni Restore* workflow with any backup key.
- **Logs:** `aws ssm start-session --target <instance>` then `docker compose ... logs muni`.
- **Costs:** the `project=muni-world` tag isolates this stack in Cost Explorer; the account-wide
  budget alarm (JethroDev stack) still covers everything.
