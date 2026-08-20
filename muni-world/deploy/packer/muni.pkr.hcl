// Bakes muni-world into an x86-64 Ubuntu AMI (muni ADR-0021) — the SAME immutable path that
// works for jethro (infra/packer/jethro.pkr.hcl): docker compose stack baked in, systemd unit
// starts it on boot, instances launched from the AMI need no build and no registry.
//
// Driven by .github/workflows/muni-deploy.yml; buildable by hand the same way:
//   cd muni-world/deploy/packer && packer init . && packer build \
//     -var region=us-east-2 -var src_archive=/path/to/muni-src.tgz .

packer {
  required_plugins {
    amazon = {
      source  = "github.com/hashicorp/amazon"
      version = ">= 1.3.0"
    }
  }
}

variable "region" {
  type    = string
  default = "us-east-2"   // same hardcoded region as the jethro bake
}

variable "build_instance_type" {
  type    = string
  default = "t3a.large"   // throwaway build box (gradle build inside docker); runtime type chosen at launch
}

variable "src_archive" {
  type        = string
  description = "Clean repo tarball (git archive HEAD), uploaded and unpacked to /opt/muni-world"
  default     = "muni-src.tgz"
}

variable "contact_email" {
  type        = string
  description = "SEC fair-access contact (sec.gov 403s EDGAR fetches without it) — from a repo variable, never committed"
  default     = ""
  sensitive   = true
}

source "amazon-ebs" "muni" {
  region          = var.region
  instance_type   = var.build_instance_type
  ssh_username    = "ubuntu"
  ami_name        = "muni-world-{{timestamp}}"
  ami_description = "muni-world (x86-64) - app image + postgres baked, restore-from-S3 on first boot"

  source_ami_filter {
    filters = {
      name                = "ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"
      virtualization-type = "hvm"
      root-device-type    = "ebs"
    }
    owners      = ["099720109477"] // Canonical
    most_recent = true
  }

  launch_block_device_mappings {
    device_name           = "/dev/sda1"
    volume_size           = 20
    volume_type           = "gp3"
    delete_on_termination = true
  }

  tags = {
    project = "muni-world"
    service = "muni-world"
    Name    = "muni-world-ami"
  }
}

build {
  sources = ["source.amazon-ebs.muni"]

  provisioner "file" {
    source      = var.src_archive
    destination = "/tmp/muni-src.tgz"
  }

  provisioner "shell" {
    script           = "provision.sh"
    environment_vars = ["MUNI_CONTACT_EMAIL=${var.contact_email}"]
    timeout          = "30m"
  }

  post-processor "manifest" {
    output = "manifest.json"
  }
}
