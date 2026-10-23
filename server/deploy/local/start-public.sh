#!/usr/bin/env bash
# 在任意一台 Linux 主机上一键拉起 image-admin 全栈，并对公网提供 HTTP 访问。
#
# 用法（全新机器，Ubuntu 22.04 / 24.04 x86_64）：
#   curl -fsSL <本文件的原始地址> | bash
# 或先在仓库里执行：
#   bash server/deploy/local/start-public.sh
#
# 说明：
#   - 复用已被验证的 start-local.sh 完成拉镜像、构建、迁移、启动；
#   - 本脚本在此基础上追加：Docker 安装、随机密钥生成、按内存自动调优、
#     公网端口暴露、IP 探测与结果自检。
#   - 使用 local profile：图片存本地磁盘，不需要 OSS，也不需要任何付费 API
#     （只有"图片侵权分析"需要 ARK_API_KEY，留空则该功能不可用）。
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
env_file="$script_dir/.env.local"
compose_public="$script_dir/compose.public.yml"

say() { printf '\033[1;36m[image-admin]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[image-admin]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- Docker ----
ensure_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    say '未检测到 Docker，开始安装…'
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y ca-certificates curl gnupg
    curl -fsSL https://get.docker.com | sh
  fi
  docker info >/dev/null 2>&1 || die 'Docker 守护进程没起来，请确认 Docker Desktop / dockerd 已运行。'
  docker compose version >/dev/null 2>&1 || {
    say '安装 Docker Compose 插件…'
    apt-get install -y docker-compose-plugin
  }
}

# ------------------------------------------------------------- 内存调优 ----
# 依据可用内存选择后端堆大小与并发度。DJL 的 SSCD 推理是内存峰值来源，
# 小机型上必须压低并发，否则容易 OOM。
memory_gb() {
  local kb mb
  kb=$(awk '/MemTotal/ {print $2}' /proc/meminfo)
  echo $((kb / 1024 / 1024))
}

plan_for_memory() {
  local gb=$1
  if (( gb >= 8 )); then
    JAVA_OPTS='-Xms512m -Xmx3g -Djava.awt.headless=true'
    RELAY_WORKERS=2; FEATURE_BATCH=1; UPLOAD_CONC=8; RENDER_CONC=2
    TIER='标准（>=8 GB）'
  elif (( gb >= 4 )); then
    JAVA_OPTS='-Xms256m -Xmx1500m -Djava.awt.headless=true'
    RELAY_WORKERS=1; FEATURE_BATCH=1; UPLOAD_CONC=4; RENDER_CONC=1
    TIER='紧凑（4 GB）'
  else
    JAVA_OPTS='-Xms256m -Xmx900m -Djava.awt.headless=true'
    RELAY_WORKERS=1; FEATURE_BATCH=1; UPLOAD_CONC=2; RENDER_CONC=1
    TIER='极限（<4 GB，SSCD 推理可能内存不足）'
  fi
}

# ------------------------------------------------------------- 环境文件 ----
rand_secret() { openssl rand -hex 16; }

prepare_env() {
  local public_host=$1
  if [[ -f "$env_file" ]]; then
    say "复用已有配置：$env_file"
    return
  fi
  say "生成配置：$env_file（自动生成随机密钥）"
  cat > "$env_file" <<EOF
# 由 start-public.sh 自动生成。请勿提交到版本库。
MYSQL_ROOT_PASSWORD=$(rand_secret)
MYSQL_DATABASE=wuzh-image-admin
MYSQL_USER=root
MYSQL_PASSWORD=$(rand_secret)
REDIS_PASSWORD=$(rand_secret)
BACKEND_JAVA_OPTS=${JAVA_OPTS}
ORDER_DETAIL_API_KEY=$(rand_secret)
TRO_API_KEY=$(rand_secret)
IMAGE_INFRINGEMENT_API_KEY=$(rand_secret)
IMAGE_INFRINGEMENT_LEASE_MINUTES=1440
RENDER_WORKER_TOKEN=$(rand_secret)
RENDER_WORKER_CONCURRENCY=${RENDER_CONC}
RENDER_WORKER_POLL_MS=2000
IMAGE_TASK_WORKERS=1
IMAGE_INFRINGEMENT_RELAY_WORKERS=${RELAY_WORKERS}
IMAGE_FEATURE_BATCH_SIZE=${FEATURE_BATCH}
IMAGE_UPLOAD_CONCURRENCY=${UPLOAD_CONC}
STORE_LISTING_PUBLIC_BASE_URL=http://${public_host}/prod-api/store/listing-exports/public-results?key=
# 图片侵权分析依赖火山方舟；留空则该功能不可用，其余功能不受影响。
ARK_API_KEY=
EOF
  chmod 600 "$env_file"
}

# --------------------------------------------------------- SSCD 模型 ----
# 约 94 MB 的模型二进制不进 Git，克隆后的机器必须单独下载。
# 国内机器访问 fbaipublicfiles.com 可能失败，失败时给出手动上传指引。
ensure_model() {
  local model="$script_dir/../../models/sscd_disc_mixup.torchscript.pt"
  if [[ -f "$model" ]]; then
    say "SSCD 模型已存在：$model"
    return
  fi
  say '未找到 SSCD 模型，尝试从官方地址下载（约 94 MB）…'
  mkdir -p "$(dirname -- "$model")"
  if curl -fL --max-time 900 \
    https://dl.fbaipublicfiles.com/sscd-copy-detection/sscd_disc_mixup.torchscript.pt \
    -o "$model"; then
    say '模型下载完成。'
  else
    rm -f "$model"
    die "模型下载失败。请从开发机手动上传：
    scp <本地仓库>/server/models/sscd_disc_mixup.torchscript.pt <user>@$(hostname -I 2>/dev/null | awk '{print $1}'):<仓库目录>/server/models/
  或改用可访问该域名的网络后重跑本脚本。"
  fi
}

# ------------------------------------------------------------- 公网地址 ----
detect_public_host() {
  local host
  for svc in https://api.ipify.org https://ifconfig.me/ip https://myip.ipip.net; do
    host=$(curl -fsS --max-time 8 "$svc" 2>/dev/null | tr -d '[:space:]' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -1) && [[ -n "$host" ]] && { echo "$host"; return; }
  done
  # 拿不到公网 IP 时退回本机第一个地址
  hostname -I 2>/dev/null | awk '{print $1}'
}

# ================================================================ main ====
[[ -r /proc/meminfo ]] || die '本脚本只能在 Linux 上运行。'
ensure_docker

MEM_GB=$(memory_gb)
plan_for_memory "$MEM_GB"
say "可用内存约 ${MEM_GB} GB，采用${TIER}档配置：${JAVA_OPTS}"

HOST_IP=$(detect_public_host)
[[ -n "$HOST_IP" ]] || die '无法探测本机 IP，请手动确认后重试。'
say "探测到的访问地址：http://${HOST_IP}"

prepare_env "$HOST_IP"
ensure_model

say '开始构建并启动全栈（首次约需 10~25 分钟）…'
bash "$script_dir/start-local.sh"

say '把前端暴露到公网 80 端口…'
docker compose --project-name image-admin-local --env-file "$env_file" \
  -f "$script_dir/compose.yml" -f "$compose_public" up -d --remove-orphans frontend

# 放行本机防火墙；云厂商安全组需自行放开，脚本无法代劳。
if command -v ufw >/dev/null 2>&1 && ufw status | grep -qi active; then
  ufw allow 80/tcp >/dev/null 2>&1 && say '已放行本机 ufw 的 80 端口。'
fi

say '等待前端就绪…'
for _ in $(seq 1 30); do
  if curl -fsS --max-time 5 "http://127.0.0.1/" -o /dev/null 2>/dev/null; then break; fi
  sleep 5
done

printf '\n'
if curl -fsS --max-time 10 "http://127.0.0.1/" -o /dev/null 2>/dev/null; then
  say "本地自检通过。访问地址：http://${HOST_IP}"
else
  say '尚未就绪，请查看后端日志：docker compose --project-name image-admin-local logs -f backend'
fi

docker compose --project-name image-admin-local ps

printf '\n\033[1;33m注意\033[0m\n'
printf '  · 若外网打不开，多半是云厂商安全组没放开 80 端口，需去控制台手动添加入站规则。\n'
printf '  · 这是明文 HTTP 且用的是随机强口令，仅建议临时演示；不要导入真实业务数据。\n'
printf '  · 停止：docker compose --project-name image-admin-local down\n'
printf '    清理数据（不可恢复）：bash %s/reset-local.ps1 的等价命令或删除对应数据卷。\n' "$script_dir"
