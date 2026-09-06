param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')]
    [string]$Version,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$serverRoot = Join-Path $repoRoot 'server'
$webRoot = Join-Path $repoRoot 'web'
$backendImage = "image-admin-backend:$Version"
$frontendImage = "image-admin-frontend:$Version"
$stage = Join-Path ([System.IO.Path]::GetTempPath()) "image-admin-release-$Version-$PID"

try {
    docker version *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop 未启动。' }

    $mavenArguments = @('mvn', '-q', '-Plocal,djl-linux', '-pl', 'wuzh-admin', '-am', 'package')
    if ($SkipTests) { $mavenArguments += '-DskipTests' }
    docker run --rm `
        --mount "type=bind,source=$serverRoot,target=/workspace" `
        --mount 'type=volume,source=image-admin-maven-cache,target=/root/.m2' `
        -w /workspace maven:3.9.11-eclipse-temurin-21 @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw '后端构建失败。' }

    Push-Location $webRoot
    try {
        pnpm build
        if ($LASTEXITCODE -ne 0) { throw '前端构建失败。' }
    } finally {
        Pop-Location
    }

    $backendStage = Join-Path $stage 'backend'
    $frontendStage = Join-Path $stage 'frontend'
    New-Item -ItemType Directory -Force -Path $backendStage, $frontendStage | Out-Null
    Copy-Item (Join-Path $PSScriptRoot 'Dockerfile.backend-runtime') (Join-Path $backendStage 'Dockerfile')
    Copy-Item (Join-Path $serverRoot 'wuzh-admin\target\wuzh-admin.jar') (Join-Path $backendStage 'wuzh-admin.jar')
    Copy-Item (Join-Path $PSScriptRoot 'Dockerfile.frontend-runtime') (Join-Path $frontendStage 'Dockerfile')
    Copy-Item (Join-Path $PSScriptRoot 'nginx.conf') (Join-Path $frontendStage 'nginx.conf')
    Copy-Item (Join-Path $webRoot 'dist') (Join-Path $frontendStage 'dist') -Recurse

    docker build -t $backendImage -f (Join-Path $backendStage 'Dockerfile') $backendStage
    if ($LASTEXITCODE -ne 0) { throw '后端运行镜像构建失败。' }
    docker build -t $frontendImage -f (Join-Path $frontendStage 'Dockerfile') $frontendStage
    if ($LASTEXITCODE -ne 0) { throw '前端运行镜像构建失败。' }

    Write-Host "镜像构建完成: $backendImage / $frontendImage"
    Write-Host "下一步: .\package-release.ps1 -Version $Version"
} finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
