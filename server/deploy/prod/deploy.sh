#!/usr/bin/env bash
set -Eeuo pipefail

app_root="${IMAGE_ADMIN_HOME:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
release_dir="$app_root/releases"
env_file="$app_root/.env"
compose_file="$app_root/compose.yml"
version="${1:-}"

if [[ ! "$version" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
    echo "用法: $0 <发布版本>，例如: $0 20260906-01" >&2
    exit 2
fi

archive="$release_dir/image-admin-$version.tar"
manifest="$release_dir/image-admin-$version.env"
checksum="$archive.sha256"
[[ -f "$archive" && -f "$manifest" && -f "$checksum" && -f "$env_file" && -f "$compose_file" ]] || {
    echo "发布包、版本清单或生产配置不完整" >&2
    exit 3
}

read_image() {
    local key="$1" value
    value="$(awk -F= -v key="$key" '$1 == key { if (++count > 1 || NF != 2) exit 2; result = $2 } END { if (count != 1) exit 3; print result }' "$manifest")" || return 1
    [[ "$value" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*:[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || return 1
    printf '%s' "$value"
}

BACKEND_IMAGE="$(read_image BACKEND_IMAGE)"
FRONTEND_IMAGE="$(read_image FRONTEND_IMAGE)"
[[ -n "$BACKEND_IMAGE" && -n "$FRONTEND_IMAGE" ]] || {
    echo "版本清单必须包含 BACKEND_IMAGE 与 FRONTEND_IMAGE" >&2
    exit 4
}

echo "校验发布包: $(basename "$archive")"
(
    cd "$release_dir"
    sha256sum -c "$(basename "$checksum")"
)

backup="$(mktemp "$release_dir/.env-before-$version.XXXXXX")"
trap 'rm -f "$backup"' EXIT
cp -p "$env_file" "$backup"

restore_previous() {
    cp -p "$backup" "$env_file" || return 1
    docker compose --env-file "$env_file" -f "$compose_file" up -d --wait --wait-timeout 240 || return 1
}

update_image() {
    local key="$1" value="$2" temporary
    temporary="$(mktemp "$app_root/.env.XXXXXX")"
    awk -v key="$key" -v value="$value" '
        index($0, key "=") == 1 { print key "=" value; found = 1; next }
        { print }
        END { if (!found) exit 5 }
    ' "$env_file" > "$temporary"
    chmod 600 "$temporary"
    mv "$temporary" "$env_file"
}

echo "加载发布包: $(basename "$archive")"
docker load --input "$archive"
docker image inspect "$BACKEND_IMAGE" "$FRONTEND_IMAGE" >/dev/null

update_image BACKEND_IMAGE "$BACKEND_IMAGE"
update_image FRONTEND_IMAGE "$FRONTEND_IMAGE"

if ! docker compose --env-file "$env_file" -f "$compose_file" up -d --wait --wait-timeout 240; then
    echo "发布失败，正在恢复上一版本" >&2
    if ! restore_previous; then echo "严重错误：自动回滚也失败，请立即执行 status.sh 并人工处理" >&2; exit 20; fi
    exit 10
fi

if ! curl --fail --silent --show-error --max-time 10 http://127.0.0.1:8088/health >/dev/null; then
    echo "前端健康检查失败，正在恢复上一版本" >&2
    if ! restore_previous; then echo "严重错误：自动回滚也失败，请立即执行 status.sh 并人工处理" >&2; exit 21; fi
    exit 11
fi

rm -f "$backup"
echo "发布成功: $version"
