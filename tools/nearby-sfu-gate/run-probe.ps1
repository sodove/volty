param([string]$Workspace = "$PSScriptRoot/../../.superpowers/sdd/2026-10-03-nearby-sfu-admission")
$ErrorActionPreference = 'Stop'
$workspacePath = (Resolve-Path -LiteralPath $Workspace).Path
$binary = Join-Path $workspacePath 'livekit-volty-prototype.exe'
$probe = Join-Path $workspacePath 'probe.exe'
if (!(Test-Path -LiteralPath $binary) -or !(Test-Path -LiteralPath $probe)) {throw 'Build owned SFU and probe binaries first'}
foreach ($port in @(17881,17882,17884)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {throw "Loopback port occupied: $port"}
}
$run = Join-Path $workspacePath ('run-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $run | Out-Null
$config = Join-Path $run 'probe.yaml'
Copy-Item -LiteralPath "$PSScriptRoot/probe.yaml" -Destination $config
$owned = Start-Process -FilePath $binary -ArgumentList @('--config',$config) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $run 'server-stdout.log') -RedirectStandardError (Join-Path $run 'server-stderr.log')
Set-Content (Join-Path $run 'server.pid') $owned.Id
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($owned.HasExited) {throw 'Owned SFU exited before readiness'}
        try { $reply = Invoke-WebRequest -Uri http://127.0.0.1:17881 -TimeoutSec 1; $ready = $reply.StatusCode -eq 200 } catch { }
        if ($ready) {break}
        Start-Sleep -Milliseconds 100
    }
    if (!$ready) {throw 'SFU readiness failed'}
    & $probe *> (Join-Path $run 'probe.log')
    $probeExit = $LASTEXITCODE
    foreach ($log in Get-ChildItem -LiteralPath $run -Filter '*.log') {
        $contents = Get-Content -LiteralPath $log.FullName -Raw
        if ($contents -match '[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}') {throw 'Credential-looking content detected; do not print raw logs'}
    }
    if ($probeExit -ne 0) {throw "Probe failed (exit $probeExit); inspect sanitized evidence"}
    Get-Content (Join-Path $run 'probe.log') | Where-Object {$_ -match '^(nonce=|case=|baseline_|original_exp=|revoke_ack_|post_ack_|participant_control_|race_admission_|expiry_without_|matrix_|readiness_protocol=|node_fence_)'}
} finally {
    if (!$owned.HasExited) {
        $current = Get-Process -Id $owned.Id -ErrorAction SilentlyContinue
        if ($null -ne $current) {
            if ($current.Path -ne $binary) {throw 'Refusing to stop a process with different executable'}
            Stop-Process -InputObject $current
            $owned.WaitForExit(5000) | Out-Null
        }
    }
    Set-Content (Join-Path $run 'cleanup.txt') ('owned_sfu_stopped=' + $owned.HasExited)
    Write-Output ('evidence=' + $run)
}
