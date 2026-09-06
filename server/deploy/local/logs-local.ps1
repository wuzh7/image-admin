$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot 'compose.yml'
$envFile = Join-Path $PSScriptRoot '.env.local'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw '尚未创建.env.local，请先运行start-local.ps1。'
}

& docker compose --project-name image-admin-local --env-file $envFile -f $composeFile logs --follow --tail=200 backend
