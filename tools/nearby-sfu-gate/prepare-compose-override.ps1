param(
    [Parameter(Mandatory=$true)][string]$ImageId,
    [Parameter(Mandatory=$true)][string]$Destination
)
$ErrorActionPreference = 'Stop'
if ($ImageId -cnotmatch '^sha256:[a-f0-9]{64}$') { throw 'Exact reviewed image ID required' }
if (Test-Path -LiteralPath $Destination) { throw 'Use a fresh override file' }
$yaml = @"
services:
  livekit:
    image: $ImageId
    pull_policy: never
  app:
    environment:
      VOLTY_VOICE_PROVIDER: livekit
      VOLTY_NEARBY_MODE: voice_spaces
      VOLTY_NEARBY_SFU_CONTROL_ORIGIN: http://livekit:7880
      VOLTY_NEARBY_SINGLETON_ORIGIN: "false"
"@
[System.IO.File]::WriteAllText($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination), $yaml, [System.Text.UTF8Encoding]::new($false))
