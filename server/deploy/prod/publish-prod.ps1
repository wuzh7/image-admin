param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')]
    [string]$Version = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$instanceId = 'i-bp10t7y63rvasdu34imd'
$remoteRoot = '/data/app/image-admin'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$archive = Join-Path $repoRoot "image-admin-$Version.tar"
$remotePrefix = "$remoteRoot/.publish-$Version-$([Guid]::NewGuid().ToString('N'))"
$remoteArchive = "$remoteRoot/image-admin-$Version.tar"
$remoteLog = "$remotePrefix.log"
$remoteResult = "$remotePrefix.result"
$publishStarted = Get-Date

function Invoke-LoggedCommand {
    param([string]$Command, [string[]]$CommandArguments, [string]$Label)
    $started = Get-Date
    $nextHeartbeat = $started.AddSeconds(10)
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $Label started."
    $job = Start-Job -ArgumentList $Command, $CommandArguments -ScriptBlock {
        param($Executable, $ExecutableArguments)
        $ErrorActionPreference = 'Continue'
        $OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
        & $Executable @ExecutableArguments 2>&1 | ForEach-Object { "$_" }
        if ($LASTEXITCODE -ne 0) { throw "Command failed (exit code $LASTEXITCODE): $Executable" }
    }
    try {
        while ($true) {
            Wait-Job $job -Timeout 1 | Out-Null
            Receive-Job $job -ErrorAction Stop | ForEach-Object { Write-Host $_ }
            $elapsed = [int]((Get-Date) - $started).TotalSeconds
            if ($job.State -eq 'Completed') { break }
            if ($job.State -ne 'Running') { throw "$Label failed: $($job.ChildJobs[0].JobStateInfo.Reason)" }
            if ((Get-Date) -ge $nextHeartbeat) {
                Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $Label is still running (${elapsed}s elapsed)..."
                $nextHeartbeat = (Get-Date).AddSeconds(10)
            }
        }
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $Label completed (${elapsed}s)."
    } finally {
        if ($job.State -eq 'Running') { Stop-Job $job }
        Remove-Job $job -Force
    }
}

function Invoke-EcsCommand {
    param([string]$Command)
    # Windows PowerShell 5.1 strips embedded quotes in native arguments; encode the whole Bash command.
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Command.Replace("`r`n", "`n")))
    $wireCommand = "printf '%s' '$encodedCommand' | base64 -d | bash"
    $response = & workbench exec --instance-id $instanceId --command $wireCommand --timeout 30 --output json
    if ($LASTEXITCODE -ne 0) {
        throw "ECS command failed: $($response -join [Environment]::NewLine)"
    }
    $result = ($response -join [Environment]::NewLine) | ConvertFrom-Json
    if ($result.exit_code -ne 0) { throw "ECS command failed: $($result.stderr) $($result.output)" }
    if ($result.stderr) { Write-Host $result.stderr }
    return $result.output
}

Write-Host "Release version: $Version"
Write-Host "Target ECS: ${instanceId}:$remoteRoot (main site only)"
foreach ($command in @('workbench', 'docker')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command is missing: $command"
    }
}

Write-Host '[1/5] Checking ECS connection and production configuration...'
$preflight = @'
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
department_access_table_count=$(docker exec "$mysql_container" sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N "$1" -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=\"sys_dept_menu\""' sh "$database")
test "$department_access_table_count" = 1 || { echo 'Department permission table is missing; apply server/script/sql/migrations/20260928_access_control.sql before publishing.' >&2; exit 1; }
printf 'ECS connection and latest image configuration: OK\n'
'@
Write-Host (Invoke-EcsCommand $preflight)

if ($SkipBuild) {
    if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
        throw "Release archive not found: $archive. Use -Version <existing-version> -SkipBuild."
    }
    Write-Host "[2/5] Reusing existing package: $archive"
} else {
    if (Test-Path -LiteralPath $archive) { throw 'This version already has a package. Use -SkipBuild or a new version.' }
    docker version *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not ready.' }
    Write-Host '[2/5] Building frontend, backend and render worker images...'
    Invoke-LoggedCommand -Command 'docker' -Label 'Build 1/3: backend (Docker cache + Maven cache)' `
        -CommandArguments @('build', '--progress=plain', '-t', "image-admin-backend:$Version", `
            '--platform', 'linux/amd64', `
            '-f', (Join-Path $PSScriptRoot 'Dockerfile.backend-runtime'), (Join-Path $repoRoot 'server'))
    Invoke-LoggedCommand -Command 'docker' -Label 'Build 2/3: frontend (cached dependencies; no release-time lint)' `
        -CommandArguments @('build', '--progress=plain', '-t', "image-admin-frontend:$Version", `
            '--platform', 'linux/amd64', `
            '--build-context', "deploy=$PSScriptRoot", '-f', (Join-Path $PSScriptRoot 'Dockerfile.frontend-runtime'), `
            (Join-Path $repoRoot 'web'))
    Invoke-LoggedCommand -Command 'docker' -Label 'Build 3/3: render worker' `
        -CommandArguments @('build', '--progress=plain', '-t', "image-admin-render-worker:$Version", `
            '--platform', 'linux/amd64', '-f', (Join-Path $repoRoot 'server/deploy/local/Dockerfile.render-worker'), `
            (Join-Path $repoRoot 'server'))
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $archive) | Out-Null
    Write-Host '[3/5] Packaging images...'
    try {
        Invoke-LoggedCommand -Command 'docker' `
            -CommandArguments @('save', '-o', $archive, "image-admin-backend:$Version", "image-admin-frontend:$Version", "image-admin-render-worker:$Version") `
            -Label 'Exporting release archive'
    } catch {
        throw "Release archive export failed. Do not reuse the incomplete package. $_"
    }
}

$archiveInfo = Get-Item -LiteralPath $archive
if ($archiveInfo.Length -le 0 -or $archiveInfo.Length -gt 1GB) {
    throw 'Workbench upload requires a nonempty release archive no larger than 1 GiB.'
}
Write-Host "[$(Get-Date -Format 'HH:mm:ss')] Computing package SHA256..."
$checksum = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
Write-Host "[4/5] Uploading $([Math]::Round($archiveInfo.Length / 1MB, 1)) MiB through Workbench..."
# Unique remote name prevents overwrite prompts and collisions with other releases.
Invoke-EcsCommand "test ! -e '$remoteArchive'" | Out-Null
try {
    Invoke-LoggedCommand -Command 'workbench' `
        -CommandArguments @('upload', $archive, $remoteArchive, '--instance-id', $instanceId) `
        -Label 'Uploading package to ECS'
} catch {
    throw "Upload failed. Reuse this package with: .\publish-prod.ps1 -Version $Version -SkipBuild. $_"
}

Write-Host '[5/5] Verifying package, updating latest images, and waiting for health checks...'
Write-Host "Remote deployment log: $remoteLog"
$remoteScript = @'
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
'@
$remoteScript = $remoteScript.Replace('__ROOT__', $remoteRoot).Replace('__VERSION__', $Version)
$remoteScript = $remoteScript.Replace('__ARCHIVE__', $remoteArchive).Replace('__RESULT__', $remoteResult)
$remoteScript = $remoteScript.Replace('__CHECKSUM__', $checksum).Replace("`r`n", "`n")
$encodedScript = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remoteScript))
# Run remotely so a local terminal/network interruption does not stop container updates.
Invoke-EcsCommand "set -eu; printf '%s' '$encodedScript' | base64 -d > '$remotePrefix.sh'; nohup bash '$remotePrefix.sh' > '$remoteLog' 2>&1 < /dev/null &" | Out-Null
$nextLine = 1
$deployStarted = Get-Date
$deadline = (Get-Date).AddMinutes(20)
$pollCommand = @'
set -eu
count=$(wc -l < '__LOG__')
printf 'LINES=%s\n' "$count"
if [ "$count" -ge __LINE__ ]; then sed -n "__LINE__,${count}p" '__LOG__'; fi
if test -f '__RESULT__'; then printf 'RESULT='; cat '__RESULT__'; fi
'@
$pollCommand = $pollCommand.Replace('__LOG__', $remoteLog).Replace('__RESULT__', $remoteResult)
while ((Get-Date) -lt $deadline) {
    $snapshot = Invoke-EcsCommand ($pollCommand.Replace('__LINE__', $nextLine.ToString()))
    $lines = $snapshot -split "`r?`n"
    if ($lines[0] -notmatch '^LINES=(\d+)$') { throw "Cannot read deployment log. Check: $remoteLog" }
    $nextLine = [int]$Matches[1] + 1
    $completed = $null
    foreach ($line in $lines | Select-Object -Skip 1) {
        if ($line -match '^RESULT=(\d+)$') { $completed = [int]$Matches[1] }
        elseif ($line) { Write-Host $line }
    }
    if ($null -ne $completed) {
        if ($completed -ne 0) { throw "Production deployment failed (exit code $completed). Log: $remoteLog" }
        Write-Host "Done: $Version. Existing .env and business data were not modified."
        Write-Host "Total elapsed: $([int]((Get-Date) - $publishStarted).TotalSeconds)s."
        return
    }
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] ECS deployment is still running ($([int]((Get-Date) - $deployStarted).TotalSeconds)s elapsed)..."
    Start-Sleep -Seconds 5
}
throw "Stopped waiting after 20 minutes. The remote job may still be running; inspect $remoteLog before retrying."
