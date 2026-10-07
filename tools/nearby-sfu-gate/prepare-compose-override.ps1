param(
    [Parameter(Mandatory=$true)][string]$ImageId,
    [string]$BackendImageId,
    [Parameter(Mandatory=$true)][string]$Destination
)
$ErrorActionPreference = 'Stop'
if ($ImageId -cnotmatch '^sha256:[a-f0-9]{64}$') { throw 'Exact reviewed image ID required' }
if ($BackendImageId -and $BackendImageId -cnotmatch '^sha256:[a-f0-9]{64}$') { throw 'Exact reviewed backend image ID required' }
if (Test-Path -LiteralPath $Destination) { throw 'Use a fresh override file' }
$backendBinding = if ($BackendImageId) {
@"
    image: $BackendImageId
    pull_policy: never
    build: !reset null
    healthcheck:
      test: ["CMD", "/usr/bin/curl", "--fail", "--silent", "--max-time", "5", "http://127.0.0.1:8080/health"]
"@
} else { '' }
$yaml = @"
services:
  livekit:
    image: $ImageId
    pull_policy: never
  app:
$backendBinding
    environment:
      VOLTY_VOICE_PROVIDER: livekit
      VOLTY_NEARBY_MODE: voice_spaces
      VOLTY_NEARBY_SFU_CONTROL_ORIGIN: http://livekit:7880
      VOLTY_NEARBY_SINGLETON_ORIGIN: "false"
"@
[System.IO.File]::WriteAllText($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination), $yaml, [System.Text.UTF8Encoding]::new($false))
