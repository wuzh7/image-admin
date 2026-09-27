#!/usr/bin/env bash
set -Eeuo pipefail

instance_id='i-bp10t7y63rvasdu34imd'
remote_root='/data/app/image-admin'
script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$script_dir/../../.." && pwd)
version=$(date '+%Y%m%d-%H%M%S')
skip_build=false

usage() {
    printf 'Usage: %s [--version VERSION] [--skip-build]\n' "$0"
}

while (($#)); do
    case "$1" in
        --version|-Version)
            (($# >= 2)) || { usage >&2; exit 2; }
            version=$2
            shift 2
            ;;
        --skip-build|-SkipBuild)
            skip_build=true
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            usage >&2
            exit 2
            ;;
    esac
done

[[ $version =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || { printf 'Invalid version: %s\n' "$version" >&2; exit 2; }

archive="$script_dir/releases/image-admin-$version.tar"
remote_prefix="/tmp/image-admin-publish-$version-$$"
remote_archive="$remote_prefix.tar"
remote_log="$remote_prefix.log"
remote_result="$remote_prefix.result"

ecs() {
    local command=$1 timeout=${2:-30} encoded
    encoded=$(printf '%s' "$command" | base64 | tr -d '\n')
    workbench exec --instance-id "$instance_id" --command "printf '%s' '$encoded' | base64 -d | bash" --timeout "$timeout" --output text
}

printf 'Release version: %s\nTarget ECS: %s:%s (main site only)\n' "$version" "$instance_id" "$remote_root"
for command in workbench docker shasum; do
    command -v "$command" >/dev/null || { printf 'Required command is missing: %s\n' "$command" >&2; exit 1; }
done

printf '[1/5] Checking ECS connection and production configuration...\n'
IFS= read -r -d '' preflight <<'BASH' || true
set -eu
cd /data/app/image-admin
test -f .env && test -f compose.yml
command -v flock >/dev/null
docker info >/dev/null
docker compose --env-file .env -f compose.yml config --images | grep -Fx 'image-admin-backend:latest' >/dev/null
docker compose --env-file .env -f compose.yml config --images | grep -Fx 'image-admin-frontend:latest' >/dev/null
docker compose --env-file .env -f compose.yml config --images | grep -Fx 'image-admin-render-worker:latest' >/dev/null
backend_container=$(docker compose --env-file .env -f compose.yml ps -q backend)
mysql_container=$(docker exec "$backend_container" printenv MYSQL_HOST)
database=$(docker exec "$backend_container" printenv MYSQL_DATABASE)
# 部门管理菜单及四个权限项必须存在，避免发布后功能从侧边栏消失。
department_menu_count=$(docker exec "$mysql_container" sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N "$1" -e "SELECT COUNT(*) FROM sys_menu WHERE menu_id IN (1761400000000000103,1761400000000001020,1761400000000001021,1761400000000001022,1761400000000001023)"' sh "$database")
test "$department_menu_count" = 5 || { echo 'Department menu is incomplete; apply server/script/sql/migrations/20260919_department_roles.sql before publishing.' >&2; exit 1; }
printf 'ECS connection and latest image configuration: OK\n'
BASH
ecs "$preflight"

if $skip_build; then
    [[ -f $archive ]] || { printf 'Release archive not found: %s\n' "$archive" >&2; exit 1; }
    printf '[2/5] Reusing existing package: %s\n' "$archive"
else
    [[ ! -e $archive ]] || { printf 'This version already has a package. Use --skip-build or a new version.\n' >&2; exit 1; }
    docker version >/dev/null
    printf '[2/5] Building backend image...\n'
    docker build --progress=plain --platform linux/amd64 -t "image-admin-backend:$version" \
        -f "$script_dir/Dockerfile.backend-runtime" "$repo_root/server"
    printf '[2/5] Building frontend image...\n'
    docker build --progress=plain --platform linux/amd64 -t "image-admin-frontend:$version" \
        --build-context "deploy=$script_dir" -f "$script_dir/Dockerfile.frontend-runtime" "$repo_root/web"
    printf '[2/5] Building render worker image...\n'
    docker build --progress=plain --platform linux/amd64 -t "image-admin-render-worker:$version" \
        -f "$repo_root/server/deploy/local/Dockerfile.render-worker" "$repo_root/server"
    mkdir -p "$(dirname "$archive")"
    printf '[3/5] Packaging images...\n'
    if ! docker save -o "$archive" "image-admin-backend:$version" "image-admin-frontend:$version" "image-admin-render-worker:$version"; then
        rm -f "$archive"
        printf 'Release archive export failed; incomplete package removed.\n' >&2
        exit 1
    fi
fi

archive_size=$(stat -f '%z' "$archive")
((archive_size > 0 && archive_size <= 1073741824)) || { printf 'Archive must be nonempty and no larger than 1 GiB.\n' >&2; exit 1; }
checksum=$(shasum -a 256 "$archive" | awk '{print $1}')
printf '[4/5] Uploading %s MiB through Workbench...\n' "$((archive_size / 1024 / 1024))"
ecs "test ! -e '$remote_archive'" >/dev/null
if ! workbench upload "$archive" "$remote_archive" --instance-id "$instance_id" --output text; then
    printf 'Upload failed. Reuse this package with: %s --version %s --skip-build\n' "$0" "$version" >&2
    exit 1
fi

printf '[5/5] Verifying package, updating images, and waiting for health checks...\nRemote deployment log: %s\n' "$remote_log"
IFS= read -r -d '' remote_script <<'BASH' || true
set -Eeuo pipefail
trap 'code=$?; trap - EXIT; printf "%s\n" "$code" > "__RESULT__"; rm -f "__ARCHIVE__"; exit "$code"' EXIT
cd '__ROOT__'
exec 9>.publish.lock
flock -n 9 || { echo 'Another deployment is running. Wait before publishing again.' >&2; exit 2; }
echo '[ECS 1/4] Verifying uploaded archive...'
printf '%s  %s\n' '__CHECKSUM__' '__ARCHIVE__' | sha256sum -c -
old_backend=$(docker inspect --format '{{.Image}}' "$(docker compose --env-file .env -f compose.yml ps -q backend)")
old_frontend=$(docker inspect --format '{{.Image}}' "$(docker compose --env-file .env -f compose.yml ps -q frontend)")
old_worker=$(docker compose --env-file .env -f compose.yml ps -q render-worker)
if [ -n "$old_worker" ]; then old_worker=$(docker inspect --format '{{.Image}}' "$old_worker"); fi
echo '[ECS 2/4] Loading Docker images...'
docker load -i '__ARCHIVE__'
docker image inspect 'image-admin-backend:__VERSION__' 'image-admin-frontend:__VERSION__' 'image-admin-render-worker:__VERSION__' >/dev/null
test "$(docker image inspect 'image-admin-backend:__VERSION__' --format '{{.Architecture}}')" = amd64
test "$(docker image inspect 'image-admin-frontend:__VERSION__' --format '{{.Architecture}}')" = amd64
test "$(docker image inspect 'image-admin-render-worker:__VERSION__' --format '{{.Architecture}}')" = amd64
docker image tag 'image-admin-backend:__VERSION__' image-admin-backend:latest
docker image tag 'image-admin-frontend:__VERSION__' image-admin-frontend:latest
docker image tag 'image-admin-render-worker:__VERSION__' image-admin-render-worker:latest
echo '[ECS 3/4] Updating containers and waiting for health checks (up to 300s)...'
if ! docker compose --env-file .env -f compose.yml up -d --force-recreate --wait --wait-timeout 300 backend render-worker frontend ||
   ! curl -fsS --max-time 10 http://127.0.0.1:8088/health >/dev/null; then
    echo 'Deployment failed. Restoring previous images.' >&2
    docker image tag "$old_backend" image-admin-backend:latest
    docker image tag "$old_frontend" image-admin-frontend:latest
    if [ -n "$old_worker" ]; then
        docker image tag "$old_worker" image-admin-render-worker:latest
        docker compose --env-file .env -f compose.yml up -d --force-recreate --wait --wait-timeout 300 backend render-worker frontend
    else
        docker compose --env-file .env -f compose.yml stop render-worker
        docker compose --env-file .env -f compose.yml up -d --force-recreate --wait --wait-timeout 300 backend frontend
    fi
    exit 10
fi
printf '%s\n' '__VERSION__' > current-version
cleanup_release_images() {
    docker image tag "$old_backend" image-admin-backend:rollback || return
    docker image tag "$old_frontend" image-admin-frontend:rollback || return
    if [ -n "$old_worker" ]; then docker image tag "$old_worker" image-admin-render-worker:rollback || return; fi
    for repository in image-admin-backend image-admin-frontend image-admin-render-worker; do
        images=$(docker image ls "$repository" --format '{{.Repository}}:{{.Tag}}') || return
        while IFS= read -r image; do
            case "$image" in
                "$repository:latest"|"$repository:rollback"|"$repository:__VERSION__") ;;
                *) docker image rm "$image" || return ;;
            esac
        done <<< "$images"
    done
}
if cleanup_release_images; then
    echo 'Old application release images cleaned; current and rollback images kept.'
else
    echo 'Warning: deployment succeeded, but old image cleanup failed.' >&2
fi
echo '[ECS 4/4] Checking final container status...'
docker compose --env-file .env -f compose.yml ps backend render-worker frontend
echo 'Production release succeeded: __VERSION__'
BASH
remote_script=${remote_script//__ROOT__/$remote_root}
remote_script=${remote_script//__VERSION__/$version}
remote_script=${remote_script//__ARCHIVE__/$remote_archive}
remote_script=${remote_script//__RESULT__/$remote_result}
remote_script=${remote_script//__CHECKSUM__/$checksum}
encoded_script=$(printf '%s' "$remote_script" | base64 | tr -d '\n')
ecs "set -eu; printf '%s' '$encoded_script' | base64 -d > '$remote_prefix.sh'; nohup bash '$remote_prefix.sh' > '$remote_log' 2>&1 < /dev/null &" >/dev/null

next_line=1
deadline=$((SECONDS + 1200))
IFS= read -r -d '' poll_template <<'BASH' || true
set -eu
count=$(wc -l < '__LOG__')
printf 'LINES=%s\n' "$count"
if test "$count" -ge __LINE__; then sed -n "__LINE__,${count}p" '__LOG__'; fi
if test -f '__RESULT__'; then printf 'RESULT='; cat '__RESULT__'; fi
BASH
poll_template=${poll_template//__LOG__/$remote_log}
poll_template=${poll_template//__RESULT__/$remote_result}

while ((SECONDS < deadline)); do
    poll_command=${poll_template//__LINE__/$next_line}
    snapshot=$(ecs "$poll_command")
    line_count=$(printf '%s\n' "$snapshot" | sed -n '1s/^LINES=//p')
    [[ $line_count =~ ^[0-9]+$ ]] || { printf 'Cannot read deployment log: %s\n' "$remote_log" >&2; exit 1; }
    next_line=$((line_count + 1))
    printf '%s\n' "$snapshot" | sed '1d;/^RESULT=[0-9][0-9]*$/d'
    result=$(printf '%s\n' "$snapshot" | sed -n 's/^RESULT=\([0-9][0-9]*\)$/\1/p' | tail -1)
    if [[ -n $result ]]; then
        ((result == 0)) || { printf 'Production deployment failed (exit code %s). Log: %s\n' "$result" "$remote_log" >&2; exit 1; }
        printf 'Done: %s. Existing .env and business data were not modified.\n' "$version"
        exit 0
    fi
    sleep 5
done

printf 'Stopped waiting after 20 minutes. Inspect: %s\n' "$remote_log" >&2
exit 1
