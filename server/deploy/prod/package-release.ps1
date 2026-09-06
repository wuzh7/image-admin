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
    if ($LASTEXITCODE -ne 0) { throw "本地镜像不存在: $image" }
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$archive = Join-Path $OutputDirectory "image-admin-$Version.tar"
$manifest = Join-Path $OutputDirectory "image-admin-$Version.env"
$checksum = "$archive.sha256"
if (Test-Path -LiteralPath $archive) { throw "发布包已存在，请使用新的版本号: $archive" }
if (Test-Path -LiteralPath $manifest) { throw "版本清单已存在，请使用新的版本号: $manifest" }
if (Test-Path -LiteralPath $checksum) { throw "校验文件已存在，请使用新的版本号: $checksum" }

docker save --output $archive $BackendImage $FrontendImage
if ($LASTEXITCODE -ne 0) { throw '发布包导出失败。' }
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($manifest, "BACKEND_IMAGE=$BackendImage`nFRONTEND_IMAGE=$FrontendImage`n", $utf8NoBom)

$checksums = @($archive, $manifest) | ForEach-Object {
    "$((Get-FileHash -Algorithm SHA256 $_).Hash.ToLowerInvariant())  $(Split-Path -Leaf $_)"
}
[System.IO.File]::WriteAllText($checksum, (($checksums -join "`n") + "`n"), $utf8NoBom)
Write-Host "发布包已生成: $archive"
Write-Host "版本清单已生成: $manifest"
Write-Host "校验文件已生成: $checksum"
