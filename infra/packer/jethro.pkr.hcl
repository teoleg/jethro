// Bakes the full Jethro stack into an x86-64 Ubuntu AMI — the same structure that runs on
// the Pi (docker compose: redpanda, postgres, ollama, app), plus the app image built and the
// Ollama model pre-pulled, so an instance launched from this AMI boots straight into a
// running stack (systemd `jethro.service`) with no build and no model download.
//
// Build:  cd infra/packer && packer init . && packer build \
//           -var region=eu-west-1 -var src_archive=/path/to/jethro-src.tgz .
// The GitHub `Bake AMI` workflow does this and can spawn an instance from the result.

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
  default = "eu-west-1"
}

variable "build_instance_type" {
  type    = string
  default = "t3a.large" // the throwaway build box; the runtime instance type is chosen at launch
}

variable "ai_model" {
  type    = string
  default = "qwen2.5:1.5b"
}

variable "src_archive" {
  type        = string
  description = "Path (on the machine running packer) to a clean repo tarball, e.g. git archive HEAD"
  default     = "jethro-src.tgz"
}

source "amazon-ebs" "jethro" {
  region        = var.region
  instance_type = var.build_instance_type
  ssh_username  = "ubuntu"
  ami_name      = "jethro-{{timestamp}}"
  ami_description = "Jethro full stack (x86-64) — app image + Ollama model baked in"

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
    volume_size           = 40
    volume_type           = "gp3"
    delete_on_termination = true
  }

  tags = {
    project = "jethro"
    service = "platform"
    Name    = "jethro-ami"
  }
}

build {
  sources = ["source.amazon-ebs.jethro"]

  // Upload a clean source tarball (no .git/build) and bake.
  provisioner "file" {
    source      = var.src_archive
    destination = "/tmp/jethro-src.tgz"
  }

  provisioner "shell" {
    script           = "provision.sh"
    environment_vars = ["AI_MODEL=${var.ai_model}"]
    // give docker build + model pull room
    timeout          = "45m"
  }

  // Emit the resulting AMI id for the workflow to read.
  post-processor "manifest" {
    output = "manifest.json"
  }
}
