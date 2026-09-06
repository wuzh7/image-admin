$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot 'compose.yml'
$envFile = Join-Path $PSScriptRoot '.env.local'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw '尚未创建.env.local，请先运行start-local.ps1。'
}

Write-Warning '此操作会永久删除本项目的MySQL、Redis、Qdrant和图片命名卷。'
$confirmation = Read-Host '请输入 RESET 确认重置'
if ($confirmation -cne 'RESET') {
    Write-Host '已取消，未删除任何数据。'
    exit 0
}

& docker compose --project-name image-admin-local --env-file $envFile -f $composeFile down --volumes --remove-orphans
if ($LASTEXITCODE -ne 0) { throw '重置失败。' }
Write-Host '本地数据卷已删除；模型文件仍保留，再次运行start-local.ps1会创建空环境。'
