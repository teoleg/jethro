# Baked AMI path (immutable)

Bakes the **exact structure you run on the Pi** (docker compose: redpanda, postgres, ollama,
app) into an **x86-64 Ubuntu AMI** — with the app image built and the Ollama model
pre-pulled — then spawns an instance from it. An instance from this AMI boots straight into
the running stack on `:8080` with no build and no downloads. This is the immutable
alternative to the `deploy/` compose-rollout path; pick whichever you prefer.

## How it works

1. **Packer** (`jethro.pkr.hcl` + `provision.sh`) launches a throwaway build box, installs
   Docker, unpacks the repo, builds the app image, pulls the base images, bakes the Ollama
   model into a volume, installs a `jethro.service` systemd unit, and snapshots the result
   to an AMI.
2. The **`Bake AMI` GitHub workflow** (`.github/workflows/bake.yml`) runs Packer and — if
   `launch` is on — spawns a fresh instance from the new AMI, associates your stable IP (if
   set), and terminates the previous one.

## One-time setup (mobile-friendly)

In the AWS console (kappara's account):

1. **A security group** locked to *your* IP, allowing inbound **8080** (and 22 if you want
   SSH). There's no TLS/auth on `:8080`, so keep it to your IP. Note its `sg-…` id.
2. *(optional)* An **Elastic IP** for a stable address, and/or an **instance profile** with
   SSM if you want a browser shell. Note their ids.

In GitHub → repo Settings:

- **Secrets** (Actions): `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` — an IAM user with EC2
  + AMI permissions (kappara's is fine). *(OIDC is the hardening path — the CDK provisions a
  role for it if you'd rather not keep static keys.)*
- **Variables** (Actions): `AWS_REGION`, `JETHRO_SG_ID`, and optionally `JETHRO_SUBNET_ID`,
  `JETHRO_INSTANCE_PROFILE`, `JETHRO_EIP_ALLOC_ID`.

## Use it

GitHub → **Actions → Bake AMI → Run workflow**: pick the ref, the SLM, the instance type
(`t3a.xlarge` default), and whether to launch. It prints the instance's
`http://<ip>:8080` in the run summary. Give it ~60s after launch for the stack to come up.

Rebake whenever the code changes — each run produces a new AMI and rolls the instance.

## Cost (under $100)

`t3a.xlarge` is ~$0.15/hr — fine for demos, but **stop the instance when you're not using
it** (or deploy the CDK's stop-when-idle schedule + account budget). Left running 24/7 it's
~$110/mo; stopped nights/weekends it's ~$45–65. Old AMIs + their snapshots cost a few cents
each — prune occasionally (`aws ec2 deregister-image` + delete the snapshot).

## Not baked in (by design)

- **TLS / auth** — `:8080` is plain; that's why the SG is IP-locked. The `deploy/` path adds
  Caddy (TLS + basic auth) if you want a public URL.
- **Persistent data** — Postgres/LMDB start fresh (sim regenerates everything), same as the
  Pi.
