#!/usr/bin/env bash
set -Eeuo pipefail

server_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$HOME/.m2"
exec docker run --rm \
  -v "$server_root:/workspace" \
  -v "$HOME/.m2:/root/.m2" \
  -w /workspace \
  maven:3.9.11-eclipse-temurin-21 mvn "$@"
