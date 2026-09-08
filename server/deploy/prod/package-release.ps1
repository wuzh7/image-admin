param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]*$')]
    [string]$Version,
    [string]$BackendImage = "image-admin-backend:$Version",
    [string]$FrontendImage = "image-admin-frontend:$Version",
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'releases')
)

$ErrorActionPreference = 'Stop'
foreach ($image in @($BackendImage, $FrontendImage)) {
    docker image inspect $image *> $null
    if ($LASTEXITCODE -ne 0) { throw "Local image does not exist: $image" }
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$archive = Join-Path $OutputDirectory "image-admin-$Version.tar"
$manifest = Join-Path $OutputDirectory "image-admin-$Version.env"
$checksum = "$archive.sha256"
if (Test-Path -LiteralPath $archive) { throw "Release archive already exists; use a new version: $archive" }
if (Test-Path -LiteralPath $manifest) { throw "Release manifest already exists; use a new version: $manifest" }
if (Test-Path -LiteralPath $checksum) { throw "Release checksum already exists; use a new version: $checksum" }

docker save --output $archive $BackendImage $FrontendImage
if ($LASTEXITCODE -ne 0) { throw 'Release archive export failed.' }
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($manifest, "BACKEND_IMAGE=$BackendImage`nFRONTEND_IMAGE=$FrontendImage`n", $utf8NoBom)

$checksums = @($archive, $manifest) | ForEach-Object {
    "$((Get-FileHash -Algorithm SHA256 $_).Hash.ToLowerInvariant())  $(Split-Path -Leaf $_)"
}
[System.IO.File]::WriteAllText($checksum, (($checksums -join "`n") + "`n"), $utf8NoBom)
Write-Host "Release archive: $archive"
Write-Host "Release manifest: $manifest"
Write-Host "Release checksum: $checksum"
