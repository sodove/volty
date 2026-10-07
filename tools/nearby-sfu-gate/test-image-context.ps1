param(
    [Parameter(Mandatory=$true)][string]$Binary,
    [Parameter(Mandatory=$true)][string]$ExpectedBinarySha256,
    [Parameter(Mandatory=$true)][string]$RuntimeImage,
    [Parameter(Mandatory=$true)][string]$Destination
)
$ErrorActionPreference = 'Stop'
function Reject-Context([string]$Image, [string]$Hash, [string]$OutputPath) {
    $rejected = $false
    try { & "$PSScriptRoot/prepare-image-context.ps1" -Binary $Binary -ExpectedBinarySha256 $Hash -RuntimeImage $Image -Destination $OutputPath }
    catch { $rejected = $true }
    if (!$rejected -or (Test-Path -LiteralPath $OutputPath)) { throw 'Invalid input produced an image context' }
}
Reject-Context 'livekit/livekit-server:v1.13.5' $ExpectedBinarySha256 ($Destination + '-tag-rejected')
Reject-Context $RuntimeImage ('0' * 64) ($Destination + '-hash-rejected')
& "$PSScriptRoot/prepare-image-context.ps1" -Binary $Binary -ExpectedBinarySha256 $ExpectedBinarySha256 -RuntimeImage $RuntimeImage -Destination $Destination
$files = @(Get-ChildItem -LiteralPath $Destination -File | Select-Object -ExpandProperty Name | Sort-Object)
if (($files -join ',') -ne '.dockerignore,Dockerfile,go-build.txt,livekit-server,manifest.json') { throw 'Unexpected context files' }
$manifest = Get-Content -LiteralPath (Join-Path $Destination 'manifest.json') -Raw | ConvertFrom-Json
if ($manifest.binarySha256 -ne $ExpectedBinarySha256.ToLowerInvariant() -or $manifest.runtimeImage -ne $RuntimeImage) { throw 'Manifest lost exact artifact identity' }
if ((Get-FileHash -LiteralPath (Join-Path $Destination 'livekit-server')).Hash -ne $ExpectedBinarySha256) { throw 'Copied artifact differs' }
Write-Output 'image_context_checks=5 failed=0'
