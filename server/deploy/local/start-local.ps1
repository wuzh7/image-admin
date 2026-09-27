$ErrorActionPreference = 'Stop'

$composeFile = Join-Path $PSScriptRoot 'compose.yml'
$envFile = Join-Path $PSScriptRoot '.env.local'
$serverRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$modelName = 'sscd_disc_mixup.torchscript.pt'
$modelTarget = Join-Path $serverRoot "models\$modelName"
$expectedHash = '9F26BD4C848CC19B73D2AE92EEA6E04886F61A7B764CEB7A13AEEE62E6A6DB56'

function Invoke-Compose([string[]]$ComposeCommand) {
    & docker compose --project-name image-admin-local --env-file $envFile -f $composeFile @ComposeCommand
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose执行失败，退出码: $LASTEXITCODE"
    }
}

function Wait-ContainerHealthy([string]$ContainerName) {
    for ($attempt = 1; $attempt -le 180; $attempt++) {
        $health = & docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $ContainerName 2>$null
        if ($health -eq 'healthy') {
            return
        }
        if ($health -eq 'unhealthy' -or $health -eq 'exited') {
            throw "$ContainerName 健康检查失败。"
        }
        Start-Sleep -Seconds 2
    }
    throw "等待 $ContainerName 就绪超时。"
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw '未找到Docker命令，请先安装并启动Docker Desktop。'
}

& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Desktop尚未启动，请启动后重试。'
}

& docker compose version *> $null
if ($LASTEXITCODE -ne 0) {
    throw '未检测到Docker Compose V2。'
}

$dockerMemory = & docker info --format '{{.MemTotal}}'
if ($dockerMemory -match '^\d+$' -and ([double]$dockerMemory / 1GB) -lt 8) {
    Write-Warning 'Docker可用内存少于8GB，模型加载或Java构建可能失败。'
}

if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '.env.example') -Destination $envFile
    Write-Host '已创建本地配置 deploy/local/.env.local'
}

if (-not (Test-Path -LiteralPath $modelTarget)) {
    throw "缺少SSCD模型。请把$modelName放到 $modelTarget"
}

$actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $modelTarget).Hash.ToUpperInvariant()
if ($actualHash -ne $expectedHash) {
    throw "SSCD模型SHA-256不匹配。实际值: $actualHash"
}
Write-Host 'SSCD模型校验通过。'

$existingContainers = & docker compose --project-name image-admin-local --env-file $envFile -f $composeFile ps -q
if (-not $existingContainers) {
    $occupied = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in @(3306, 6379, 6333, 8080, 8088) }
    if ($occupied) {
        $ports = ($occupied.LocalPort | Sort-Object -Unique) -join ', '
        throw "以下端口已被其他程序占用: $ports"
    }
}

Write-Host '正在构建并启动：MySQL、Redis、Qdrant -> 数据库迁移 -> Java后端 -> React前端。'
Invoke-Compose @('up', '--build', '-d', 'mysql', 'redis', 'qdrant')
foreach ($container in @('image-admin-mysql', 'image-admin-redis', 'image-admin-qdrant')) {
    Wait-ContainerHealthy $container
}

# 迁移容器只执行一次，完成后由 --rm 自动删除，不会作为第二个 MySQL 常驻。
Invoke-Compose @('--profile', 'maintenance', 'run', '--rm', 'migrate')
Invoke-Compose @('up', '--build', '-d', 'backend', 'render-worker', 'frontend')

for ($attempt = 1; $attempt -le 180; $attempt++) {
    $health = & docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' image-admin-frontend 2>$null
    if ($health -eq 'healthy') {
        Write-Host ''
        Write-Host '启动完成：http://localhost:8088'
        Write-Host '默认账号：admin / admin123'
        Write-Host '运行状态：.\status-local.ps1'
        exit 0
    }
    if ($health -eq 'unhealthy') {
        Invoke-Compose @('ps')
        Invoke-Compose @('logs', '--tail=120', 'backend', 'frontend')
        throw '服务健康检查失败，请根据上方日志排查。'
    }
    if ($attempt % 10 -eq 0) {
        Write-Host "仍在等待模型预热和服务健康检查（$($attempt * 2)秒）..."
    }
    Start-Sleep -Seconds 2
}

Invoke-Compose @('ps')
Invoke-Compose @('logs', '--tail=120', 'backend')
throw '等待服务启动超时，请查看上方后端日志。'
