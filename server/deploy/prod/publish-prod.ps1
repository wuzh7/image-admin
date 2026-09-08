param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')]
    [string]$Version,
    [ValidatePattern('^i-[A-Za-z0-9]+$')]
    [string]$InstanceId = 'i-bp10t7y63rvasdu34imd',
    [ValidatePattern('^/[A-Za-z0-9._/-]+$')]
    [string]$RemoteRoot = '/data/app/image-admin',
    [ValidateNotNullOrEmpty()]
    [string]$DockerDesktopPath = 'D:\Docker\DockerDesktop\Docker Desktop.exe'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$releaseDirectory = Join-Path $PSScriptRoot 'releases'
$buildScript = Join-Path $PSScriptRoot 'build-images.ps1'
$packageScript = Join-Path $PSScriptRoot 'package-release.ps1'

function Invoke-Native([string]$Command, [string[]]$Arguments, [string]$FailureMessage) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage (exit code: $LASTEXITCODE)"
    }
}

function Test-DockerReady {
    & docker info *> $null
    return $LASTEXITCODE -eq 0
}

function Initialize-DockerDesktop {
    if (Test-DockerReady) { return }
    if (-not (Test-Path -LiteralPath $DockerDesktopPath)) {
        throw "Docker Desktop is not running and was not found at: $DockerDesktopPath"
    }
    if (-not (Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue)) {
        Write-Host 'Docker Desktop is not running. Starting it now...'
        Start-Process -FilePath $DockerDesktopPath -WindowStyle Hidden
    } else {
        Write-Host 'Docker Desktop is still starting...'
    }
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        if (Test-DockerReady) {
            Write-Host 'Docker Desktop is ready.'
            return
        }
        if ($attempt % 10 -eq 0) {
            Write-Host "Waiting for Docker Desktop ($($attempt * 2) seconds)..."
        }
        Start-Sleep -Seconds 2
    }
    throw 'Docker Desktop did not become ready within 120 seconds'
}

foreach ($command in @('git', 'docker', 'workbench')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command is missing: $command"
    }
}

$insideWorkTree = (& git -C $repoRoot rev-parse --is-inside-work-tree).Trim()
if ($LASTEXITCODE -ne 0 -or $insideWorkTree -ne 'true') {
    throw "Not running from the expected Git repository: $repoRoot"
}

$commit = (& git -C $repoRoot rev-parse --short=7 HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to read the current Git commit'
}
if (-not $Version) {
    $Version = "$(Get-Date -Format 'yyyyMMdd-HHmmss')-$commit"
}

Initialize-DockerDesktop

$archive = Join-Path $releaseDirectory "image-admin-$Version.tar"
$manifest = Join-Path $releaseDirectory "image-admin-$Version.env"
$checksum = "$archive.sha256"
$remoteReleaseDirectory = "$RemoteRoot/releases"
$remoteFiles = @(
    "$remoteReleaseDirectory/$(Split-Path -Leaf $archive)",
    "$remoteReleaseDirectory/$(Split-Path -Leaf $manifest)",
    "$remoteReleaseDirectory/$(Split-Path -Leaf $checksum)"
)
$absenceChecks = ($remoteFiles | ForEach-Object { "test ! -e '$_'" }) -join '; '
$preflight = "set -eu; test -d '$remoteReleaseDirectory'; test -x '$RemoteRoot/deploy.sh'; test -x '$RemoteRoot/status.sh'; $absenceChecks"

Write-Host "Release version: $Version"
Write-Host "Target: ${InstanceId}:$RemoteRoot"
Invoke-Native 'workbench' @('exec', '--instance-id', $InstanceId, '--command', $preflight, '--timeout', '30') 'Remote preflight failed or the release version already exists'

& $buildScript -Version $Version
if (-not $?) { throw 'Image build failed' }
& $packageScript -Version $Version
if (-not $?) { throw 'Release packaging failed' }

if ((Get-Item -LiteralPath $archive).Length -ge 1GB) {
    throw 'The release archive is too large for Workbench upload (must be smaller than 1 GB)'
}

for ($index = 0; $index -lt $remoteFiles.Count; $index++) {
    $localFile = @($archive, $manifest, $checksum)[$index]
    Invoke-Native 'workbench' @('upload', $localFile, $remoteFiles[$index], '--instance-id', $InstanceId) "Upload failed: $localFile"
}

$deployCommand = "set -eu; '$RemoteRoot/deploy.sh' '$Version'; '$RemoteRoot/status.sh'"
Invoke-Native 'workbench' @('exec', '--instance-id', $InstanceId, '--command', $deployCommand, '--timeout', '600') 'Production deployment failed'

Write-Host "Production release succeeded: $Version"
