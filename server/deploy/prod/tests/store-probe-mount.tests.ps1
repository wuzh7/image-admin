# Offline checks only: never connects to ECS or changes containers.
$ErrorActionPreference = 'Stop'
$prodRoot = Split-Path -Parent $PSScriptRoot
$compose = Get-Content (Join-Path $prodRoot 'compose.yml') -Raw
$envExample = Get-Content (Join-Path $prodRoot '.env.oss.example') -Raw

if (-not $compose.Contains('- ./data:/app/config:ro')) {
    throw 'Store probe data directory is not mounted read-only.'
}
if ($compose.Contains('/app/config/store-probe.xlsx:ro')) {
    throw 'Store probe still uses a single-file bind mount.'
}
if ($envExample.Contains('IMAGE_INFRINGEMENT_STORE_HOST_PATH')) {
    throw 'Obsolete single-file mount configuration is still documented.'
}

Write-Host 'PASS: store probe uses a read-only directory mount.'
