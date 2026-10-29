# Local Windows frontend routing validation; see scripts/README.md.
# This script is not connected to the Linux deployment workflows.
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('blue', 'green')]
    [string]$Color
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Color = $Color.ToLowerInvariant()

function Invoke-Docker {
    $result = & docker @args
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed: $($args[0])"
    }
    $result
}

function Assert-Http([string]$Path) {
    $status = & curl.exe --silent --show-error --max-time 10 `
        --output NUL --write-out '%{http_code}' `
        "http://127.0.0.1:18080$Path"

    if ($LASTEXITCODE -ne 0 -or $status -ne '200') {
        throw "HTTP check failed: $Path / $status"
    }
}

function Set-EdgeConfig([string]$Text) {
    $temp = Join-Path $edgeDir (
        "switch-" + [guid]::NewGuid().ToString('N') + '.tmp'
    )
    [IO.File]::WriteAllText(
        $temp, $Text, [Text.UTF8Encoding]::new($false)
    )
    $replaceBackup = Join-Path $runtime ("replaced-" + [guid]::NewGuid().ToString('N') + '.conf.bak')
    [IO.File]::Replace($temp, $confPath, $replaceBackup)

    $mounted = (
        Invoke-Docker exec $edge cat /etc/nginx/conf.d/default.conf
    ) -join "`n"

    if ($mounted.Trim() -ne $Text.Replace("`r`n", "`n").Trim()) {
        throw 'Mounted configuration does not match.'
    }
}

Push-Location (Split-Path -Parent $PSScriptRoot)
$deployLock = $null
$changed = $false

try {
    $runtime = (Resolve-Path 'infra/runtime/bluegreen').Path
    $edgeDir = Join-Path $runtime 'edge'
    $confPath = Join-Path $edgeDir 'default.conf'
    $edge = '2026-toadzip-edge-nginx-1'
    $target = "2026-toadzip-frontend-$Color-1"

    $deployLock = [IO.File]::Open(
        (Join-Path $runtime 'deploy.lock'),
        [IO.FileMode]::OpenOrCreate,
        [IO.FileAccess]::ReadWrite,
        [IO.FileShare]::None
    )

    $mounts = Invoke-Docker inspect `
        --format '{{json .Mounts}}' $edge | ConvertFrom-Json
    $mount = @(
        $mounts | Where-Object Destination -eq '/etc/nginx/conf.d'
    )
    if ($mount.Count -ne 1) {
        throw 'Unexpected Edge Nginx mount.'
    }
    if ($mount[0].RW -or $mount[0].Source -ne $edgeDir) {
        throw 'Unexpected Edge Nginx mount.'
    }

    $health = Invoke-Docker inspect `
        --format '{{.State.Health.Status}}' $target
    if ($health -ne 'healthy') {
        throw "Target is not healthy: $target"
    }

    Invoke-Docker exec $target wget --quiet `
        --output-document=/dev/null `
        http://127.0.0.1:8080/manifest.webmanifest | Out-Null

    $before = [IO.File]::ReadAllText($confPath)
    $pattern = [regex]'(?ms)(^\s*upstream\s+frontend_active\s*\{\s*server\s+)(frontend-(?:blue|green))(:8080;\s*\})'

    if ($pattern.Matches($before).Count -ne 1) {
        throw 'Expected one frontend Blue/Green upstream.'
    }

    $after = $pattern.Replace(
        $before, ('${1}frontend-' + $Color + '${3}')
    )
    Assert-Http '/manifest.webmanifest'
    Assert-Http '/api/health'

    $backup = Join-Path $runtime (
        "before-switch-" + [guid]::NewGuid().ToString('N') + '.conf.bak'
    )
    [IO.File]::Copy($confPath, $backup)

    $changed = $true
    Set-EdgeConfig $after
    Invoke-Docker exec $edge nginx -t | Out-Null
    $since = [DateTimeOffset]::UtcNow.ToString('o')
    Invoke-Docker exec $edge nginx -s reload | Out-Null

    $probe = '/assets/edge-probe-' + [guid]::NewGuid().ToString('N')
    $verified = $false

    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        $status = & curl.exe --silent --show-error --max-time 3 `
            --output NUL --write-out '%{http_code}' `
            "http://127.0.0.1:18080$probe"

        if ($LASTEXITCODE -eq 0 -and $status -eq '404') {
            $targetLogs = (
                Invoke-Docker logs --since $since --tail 100 $target
            ) -join "`n"

            if ($targetLogs.Contains($probe)) {
                $verified = $true
                break
            }
        }
        Start-Sleep -Milliseconds 500
    }

    if (-not $verified) {
        throw 'Target request log was not confirmed.'
    }

    Assert-Http '/manifest.webmanifest'
    Assert-Http '/api/health'
    Write-Host "Screen target confirmed: frontend-$Color"
    Write-Host "Backup: $backup"
}
catch {
    $failure = $_

    if ($changed) {
        try {
            Set-EdgeConfig $before
            Invoke-Docker exec $edge nginx -t | Out-Null
            Invoke-Docker exec $edge nginx -s reload | Out-Null
            Assert-Http '/manifest.webmanifest'
            Assert-Http '/api/health'
            Write-Warning 'Previous configuration restored; verify its request logs.'
        }
        catch {
            throw "Switch failed: $($failure.Exception.Message)`nRecovery failed: $($_.Exception.Message)`nBackup: $backup"
        }
    }
    throw $failure
}
finally {
    if ($null -ne $deployLock) {
        $deployLock.Dispose()
    }
    Pop-Location
}
