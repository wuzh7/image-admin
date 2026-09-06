#!/usr/bin/env bash
set -Eeuo pipefail

app_root="${IMAGE_ADMIN_HOME:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
env_file="$app_root/.env"
compose_file="$app_root/compose.yml"

echo "当前镜像:"
grep -E '^(BACKEND_IMAGE|FRONTEND_IMAGE)=' "$env_file"
echo
docker compose --env-file "$env_file" -f "$compose_file" ps
