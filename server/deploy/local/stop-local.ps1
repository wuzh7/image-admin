$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot 'compose.yml'
$envFile = Join-Path $PSScriptRoot '.env.local'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw '尚未创建.env.local，请先运行start-local.ps1。'
}

& docker compose --project-name image-admin-local --env-file $envFile -f $composeFile stop frontend backend qdrant redis mysql
if ($LASTEXITCODE -ne 0) { throw '停止服务失败。' }
Write-Host '服务已停止，MySQL、Qdrant、Redis和图片数据卷均已保留。'
