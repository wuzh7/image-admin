# Offline checks only: never connects to ECS or changes containers.
$ErrorActionPreference = 'Stop'
$prodRoot = Split-Path -Parent $PSScriptRoot
foreach ($name in @('publish-prod.ps1')) {
    $tokens = $null
    $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $prodRoot $name), [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw "$name has syntax errors: $parseErrors" }
    if ($name -eq 'publish-prod.ps1') {
        $runner = $ast.Find({ param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-LoggedCommand'
        }, $true)
    }
}
if (-not $runner) { throw 'Progress runner is missing.' }
Invoke-Expression $runner.Extent.Text
$executable = (Get-Process -Id $PID).Path
$encodedProbe = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes('Write-Output "argument with spaces"; Start-Sleep -Seconds 12; Write-Output "finished"'))
$output = & {
    Invoke-LoggedCommand -Command $executable -Label 'Offline progress test' `
        -CommandArguments @('-NoProfile', '-EncodedCommand', $encodedProbe)
} 6>&1 | Out-String
foreach ($expected in @('argument with spaces', 'is still running', 'finished', 'completed')) {
    if (-not $output.Contains($expected)) { throw "Progress output is missing: $expected" }
}
$failed = $false
try {
    Invoke-LoggedCommand -Command $executable -Label 'Expected failure test' `
        -CommandArguments @('-NoProfile', '-Command', 'exit 7')
} catch { $failed = $_.ToString().Contains('exit code 7') }
if (-not $failed) { throw 'Native command failure was not propagated.' }
if (Get-Job) { throw 'Progress runner left a background job behind.' }
$build = Get-Content (Join-Path $prodRoot 'publish-prod.ps1') -Raw
foreach ($name in @('build-images.ps1', 'package-release.ps1', 'deploy.sh')) {
    if ((Test-Path (Join-Path $prodRoot $name)) -or $build.Contains($name)) { throw "Obsolete script is still present or referenced: $name" }
}
if ($build.Contains('pnpm lint') -or $build.Contains('docker run')) { throw 'Old release-time build pipeline is still present.' }
if (-not $build.Contains('--progress=plain')) { throw 'Plain Docker progress is missing.' }
if ([regex]::Matches($build, "'--platform', 'linux/amd64'").Count -ne 3) { throw 'Production images must target linux/amd64.' }
if ([regex]::Matches($build, "--format '{{.Architecture}}'").Count -ne 3) { throw 'Uploaded images must be checked for amd64 before activation.' }
if ([regex]::Matches($build, 'image-admin-(backend|frontend|render-worker):rollback').Count -ne 3) { throw 'Exactly one rollback image per service must be kept.' }
if (-not ($build.Contains('sys_dept_menu') -and $build.Contains('20260928_access_control.sql'))) { throw 'Department permission migration guard is missing.' }
if ($build.Contains('docker image prune') -or $build.Contains('docker system prune')) { throw 'Release cleanup must stay scoped to old application images.' }
$cmd = [IO.File]::ReadAllBytes((Join-Path $prodRoot 'publish-prod.cmd'))
if ($cmd.Length -ge 3 -and $cmd[0] -eq 239 -and $cmd[1] -eq 187 -and $cmd[2] -eq 191) { throw 'CMD must not have a UTF-8 BOM.' }
Write-Host 'PASS: syntax, live output, silent-stage heartbeat, failure propagation, job cleanup, and CMD encoding.'
