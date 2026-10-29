# Local Windows frontend candidate preparation; see scripts/README.md.
# The caller must hold the shared deploy.lock while this script runs.
param(
    [string]$Image = 'toadzip-frontend:bg-local-533',
    [string]$ComposeOverride
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Docker {
    $result = & docker @args
    if ($LASTEXITCODE -ne 0) {
        throw "Docker 명령 실패: $($args[0])"
    }
    $result
}

$previousImage = $env:FRONTEND_GREEN_IMAGE
$previousEdgeDir = $env:EDGE_NGINX_CONF_DIR
Push-Location (Split-Path -Parent $PSScriptRoot)

try {
    $edge = '2026-toadzip-edge-nginx-1'
    $edgeDir = (Resolve-Path 'infra/runtime/bluegreen/edge').Path

    $mounts = Invoke-Docker inspect --format '{{json .Mounts}}' $edge |
        ConvertFrom-Json
    $mount = @($mounts | Where-Object Destination -eq '/etc/nginx/conf.d')

    if ($mount.Count -ne 1) {
        throw 'Edge Nginx 설정 마운트를 특정할 수 없습니다.'
    }
    if ($mount[0].RW -or $mount[0].Source -ne $edgeDir) {
        throw '예상한 로컬 읽기 전용 마운트와 다릅니다.'
    }

    $conf = (Invoke-Docker exec $edge cat /etc/nginx/conf.d/default.conf) -join "`n"
    $pattern = '(?ms)^\s*upstream\s+frontend_active\s*\{\s*server\s+frontend-blue:8080;\s*\}'
    if ([regex]::Matches($conf, $pattern).Count -ne 1) {
        throw 'frontend-blue 활성 설정을 확인하지 못했습니다.'
    }

    $imageId = Invoke-Docker image inspect --format '{{.Id}}' $Image
    if ($imageId -notmatch '^sha256:[0-9a-f]{64}$') {
        throw '로컬 이미지 ID를 확인하지 못했습니다.'
    }

    $env:FRONTEND_GREEN_IMAGE = $imageId
    $env:EDGE_NGINX_CONF_DIR = $edgeDir

    $composeFiles = @('-f', 'compose.yaml', '-f', 'compose.bluegreen.yaml')
    if ($ComposeOverride) {
        $overridePath = (Resolve-Path -LiteralPath $ComposeOverride).Path
        $composeFiles += @('-f', $overridePath)
    }

    Write-Host "교체 후보: frontend-green / 기존 로컬 이미지: $imageId"
    Invoke-Docker compose --project-name 2026-toadzip @composeFiles `
        --profile bluegreen up -d `
        --no-deps --no-build --pull never --force-recreate `
        --wait --wait-timeout 180 frontend-green
}
finally {
    $env:FRONTEND_GREEN_IMAGE = $previousImage
    $env:EDGE_NGINX_CONF_DIR = $previousEdgeDir
    Pop-Location
}
