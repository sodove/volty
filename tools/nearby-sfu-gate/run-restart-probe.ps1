param([Parameter(Mandatory=$true)][string]$Workspace)
$ErrorActionPreference='Stop'
$workspacePath=(Resolve-Path -LiteralPath $Workspace).Path
$binary=Join-Path $workspacePath 'livekit-volty-prototype.exe'
$probeBinary=Join-Path $workspacePath 'probe.exe'
foreach($port in @(17881,17882,17884)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {throw 'Restart fixture port occupied'}
}
$run=Join-Path $workspacePath ('restart-run-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $run | Out-Null
$config=Join-Path $run 'probe.yaml'
Copy-Item -LiteralPath "$PSScriptRoot/probe.yaml" -Destination $config
$processes=[System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$probe=$null
function Stop-OwnedSfu($process) {
    if (!$process.HasExited) {
        $current=Get-Process -Id $process.Id -ErrorAction SilentlyContinue
        if ($null -ne $current) {
            if ($current.Path -ne $binary) {throw 'Refusing to stop different executable'}
            Stop-Process -InputObject $current
            if (!$process.WaitForExit(5000)) {throw 'Owned SFU did not stop'}
        }
    }
}
function Start-OwnedSfu([int]$index) {
    $process=Start-Process -FilePath $binary -ArgumentList @('--config',$config) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run "server-$index-out.log") -RedirectStandardError (Join-Path $run "server-$index-error.log")
    $processes.Add($process)
    $ready=$false
    for($attempt=0;$attempt -lt 30;$attempt++) {
        if ($process.HasExited) {throw 'Owned SFU exited before ready'}
        try {$ready=(Invoke-WebRequest http://127.0.0.1:17881 -TimeoutSec 1).StatusCode -eq 200} catch { }
        if ($ready) {break}
        Start-Sleep -Milliseconds 100
    }
    if (!$ready) {throw 'Owned SFU readiness failed'}
    return $process
}
try {
    $old=Start-OwnedSfu 1
    $start=[System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName=$probeBinary
    $start.ArgumentList.Add('restart')
    $start.UseShellExecute=$false
    $start.CreateNoWindow=$true
    $start.RedirectStandardInput=$true
    $start.RedirectStandardOutput=$true
    $start.RedirectStandardError=$true
    $probe=[System.Diagnostics.Process]::new()
    $probe.StartInfo=$start
    $probe.Start() | Out-Null
    $stderr=$probe.StandardError.ReadToEndAsync()
    $deadline=[DateTime]::UtcNow.AddSeconds(90)
    $pendingLine=$probe.StandardOutput.ReadLineAsync()
    $restarted=$false;$passed=$false
    while([DateTime]::UtcNow -lt $deadline) {
        if (!$pendingLine.Wait(100)) {continue}
        $line=$pendingLine.GetAwaiter().GetResult()
        if ($null -eq $line) {break}
        Add-Content (Join-Path $run 'probe.log') $line
        if ($line -eq 'restart_barrier_ready=true') {
            if ($restarted) {throw 'Duplicate restart barrier'}
            Stop-OwnedSfu $old
            if (!$old.HasExited) {throw 'Old SFU still alive'}
            $new=Start-OwnedSfu 2
            Set-Content (Join-Path $run 'processes.txt') "old_pid=$($old.Id)`nold_exited=True`nnew_pid=$($new.Id)"
            $restarted=$true
            $probe.StandardInput.WriteLine('server_restarted')
            $probe.StandardInput.Flush()
        }
        if ($line -match '^restart_matrix_passed=true') {$passed=$true}
        $pendingLine=$probe.StandardOutput.ReadLineAsync()
    }
    if (!$probe.WaitForExit(5000)) {throw 'Restart probe timed out'}
    Set-Content (Join-Path $run 'probe-error.log') $stderr.GetAwaiter().GetResult()
    foreach($log in Get-ChildItem -LiteralPath $run -Filter '*.log') {
        if ((Get-Content -LiteralPath $log.FullName -Raw) -match '[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}') {throw 'Credential-looking content; do not print raw logs'}
    }
    if ($probe.ExitCode -ne 0 -or !$restarted -or !$passed) {throw 'Restart matrix failed; inspect sanitized evidence'}
    Get-Content (Join-Path $run 'probe.log') | Where-Object {$_ -match '^(restart_|case=)'}
} finally {
    if ($null -ne $probe -and !$probe.HasExited) {
        $current=Get-Process -Id $probe.Id -ErrorAction SilentlyContinue
        if ($null -ne $current) {
            if ($current.Path -ne $probeBinary) {throw 'Refusing cleanup of different probe executable'}
            Stop-Process -InputObject $current
            $probe.WaitForExit(5000) | Out-Null
        }
    }
    foreach($owned in $processes) {Stop-OwnedSfu $owned}
    Set-Content (Join-Path $run 'cleanup.txt') ('owned_sfus_stopped=' + @($processes | Where-Object {!$_.HasExited}).Count.Equals(0))
    Write-Output "evidence=$run"
}
