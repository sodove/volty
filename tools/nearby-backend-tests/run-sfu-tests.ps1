param([Parameter(Mandatory=$true)][string]$Workspace)
$ErrorActionPreference = 'Stop'
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$ownedWorkspace=(Resolve-Path -LiteralPath $Workspace).Path
$binary=Join-Path $ownedWorkspace 'livekit-volty-prototype.exe'
if (!(Test-Path -LiteralPath $binary)) {throw 'Build the owned SFU candidate first'}
foreach ($port in @(17881,17882)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {throw 'SFU fixture port occupied'}
}
$run=Join-Path $ownedWorkspace ('kotlin-run-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $run | Out-Null
$config=Join-Path $run 'probe.yaml'
Copy-Item -LiteralPath "$root/tools/nearby-sfu-gate/probe.yaml" -Destination $config
$oldUrl=$env:VOLTY_SFU_TEST_URL
$owned=Start-Process -FilePath $binary -ArgumentList @('--config',$config) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $run 'server-stdout.log') -RedirectStandardError (Join-Path $run 'server-stderr.log')
try {
    $ready=$false
    for ($attempt=0;$attempt -lt 30;$attempt++) {
        if ($owned.HasExited) {throw 'Owned SFU exited'}
        try {$ready=(Invoke-WebRequest http://127.0.0.1:17881 -TimeoutSec 1).StatusCode -eq 200} catch { }
        if ($ready) {break}
        Start-Sleep -Milliseconds 100
    }
    if (!$ready) {throw 'Owned SFU not ready'}
    $env:VOLTY_SFU_TEST_URL='http://127.0.0.1:17881'
    & "$root/gradlew.bat" -p "$root/backend" sfuTest --rerun-tasks --no-build-cache --no-configuration-cache *> (Join-Path $run 'gradle.log')
    $testExit=$LASTEXITCODE
    Get-ChildItem "$root/backend/build/test-results/sfuTest/TEST-*.xml" | Copy-Item -Destination $run
    foreach ($log in Get-ChildItem -LiteralPath $run -Filter '*.log') {
        if ((Get-Content -LiteralPath $log.FullName -Raw) -match '[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}') {throw 'Credential-looking log; do not print raw content'}
    }
    if ($testExit -ne 0) {throw 'SFU client runtime tests failed; inspect sanitized evidence'}
    $tests=0;$failures=0;$errors=0;$skipped=0
    Get-ChildItem "$root/backend/build/test-results/sfuTest/TEST-*.xml" | ForEach-Object {
        [xml]$r=Get-Content $_.FullName;$tests += [int]$r.testsuite.tests;$failures += [int]$r.testsuite.failures;$errors += [int]$r.testsuite.errors;$skipped += [int]$r.testsuite.skipped
    }
    if ($tests -ne 2 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {throw 'Unexpected SFU test totals'}
    Set-Content (Join-Path $run 'result.txt') "kotlin_sfu_tests=$tests failures=$failures errors=$errors skipped=$skipped"
    Get-Content (Join-Path $run 'result.txt')
} finally {
    $env:VOLTY_SFU_TEST_URL=$oldUrl
    if (!$owned.HasExited) {
        $current=Get-Process -Id $owned.Id -ErrorAction SilentlyContinue
        if ($null -ne $current) {
            if ($current.Path -ne $binary) {throw 'Refusing cleanup of different executable'}
            Stop-Process -InputObject $current
            $owned.WaitForExit(5000) | Out-Null
        }
    }
    Set-Content (Join-Path $run 'cleanup.txt') ('owned_sfu_stopped=' + $owned.HasExited)
    Write-Output "evidence=$run"
}
