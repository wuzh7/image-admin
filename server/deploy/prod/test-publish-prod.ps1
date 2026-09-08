$ErrorActionPreference = 'Stop'

$script = Join-Path $PSScriptRoot 'publish-prod.ps1'
if (-not (Test-Path -LiteralPath $script)) {
    throw 'publish-prod.ps1 is missing'
}

$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($script, [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) {
    throw "publish-prod.ps1 syntax error: $($errors[0].Message)"
}

$parameterNames = @($ast.ParamBlock.Parameters.Name.VariablePath.UserPath)
foreach ($required in @('Version', 'InstanceId', 'RemoteRoot', 'DockerDesktopPath')) {
    if ($required -notin $parameterNames) {
        throw "publish-prod.ps1 is missing parameter: $required"
    }
}
if ('AllowDirty' -in $parameterNames) {
    throw 'publish-prod.ps1 must always publish the current local worktree without AllowDirty'
}

$ErrorActionPreference = 'Continue'
$null = & powershell -NoProfile -ExecutionPolicy Bypass -File $script -Version '../invalid' 2>&1
$invalidExitCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($invalidExitCode -eq 0) {
    throw 'invalid version was not rejected'
}

$scriptText = Get-Content -Raw -LiteralPath $script
if ($scriptText -match 'status\s+--porcelain|uncommitted changes') {
    throw 'publish-prod.ps1 still blocks uncommitted local code'
}
if ($scriptText -match 'GetFullPath\(\$gitRoot') {
    throw 'publish-prod.ps1 still passes Git text output to GetFullPath'
}

foreach ($releaseScript in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File) {
    if ((Get-Content -Raw -LiteralPath $releaseScript.FullName) -match '[^\x00-\x7F]') {
        throw "$($releaseScript.Name) contains non-ASCII text that Windows PowerShell 5.1 may decode incorrectly"
    }
}

$buildScriptText = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'build-images.ps1')
if ($buildScriptText -notmatch 'image-admin-corepack-cache' -or $buildScriptText -notmatch 'until corepack prepare') {
    throw 'build-images.ps1 must cache and retry the containerized pnpm bootstrap'
}

Write-Host 'publish-prod.ps1 contract check passed.'
