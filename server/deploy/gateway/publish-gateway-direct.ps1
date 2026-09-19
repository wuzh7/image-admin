param(
    [switch]$SkipTests = $true,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$sshUser = 'root'
$sshHost = '47.84.93.1'
$remoteRoot = '/opt/image-admin-ai-gateway'
$serverRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$jarPath = Join-Path $serverRoot 'wuzh-ai-gateway\target\wuzh-ai-gateway.jar'
$remotePrefix = "/tmp/image-admin-gateway-direct-$([Guid]::NewGuid().ToString('N'))"
$remoteJar = "$remotePrefix.jar"
$publishStarted = Get-Date
$sshTarget = "${sshUser}@${sshHost}"
$sshOptions = @('-o', 'ConnectTimeout=15', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=4', '-o', 'StrictHostKeyChecking=accept-new')

function Invoke-SshScript {
    param([string]$Script)
    $encodedScript = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Script.Replace("`r`n", "`n")))
    & ssh @sshOptions $sshTarget "printf '%s' '$encodedScript' | base64 -d | bash"
    if ($LASTEXITCODE -ne 0) { throw "ECS command failed (exit code $LASTEXITCODE)." }
}

Write-Host "Target ECS: ${sshTarget}:$remoteRoot (Singapore Gateway only)"
Write-Host 'Authentication: OpenSSH will prompt locally for the ECS password; it is not stored or printed.'
foreach ($command in @('ssh', 'scp', 'docker')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Required command is missing: $command" }
}

Write-Host '[1/4] Checking direct SSH access and existing Gateway configuration...'
$preflight = @'
set -eu
cd /opt/image-admin-ai-gateway
test -f .env && test -f compose.yml && test -f Dockerfile
command -v flock >/dev/null
docker info >/dev/null
docker compose --env-file .env config --images | grep -Fx 'image-admin-ai-gateway:latest' >/dev/null
id=$(docker compose --env-file .env ps -q ai-gateway)
test -n "$id"
prompt=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$id" | sed -n 's/^GEMINI_PROMPT_FILE=//p')
test -n "$prompt" && docker exec "$id" test -s "$prompt"
echo 'ECS connection: OK. Existing .env and server prompt will be preserved.'
'@
Invoke-SshScript $preflight

Write-Host '[2/4] Building Gateway JAR with cached Maven dependencies...'
if ($SkipBuild) {
    Write-Host "Reusing existing JAR: $jarPath"
} else {
    docker version *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not ready.' }
    $mavenArgs = @('-B', '-ntp', '-Plocal', '-pl', 'wuzh-ai-gateway', '-am', 'clean', 'package')
    if ($SkipTests) {
        $mavenArgs += '-Dmaven.test.skip=true'
    } else {
        $mavenArgs += @('-Dmaven.test.skip=false', '-DskipTests=false')
    }
    & docker run --rm --mount "type=bind,source=$serverRoot,target=/workspace" `
        --mount 'type=volume,source=image-admin-maven-cache,target=/root/.m2' `
        -w /workspace maven:3.9.11-eclipse-temurin-21 mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Gateway JAR build failed (exit code $LASTEXITCODE)." }
}
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw "Gateway JAR was not produced: $jarPath" }
$jar = Get-Item -LiteralPath $jarPath
if ($jar.Length -le 0) { throw 'Gateway JAR is empty.' }

Write-Host "[3/4] Copying $([Math]::Round($jar.Length / 1MB, 1)) MiB JAR directly to ECS..."
& scp @sshOptions $jarPath "${sshTarget}:$remoteJar"
if ($LASTEXITCODE -ne 0) { throw "Direct SCP upload failed (exit code $LASTEXITCODE)." }

Write-Host '[4/4] Updating Gateway and waiting for health checks...'
$remoteScript = @'
set -Eeuo pipefail
trap 'code=$?; trap - EXIT; rm -f "__JAR__"; exit "$code"' EXIT
cd '__ROOT__'
exec 9>.publish.lock
flock -n 9 || { echo 'Another Gateway deployment is running.' >&2; exit 2; }
echo '[ECS 1/3] Verifying uploaded JAR...'
test -s '__JAR__'
old_image=$(docker inspect --format '{{.Image}}' "$(docker compose --env-file .env ps -q ai-gateway)")
install -m 644 '__JAR__' wuzh-ai-gateway.jar
echo '[ECS 2/3] Building Gateway image (existing Docker cache)...'
docker compose --env-file .env --progress plain build ai-gateway
echo '[ECS 3/3] Recreating Gateway and checking health (up to 180s)...'
if ! docker compose --env-file .env up -d --no-build --no-deps --force-recreate --wait --wait-timeout 180 ai-gateway ||
   ! docker compose --env-file .env exec -T ai-gateway curl -fsS --max-time 10 http://127.0.0.1:18080/health; then
    echo 'Deployment failed. Restoring previous Gateway image.' >&2
    docker image tag "$old_image" image-admin-ai-gateway:latest
    docker compose --env-file .env up -d --no-build --no-deps --force-recreate --wait --wait-timeout 180 ai-gateway
    exit 10
fi
docker compose --env-file .env ps ai-gateway
echo 'Gateway deployment succeeded. Existing .env and prompt were not modified.'
'@
$remoteScript = $remoteScript.Replace('__ROOT__', $remoteRoot).Replace('__JAR__', $remoteJar)
Invoke-SshScript $remoteScript
Write-Host "Done. Total elapsed: $([int]((Get-Date) - $publishStarted).TotalSeconds)s."
