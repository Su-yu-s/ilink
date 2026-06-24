$ErrorActionPreference = 'Stop'

$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$port = 18090
$tmpDir = Join-Path $PSScriptRoot '.tmp'
$out = Join-Path $tmpDir 'server-out.log'
$err = Join-Path $tmpDir 'server-err.log'

New-Item -ItemType Directory -Force $tmpDir | Out-Null

$existingPids = @(
    Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue |
        Where-Object { $_.OwningProcess -gt 0 } |
        Select-Object -ExpandProperty OwningProcess -Unique
)

if ($existingPids.Count -gt 0) {
    throw "端口 $port 已被占用，请先停止占用该端口的进程：$($existingPids -join ', ')"
}

Remove-Item -LiteralPath $out, $err -Force -ErrorAction SilentlyContinue

$server = Start-Process `
    -FilePath 'cmd.exe' `
    -ArgumentList '/c', 'tests\e2e\run-e2e-server.cmd' `
    -WorkingDirectory $root `
    -WindowStyle Hidden `
    -PassThru `
    -RedirectStandardOutput $out `
    -RedirectStandardError $err

try {
    Push-Location $root
    python tests/e2e/profile_edit_validation.py
    if ($LASTEXITCODE -ne 0) {
        throw "Playwright E2E 测试失败，退出码：$LASTEXITCODE"
    }
} finally {
    Pop-Location
    $currentPids = @(
        Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue |
            Where-Object { $_.OwningProcess -gt 0 -and $existingPids -notcontains $_.OwningProcess } |
            Select-Object -ExpandProperty OwningProcess -Unique
    )
    foreach ($pidValue in $currentPids) {
        Stop-Process -Id $pidValue -Force -ErrorAction SilentlyContinue
    }
    if ($server -and -not $server.HasExited) {
        Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    }
}

Write-Host 'profile-edit E2E passed'
