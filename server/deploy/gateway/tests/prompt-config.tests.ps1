# Offline configuration checks: never connects to ECS or recreates containers.
$ErrorActionPreference = 'Stop'
$gatewayRoot = Split-Path -Parent $PSScriptRoot
$output = docker compose --env-file (Join-Path $gatewayRoot '.env.example') -f (Join-Path $gatewayRoot 'compose.yml') config --format json
if ($LASTEXITCODE -ne 0) { throw 'Compose configuration is invalid.' }
$service = ($output | ConvertFrom-Json).services.'ai-gateway'
$promptDir = '/opt/image-admin-ai-gateway/prompts'
if ($service.environment.GEMINI_PROMPT_FILE -cne "$promptDir/gemini_prompt.txt") { throw 'Prompt1 must use its server path.' }
if ($service.environment.RELAY_PROMPT2_FILE -cne "$promptDir/prompt2.txt") { throw 'Prompt2 must use its server path.' }
if ($service.volumes.Count -ne 1) { throw 'Mount only the dedicated prompt directory.' }
$mount = $service.volumes[0]
if ($mount.source -cne $promptDir -or $mount.target -cne $promptDir -or -not $mount.read_only) { throw 'Prompt directory must use identical, read-only paths.' }
if ($service.environment.RELAY_STAGE2_PROTOCOL -cne 'CHAT_COMPLETIONS') { throw 'Doubao protocol default changed.' }
$example = Get-Content (Join-Path $gatewayRoot '.env.example') -Raw
foreach ($obsolete in @('GEMINI_PROMPT_HOST_PATH=', 'RELAY_PROMPT_HOST_DIR=', 'RELAY_STAGE2_PROTOCOL=')) {
    if ($example.Contains($obsolete)) { throw "Unnecessary configuration: $obsolete" }
}
$publisher = Get-Content (Join-Path $gatewayRoot 'publish-gateway-direct.ps1') -Raw
if ($publisher.Contains('.Destination "/app/config/gemini_prompt.txt"')) { throw 'Publisher assumes an obsolete mount target.' }
if (-not $publisher.Contains('s/^GEMINI_PROMPT_FILE=//p')) { throw 'Publisher must validate the running prompt path.' }
Write-Host 'PASS: identical server/container prompt paths, directory mount, minimal configuration, and publisher preflight.'
