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
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not ready.' }

    $mavenArguments = @('mvn', '-q', '-Plocal,djl-linux', '-pl', 'wuzh-admin', '-am', 'clean', 'package')
    if ($SkipTests) { $mavenArguments += '-DskipTests' }
    docker run --rm `
        --mount "type=bind,source=$serverRoot,target=/workspace" `
        --mount 'type=volume,source=image-admin-maven-cache,target=/root/.m2' `
        -w /workspace maven:3.9.11-eclipse-temurin-21 @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }

    $frontendCommand = 'set -eu; corepack enable; attempt=1; until corepack prepare pnpm@10.34.5 --activate; do [ "$attempt" -ge 3 ] && exit 1; attempt=$((attempt + 1)); sleep 2; done; pnpm config set store-dir /pnpm/store; pnpm install --frozen-lockfile; pnpm lint; pnpm build'
    docker run --rm `
        --mount "type=bind,source=$webRoot,target=/workspace" `
        --mount 'type=volume,source=image-admin-pnpm-store,target=/pnpm/store' `
        --mount 'type=volume,source=image-admin-web-node-modules,target=/workspace/node_modules' `
        --mount 'type=volume,source=image-admin-corepack-cache,target=/corepack' `
        -e 'COREPACK_HOME=/corepack' `
        -w /workspace node:24-alpine sh -lc $frontendCommand
    if ($LASTEXITCODE -ne 0) { throw 'Frontend lint or build failed.' }

    $backendStage = Join-Path $stage 'backend'
    $frontendStage = Join-Path $stage 'frontend'
    New-Item -ItemType Directory -Force -Path $backendStage, $frontendStage | Out-Null
    Copy-Item (Join-Path $PSScriptRoot 'Dockerfile.backend-runtime') (Join-Path $backendStage 'Dockerfile')
    Copy-Item (Join-Path $serverRoot 'wuzh-admin\target\wuzh-admin.jar') (Join-Path $backendStage 'wuzh-admin.jar')
    Copy-Item (Join-Path $PSScriptRoot 'Dockerfile.frontend-runtime') (Join-Path $frontendStage 'Dockerfile')
    Copy-Item (Join-Path $PSScriptRoot 'nginx.conf') (Join-Path $frontendStage 'nginx.conf')
    Copy-Item (Join-Path $webRoot 'dist') (Join-Path $frontendStage 'dist') -Recurse

    docker build -t $backendImage -f (Join-Path $backendStage 'Dockerfile') $backendStage
    if ($LASTEXITCODE -ne 0) { throw 'Backend runtime image build failed.' }
    docker build -t $frontendImage -f (Join-Path $frontendStage 'Dockerfile') $frontendStage
    if ($LASTEXITCODE -ne 0) { throw 'Frontend runtime image build failed.' }

    Write-Host "Images built: $backendImage / $frontendImage"
    Write-Host "Next: .\package-release.ps1 -Version $Version"
} finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
