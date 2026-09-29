# Offline configuration checks: never connects to ECS or recreates containers.
$ErrorActionPreference = 'Stop'
$gatewayRoot = Split-Path -Parent $PSScriptRoot
$output = docker compose --env-file (Join-Path $gatewayRoot '.env.example') -f (Join-Path $gatewayRoot 'compose.yml') config --format json
if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid.' }
$service = ($output | ConvertFrom-Json).services.'ai-gateway'
$promptDir = '/opt/image-admin-ai-gateway/prompts'
if ($service.environment.RELAY_PROMPT_FILE -cne "$promptDir/image_analysis_prompt.txt") { throw 'Image analysis prompt must use its server path.' }
if ($service.volumes.Count -ne 1) { throw 'Mount only the dedicated prompt directory.' }
$mount = $service.volumes[0]
if ($mount.source -cne $promptDir -or $mount.target -cne $promptDir -or -not $mount.read_only) { throw 'Prompt directory must use identical, read-only paths.' }
$example = Get-Content (Join-Path $gatewayRoot '.env.example') -Raw
foreach ($obsolete in @('GEMINI_PROMPT_HOST_PATH=', 'RELAY_PROMPT_HOST_DIR=', 'RELAY_PROMPT2_FILE=', 'RELAY_STAGE2_MODEL=', 'RELAY_STAGE2_PROTOCOL=', 'OPENLUX_MODEL=')) {
    if ($example.Contains($obsolete)) { throw "Unnecessary configuration: $obsolete" }
}
$publisher = Get-Content (Join-Path $gatewayRoot 'publish-gateway-direct.ps1') -Raw
if ($publisher.Contains('.Destination "/app/config/gemini_prompt.txt"')) { throw 'Publisher assumes an obsolete mount target.' }
if (-not $publisher.Contains('prompts/image_analysis_prompt.txt')) { throw 'Publisher must install the image analysis prompt.' }
Write-Host 'PASS: identical server/container prompt paths, directory mount, minimal configuration, and publisher preflight.'
