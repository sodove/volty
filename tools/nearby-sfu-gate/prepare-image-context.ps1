param(
    [Parameter(Mandatory=$true)][string]$Binary,
    [Parameter(Mandatory=$true)][string]$ExpectedBinarySha256,
    [Parameter(Mandatory=$true)][string]$RuntimeImage,
    [Parameter(Mandatory=$true)][string]$Destination
)
$ErrorActionPreference = 'Stop'
if ($RuntimeImage -cnotmatch '^livekit/livekit-server@sha256:[a-f0-9]{64}$') { throw 'Explicit pinned LiveKit runtime digest required' }
if ($ExpectedBinarySha256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'Expected binary SHA256 required' }
$ownedBinary = (Resolve-Path -LiteralPath $Binary).Path
$hash = (Get-FileHash -LiteralPath $ownedBinary -Algorithm SHA256).Hash.ToLowerInvariant()
if ($hash -ne $ExpectedBinarySha256.ToLowerInvariant()) { throw 'Binary identity mismatch' }
$header = [System.IO.File]::ReadAllBytes($ownedBinary)
if ($header.Length -lt 20 -or $header[0] -ne 127 -or $header[1] -ne 69 -or $header[2] -ne 76 -or $header[3] -ne 70 -or
    $header[4] -ne 2 -or $header[5] -ne 1 -or $header[18] -ne 62 -or $header[19] -ne 0) { throw 'Linux amd64 ELF required' }
$metadata = (& go version -m $ownedBinary) -join "`n"
if ($LASTEXITCODE -ne 0 -or $metadata -notmatch 'path\s+github.com/livekit/livekit-server/cmd/server' -or
    $metadata -notmatch 'CGO_ENABLED=0' -or $metadata -notmatch 'GOOS=linux' -or $metadata -notmatch 'GOARCH=amd64') { throw 'Unexpected server build metadata' }
if (Test-Path -LiteralPath $Destination) { throw 'Use a fresh context destination' }
New-Item -ItemType Directory -Path $Destination | Out-Null
Copy-Item -LiteralPath $ownedBinary -Destination (Join-Path $Destination 'livekit-server')
if ((Get-FileHash -LiteralPath (Join-Path $Destination 'livekit-server')).Hash.ToLowerInvariant() -ne $hash) { throw 'Copied binary identity mismatch' }
$manifest = [ordered]@{ candidateProtocol = 4; platform = 'linux/amd64'; binarySha256 = $hash; runtimeImage = $RuntimeImage }
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText((Join-Path $Destination 'manifest.json'), ($manifest | ConvertTo-Json) + "`n", $utf8)
[System.IO.File]::WriteAllText((Join-Path $Destination 'go-build.txt'), $metadata + "`n", $utf8)
$dockerfile = "FROM $RuntimeImage`nCOPY --chmod=0755 livekit-server /livekit-server`nCOPY manifest.json /usr/share/volty-sfu/manifest.json`nLABEL ru.sodovaya.volty.sfu.protocol=`"4`"`nENTRYPOINT [`"/livekit-server`"]`n"
[System.IO.File]::WriteAllText((Join-Path $Destination 'Dockerfile'), $dockerfile, $utf8)
[System.IO.File]::WriteAllText((Join-Path $Destination '.dockerignore'), "*`n!Dockerfile`n!livekit-server`n!manifest.json`n", $utf8)
Write-Output $Destination
