# Deploying Jethro

**Default path: bake an AMI and launch it → [`../infra/packer/README.md`](../infra/packer/README.md).**

Jethro runs as one node (ADR-0013): the same `docker compose` stack that runs on the Pi
(redpanda, postgres, ollama, app), on an x86 EC2 instance. The recommended way to run it is
the **baked AMI** — GitHub builds an image with the whole stack (app + Ollama model) baked
in, then spawns an instance that boots straight into the running platform on `:8080`. It's
immutable, fast-booting, and the simplest to operate.

→ **Follow [`../infra/packer/README.md`](../infra/packer/README.md).** Everything else on
this page is an alternative you can ignore unless you specifically want it.

There's no data to migrate off the Pi: Postgres starts empty, Flyway migrates, the sim
regenerates everything.

---

## Cost guardrails (optional, recommended)

The account-wide **$100 budget** + **stop-when-idle schedule** live in the CDK stack
([`../infra/README.md`](../infra/README.md)); deploy just those if you want the guardrails
without the CDK-managed instance. Otherwise: use `t3a.xlarge` and **stop the instance when
idle** (~$45–65/mo vs ~$110 if left on 24/7).

## Alternatives (only if you need them)

- **Throwaway manual run** — `deploy/quickstart.sh`: on any box you can reach, `git clone`
  then `bash deploy/quickstart.sh` builds the app and brings the stack up on `:8080`. Good
  for a one-off; no image, no automation. Lock the security group to your IP.

- **Managed compose rollout with a public TLS URL** — `deploy/docker-compose.prod.yml` +
  `Caddyfile` + the **Deploy** workflow (`.github/workflows/deploy.yml`): builds the app to
  ECR and rolls it out via SSM, with Caddy fronting it (auto-TLS + HTTP basic auth) so you
  get a real `https://` address instead of an IP-locked `:8080`. Heavier to set up (ECR,
  OIDC role, SSM params); use it when you want a shareable authenticated URL. Runbook bits
  live in [`../infra/README.md`](../infra/README.md).

## Files here

| File | Used by |
|---|---|
| `quickstart.sh`, `quickstart.override.yml` | the throwaway manual run |
| `docker-compose.prod.yml`, `Caddyfile`, `remote-deploy.sh`, `bootstrap.sh`, `.env.example` | the managed compose-rollout alternative |
