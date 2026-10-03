param(
    [string]$HostName = 'sodovaya@mc.sodove.ru',
    [int]$RemotePort = 23217,
    [int]$LocalPort = 23217,
    [int]$ExpectedTests = 17,
    [string[]]$TestFilter = @('*JdbcSessionRepositoryTest')
)
$ErrorActionPreference = 'Stop'
if ($TestFilter.Count -eq 0 -or @($TestFilter | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -ne 0) {throw 'Test filters required'}
if ($HostName -notmatch '^[a-zA-Z0-9_.-]+@[a-zA-Z0-9.-]+$') {throw 'Invalid SSH host'}
if ($LocalPort -lt 1024 -or $LocalPort -gt 65535 -or $RemotePort -lt 1024 -or $RemotePort -gt 65535) {throw 'Invalid port'}
if (Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue) {throw 'Local fixture port occupied'}
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$nonce = [guid]::NewGuid().ToString()
$name = 'volty-nearby-test-' + $nonce
$run = Join-Path $root ('.superpowers/sdd/2026-10-03-nearby-backend-session-lifecycle/postgres-' + $nonce)
New-Item -ItemType Directory -Path $run | Out-Null
$tunnel = $null
$created = $false
$previousUrl = $env:VOLTY_NEARBY_TEST_JDBC_URL
try {
    $container = & ssh -o BatchMode=yes -o ConnectTimeout=10 $HostName docker run --pull=never --rm -d --name $name --label "codex.nearby.fixture=$nonce" --tmpfs /var/lib/postgresql/data -p "127.0.0.1:${RemotePort}:5432" -e POSTGRES_USER=nearby_test -e POSTGRES_DB=nearby_test -e POSTGRES_PASSWORD=nearby-disposable-test-only postgres:17-alpine
    if ($LASTEXITCODE -ne 0) {throw 'Disposable PostgreSQL start failed'}
    $created = $true
    Set-Content (Join-Path $run 'fixture.txt') "name=$name`nnonce=$nonce`ncontainer=$container`nimage=postgres:17-alpine"
    & ssh -o BatchMode=yes -o ConnectTimeout=10 $HostName docker image inspect --format "'{{.Id}}'" postgres:17-alpine > (Join-Path $run 'image-id.txt')
    if ($LASTEXITCODE -ne 0) {throw 'Cannot record fixture image identity'}
    $ready = $false
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        & ssh -o BatchMode=yes -o ConnectTimeout=10 $HostName docker exec $name pg_isready -U nearby_test -d nearby_test *> (Join-Path $run 'postgres-ready.log')
        if ($LASTEXITCODE -eq 0) {$ready=$true; break}
        Start-Sleep -Milliseconds 250
    }
    if (!$ready) {throw 'Disposable PostgreSQL not ready'}
    $tunnel = Start-Process ssh -ArgumentList @('-N','-o','BatchMode=yes','-o','ExitOnForwardFailure=yes','-o','ServerAliveInterval=15','-L',"127.0.0.1:${LocalPort}:127.0.0.1:${RemotePort}",$HostName) -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $run 'tunnel.log')
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($tunnel.HasExited) {throw 'Owned SSH tunnel failed'}
        if (Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue) {break}
        Start-Sleep -Milliseconds 100
    }
    if (!(Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue)) {throw 'Owned SSH tunnel not listening'}
    $env:VOLTY_NEARBY_TEST_JDBC_URL = "jdbc:postgresql://127.0.0.1:${LocalPort}/nearby_test?connectTimeout=5&socketTimeout=10"
    $gradleArguments = @('-p', "$root/backend", 'postgresTest')
    foreach ($filter in $TestFilter) { $gradleArguments += @('--tests', $filter) }
    $gradleArguments += @('--rerun-tasks', '--no-build-cache', '--no-configuration-cache')
    & "$root/gradlew.bat" @gradleArguments *> (Join-Path $run 'gradle.log')
    $testExit = $LASTEXITCODE
    if (Test-Path "$root/backend/build/test-results/postgresTest") {
        Get-ChildItem "$root/backend/build/test-results/postgresTest/TEST-*.xml" | Copy-Item -Destination $run
    }
    if ($testExit -ne 0) {Get-Content (Join-Path $run 'gradle.log') -Tail 25; throw 'PostgreSQL integration tests failed'}
    $tests=0; $failures=0; $errors=0; $skipped=0
    Get-ChildItem "$root/backend/build/test-results/postgresTest/TEST-*.xml" | ForEach-Object {
        [xml]$report=Get-Content $_.FullName
        $tests += [int]$report.testsuite.tests; $failures += [int]$report.testsuite.failures
        $errors += [int]$report.testsuite.errors; $skipped += [int]$report.testsuite.skipped
    }
    if ($tests -ne $ExpectedTests -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {throw "Unexpected totals $tests/$failures/$errors/$skipped"}
    $result="postgres_tests=$tests failures=$failures errors=$errors skipped=$skipped"
    Set-Content (Join-Path $run 'result.txt') $result
    Write-Output $result
} finally {
    $env:VOLTY_NEARBY_TEST_JDBC_URL = $previousUrl
    if ($null -ne $tunnel -and !$tunnel.HasExited) {Stop-Process -InputObject $tunnel; $tunnel.WaitForExit(5000) | Out-Null}
    if ($created) {
        $labels = & ssh -o BatchMode=yes -o ConnectTimeout=10 $HostName docker inspect --format "'{{json .Config.Labels}}'" $name
        if ($LASTEXITCODE -ne 0) {throw 'Cannot verify fixture ownership for cleanup'}
        $parsed = $labels | ConvertFrom-Json
        if ($parsed.'codex.nearby.fixture' -ne $nonce) {throw 'Refusing cleanup: fixture ownership differs'}
        & ssh -o BatchMode=yes -o ConnectTimeout=10 $HostName docker stop --timeout 5 $name *> (Join-Path $run 'cleanup.log')
        if ($LASTEXITCODE -ne 0) {throw 'Owned fixture cleanup failed'}
        Set-Content (Join-Path $run 'cleanup.txt') 'owned_container_stopped=true'
    }
    Write-Output "evidence=$run"
}
