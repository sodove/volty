param([Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference = 'Stop'
# Each invocation must use a fresh owned destination, not earlier generated output.
& "$PSScriptRoot/prepare-overlay.ps1" -Production -Destination $Destination | Out-Null
$leases = Get-Content -LiteralPath (Join-Path $Destination 'pkg/service/voltyleases.go') -Raw
if (Test-Path -LiteralPath (Join-Path $Destination 'pkg/service/voltybarrier.go')) { throw 'Production contains test barrier implementation' }
if ($leases -match 'voltyWaitBarrier|voltyMarkRevoking|voltyBarrierControl|/volty/test/') { throw 'Production contains test barrier hooks' }
foreach ($action in @('register','renew','renew-connected','revoke','readiness','participant')) {
    if (!$leases.Contains('/volty/leases/' + $action)) { throw "Missing production control: $action" }
}
if (!$leases.Contains('voltyGate.Admit') -or !$leases.Contains('VoltyWaitMediaClosed')) { throw 'Production lost media admission/closure fence' }
Write-Output 'production_overlay_checks=4 failed=0'
