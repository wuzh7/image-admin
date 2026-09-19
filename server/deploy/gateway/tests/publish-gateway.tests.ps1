# Offline checks only: never connects to ECS or deploys containers.
$ErrorActionPreference = 'Stop'
$gatewayRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $gatewayRoot 'publish-gateway-simple.ps1'
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
# Capture the wire command without contacting ECS; cover both independent publishers.
function workbench {
    $commandIndex = [Array]::IndexOf([string[]]$args, '--command')
    if ($commandIndex -lt 0) { throw 'Missing Workbench command argument.' }
    $script:wireCommand = $args[$commandIndex + 1]
    $global:LASTEXITCODE = 0
    '{"exit_code":0,"output":"offline-ok","stderr":""}'
}
try {
    $instanceId = 'offline-only'
    $quoteProbe = "printf '%s\\n' `"text with spaces`"`r`nprintf '%s' '`$literal'`r`n"
    foreach ($publisher in @($scriptPath, (Join-Path $gatewayRoot '../prod/publish-prod.ps1'))) {
        $publisherAst = [Management.Automation.Language.Parser]::ParseFile($publisher, [ref]$tokens, [ref]$parseErrors)
        if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
        $executor = $publisherAst.Find({ param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-EcsCommand'
        }, $true)
        Invoke-Expression $executor.Extent.Text
        if ((Invoke-EcsCommand $quoteProbe) -ne 'offline-ok') { throw 'Remote output was not returned.' }
        if ($wireCommand -notmatch "^printf '%s' '([A-Za-z0-9+/=]+)' \| base64 -d \| bash$") {
            throw "Publisher passes raw quotes through Windows native arguments: $publisher"
        }
        $decoded = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Matches[1]))
        if ($decoded -cne $quoteProbe.Replace("`r`n", "`n")) { throw 'Quotes or line endings changed during transport.' }
    }
} finally { Remove-Item Function:\workbench }
$runner = $ast.Find({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-LoggedCommand'
}, $true)
Invoke-Expression $runner.Extent.Text
$executable = (Get-Process -Id $PID).Path
$probe = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes('Write-Output "argument with spaces"; Start-Sleep -Seconds 12; Write-Output "finished"'))
$output = & {
    Invoke-LoggedCommand -Command $executable -Label 'Offline progress test' -CommandArguments @('-NoProfile', '-EncodedCommand', $probe)
} 6>&1 | Out-String
foreach ($expected in @('argument with spaces', 'is still running', 'finished', 'completed')) {
    if (-not $output.Contains($expected)) { throw "Missing progress output: $expected" }
}
$failed = $false
try {
    Invoke-LoggedCommand -Command $executable -Label 'Expected failure test' -CommandArguments @('-NoProfile', '-Command', 'exit 7')
} catch { $failed = $_.ToString().Contains('exit code 7') }
if (-not $failed) { throw 'Native failure was not propagated.' }
if (Get-Job) { throw 'Background job was not cleaned up.' }
$source = Get-Content $scriptPath -Raw
foreach ($obsolete in @('deploy.sh', 'scp', '$SshUser', '$promptPath')) {
    if ($source.Contains($obsolete)) { throw "Obsolete dependency: $obsolete" }
}
if (Test-Path (Join-Path $gatewayRoot 'deploy.sh')) { throw 'Obsolete deploy.sh still exists.' }
foreach ($required in @('workbench exec', 'workbench -Label', 'clean', 'sha256sum -c', 'flock -n', '--no-build', 'Restoring previous Gateway image')) {
    if (-not $source.Contains($required)) { throw "Missing release protection: $required" }
}
$cmd = [IO.File]::ReadAllBytes((Join-Path $gatewayRoot 'publish-gateway-simple.cmd'))
if ($cmd.Length -ge 3 -and $cmd[0] -eq 239 -and $cmd[1] -eq 187 -and $cmd[2] -eq 191) { throw 'CMD must not contain a UTF-8 BOM.' }
Write-Host 'PASS: syntax, progress, heartbeat, failure propagation, job cleanup, self-contained deployment, and CMD encoding.'
