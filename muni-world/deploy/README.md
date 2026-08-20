# muni-world on AWS — runbook (muni ADR-0021)

Same rails as the WORKING jethro deployment (bake an AMI, replace the node), same repo secrets,
same region (`us-east-2`), nothing of jethro's touched. Everything is automatic except loading
your collected data — and the whole bring-up works from a phone browser.

## Bring-up (one time, all in web UIs)

1. **Nothing to configure.** The AWS secrets already exist (jethro's bake uses them), and the
   allowed IP is **copied from jethro's existing IP-locked security group** on first run.
   Optional variables, only if wanted: `MUNI_ALLOWED_CIDR` (override the allowed IP),
   `MUNI_CONTACT_EMAIL` (SEC fair-access contact — without it the EDGAR ingest is refused with a
   403, everything else runs), `MUNI_EIP_ALLOC_ID` (stable IP across deploys).
2. **Run it**: merge the PR (`claude/muni-world-aws-deploy` → `master`), then Actions →
   *Muni Deploy* → Run workflow (or just push to the muni branch). The
   workflow tests, then idempotently creates the backup bucket + node role + security group,
   bakes the AMI (app image + Postgres + systemd units inside), launches the node and prints
   `http://<ip>:8090` in the run summary. First boot: Flyway builds the schema, the curve and
   fund ingests start on their own.

## Every deploy after that — automatic

Push to `claude/muni-world-aws-deploy` touching `muni-world/**` → full test suite → on green:
bake a fresh AMI of that exact commit, **back the old node up to S3 over SSM**, launch the new
node (its boot seeds the database and OS PDFs from the newest backup), retire the old one. The
DB survives every deploy; nightly backups run on a baked systemd timer as well.

## Loading the data from the Pi — the one manual step

```bash
# On the Pi (uses your normal AWS credentials; bucket name is found by convention):
scripts/muni-migrate-to-aws.sh
```
Then: Actions → *Muni Restore* → Run workflow (`s3_key: latest-migrate`). Deliberately manual and
destructive (drops + replaces the `muni` schema, overlays the PDFs) — it takes a pre-restore
safety dump to S3 first, so even a mistake is reversible.

## Day 2

- **Where is it?** The *Muni Deploy* run summary prints the URL; with `MUNI_EIP_ALLOC_ID` set the
  IP never changes.
- **Your IP changed?** Update `MUNI_ALLOWED_CIDR` and add the new source to the `muni-world-sg`
  security group in the EC2 console (or delete the SG and let the next deploy recreate it).
- **Logs:** EC2 console → instance → Connect → Session Manager →
  `docker compose -f /opt/muni-world/muni-world/deploy/docker-compose.aws.yml logs muni`.
- **Backups:** `s3://muni-world-backups-<account>/db/` and `/os-inbox/`, versioned; also taken
  automatically before every node replacement.
- **Costs:** everything is tagged `project=muni-world` — its own line in Cost Explorer.
