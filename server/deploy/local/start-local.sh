#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
compose_file="$script_dir/compose.yml"
env_file="$script_dir/.env.local"
server_root="$(cd -- "$script_dir/../.." && pwd)"
model="$server_root/models/sscd_disc_mixup.torchscript.pt"
expected_hash='9F26BD4C848CC19B73D2AE92EEA6E04886F61A7B764CEB7A13AEEE62E6A6DB56'

command -v docker >/dev/null || { echo '未找到 Docker，请先启动 Docker Desktop。' >&2; exit 1; }
docker info >/dev/null || { echo 'Docker Desktop 尚未启动。' >&2; exit 1; }
docker compose version >/dev/null || { echo '未检测到 Docker Compose V2。' >&2; exit 1; }

if [[ ! -f "$env_file" ]]; then
  cp "$script_dir/.env.example" "$env_file"
  echo "已创建 $env_file，请先填写本地配置后重试。"
  exit 1
fi

if [[ ! -f "$model" ]]; then
  echo "缺少 SSCD 模型：$model" >&2
  exit 1
fi
actual_hash="$(shasum -a 256 "$model" | awk '{print toupper($1)}')"
[[ "$actual_hash" == "$expected_hash" ]] || {
  echo "SSCD 模型 SHA-256 不匹配：$actual_hash" >&2
  exit 1
}

pull_image() {
  local image="$1"
  for attempt in 1 2 3; do
    if docker pull "$image"; then
      return 0
    fi
    [[ "$attempt" -lt 3 ]] && sleep $((attempt * 5))
  done
  return 1
}

# 先顺序拉取所有外部基础镜像，避免 BuildKit 并发拉取时再次触发代理超时。
for image in \
  mysql:8.4.9 \
  redis:8.4.5 \
  qdrant/qdrant:v1.18.3 \
  maven:3.9.11-eclipse-temurin-21 \
  eclipse-temurin:21-jre-jammy \
  node:24-alpine \
  nginx:1.29-alpine; do
  pull_image "$image"
done

compose=(docker compose --project-name image-admin-local --env-file "$env_file" -f "$compose_file")
"${compose[@]}" up --build -d mysql redis qdrant
"${compose[@]}" --profile maintenance run --rm migrate
"${compose[@]}" up --build -d backend render-worker frontend
"${compose[@]}" ps

echo '启动完成：http://localhost:8088'
