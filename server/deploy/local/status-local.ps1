$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot 'compose.yml'
$envFile = Join-Path $PSScriptRoot '.env.local'
$model = Join-Path $PSScriptRoot '..\..\models\sscd_disc_mixup.torchscript.pt'

if (-not (Test-Path -LiteralPath $envFile)) {
    throw '尚未创建.env.local，请先运行start-local.ps1。'
}

& docker compose --project-name image-admin-local --env-file $envFile -f $composeFile ps

Write-Host ''
if (Test-Path -LiteralPath $model) {
    Write-Host "模型SHA-256：$((Get-FileHash -Algorithm SHA256 -LiteralPath $model).Hash)"
} else {
    Write-Warning '未找到SSCD模型。'
}

try {
    $basicToken = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('health:health123'))
    $backend = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health' -Headers @{ Authorization = "Basic $basicToken" } -TimeoutSec 5
    Write-Host "后端健康：$($backend.status)"
} catch {
    Write-Warning "后端健康接口不可用：$($_.Exception.Message)"
}

try {
    $collection = Invoke-RestMethod -Uri 'http://127.0.0.1:6333/collections/image_sscd_v1' -TimeoutSec 5
    Write-Host "Qdrant Point数量：$($collection.result.points_count)"
} catch {
    Write-Warning 'Qdrant集合尚未就绪。'
}
