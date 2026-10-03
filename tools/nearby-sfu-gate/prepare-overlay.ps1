param(
    [string]$Source = "$PSScriptRoot/../../.superpowers/sdd/2026-10-03-nearby-foundations/sources/livekit-1.13.5",
    [string]$Destination = "$PSScriptRoot/../../.superpowers/sdd/2026-10-03-nearby-sfu-admission/server"
)
$ErrorActionPreference = 'Stop'
$checks = @{
    'pkg/service/roommanager.go' = 'AE299982C22770AE5C0C797E93C71BE55011D3F5A516BF1660CF1180C48362A0'
    'pkg/service/rtcservice.go' = 'B728F52E69DC6FA02541E5F0E0EB30FC3915DA7DABEF93001E85ED012B73D2FD'
    'pkg/rtc/participant.go' = '7E69B7E183C229AE665C7936A6C9D786ACDBA190E8011DD283EE5D5888E5A204'
    'pkg/service/auth.go' = 'B7D25B96867157245052EBD8553DE2A63CBCE39ED623F11AF752D012937BFC50'
}
foreach ($relative in $checks.Keys) {
    if ((Get-FileHash (Join-Path $Source $relative)).Hash -ne $checks[$relative]) {throw "Pinned source mismatch: $relative"}
}
if (Test-Path -LiteralPath $Destination) {throw 'Use a new destination; never overwrite probe evidence'}
New-Item -ItemType Directory -Path $Destination | Out-Null
Get-ChildItem -LiteralPath $Source -Force | Copy-Item -Destination $Destination -Recurse
New-Item -ItemType Directory -Path (Join-Path $Destination 'pkg/voltylease') | Out-Null
Copy-Item -LiteralPath "$PSScriptRoot/lease/gate.go" -Destination (Join-Path $Destination 'pkg/voltylease/gate.go')
Copy-Item -LiteralPath "$PSScriptRoot/lease/readiness.go" -Destination (Join-Path $Destination 'pkg/voltylease/readiness.go')
Copy-Item -LiteralPath "$PSScriptRoot/overlay/voltyleases.go" -Destination (Join-Path $Destination 'pkg/service/voltyleases.go')
Copy-Item -LiteralPath "$PSScriptRoot/overlay/voltybarrier.go" -Destination (Join-Path $Destination 'pkg/service/voltybarrier.go')
Copy-Item -LiteralPath "$PSScriptRoot/overlay/voltyreadiness.go" -Destination (Join-Path $Destination 'pkg/service/voltyreadiness.go')
function Replace-One([string]$Text,[string]$Needle,[string]$Replacement) {
    if (($Text.Split($Needle,[System.StringSplitOptions]::None).Length - 1) -ne 1) {throw "Patch anchor not unique: $Needle"}
    return $Text.Replace($Needle,$Replacement)
}
function Read-Source([string]$Relative) {return (Get-Content -LiteralPath (Join-Path $Destination $Relative) -Raw).Replace("`r`n","`n")}
function Write-Source([string]$Relative,[string]$Text) {Set-Content -LiteralPath (Join-Path $Destination $Relative) -Value $Text -NoNewline}
$manager = Read-Source 'pkg/service/roommanager.go'
$manager = Replace-One $manager 'func (r *RoomManager) StartSession(' 'func (r *RoomManager) startSessionUnguarded('
$manager = Replace-One $manager "`tuseOneShotSignallingMode bool,`n) error {" "`tuseOneShotSignallingMode bool,`n`tvoltyCapture func(types.LocalParticipant),`n) error {"
$manager = Replace-One $manager "`t`t`tif err = room.ResumeParticipant(" "`t`t`tvoltyCapture(participant)`n`t`t`tif err = room.ResumeParticipant("
$manager = Replace-One $manager "`ticeConfig := r.setIceConfig(room.Name(), participant)" "`tvoltyCapture(participant)`n`ticeConfig := r.setIceConfig(room.Name(), participant)"
Write-Source 'pkg/service/roommanager.go' $manager
$routes = Read-Source 'pkg/service/rtcservice.go'
$routes = Replace-One $routes 'func (s *RTCService) SetupRoutes(mux *http.ServeMux) {' "func (s *RTCService) SetupRoutes(mux *http.ServeMux) {`n`ts.setupVoltyLeases(mux)"
Write-Source 'pkg/service/rtcservice.go' $routes
$participant = Read-Source 'pkg/rtc/participant.go'
$participant = Replace-One $participant "import (`n" "import (`n`t`"context`"`n"
$participant = Replace-One $participant "`tdataBlob *ParticipantDataBlob" "`tvoltyMediaClosed chan struct{}`n`tdataBlob *ParticipantDataBlob"
$participant = Replace-One $participant "`t`tdisconnected: make(chan struct{})," "`t`tvoltyMediaClosed: make(chan struct{}),`n`t`tdisconnected: make(chan struct{}),"
# Patch the one transport cleanup goroutine; do not equate isClosed with transport closure.
$participant = Replace-One $participant "`tgo func() {`n`t`tp.SubscriptionManager.Close(isExpectedToResume)" "`tgo func() {`n`t`tdefer close(p.voltyMediaClosed)`n`t`tp.SubscriptionManager.Close(isExpectedToResume)"
$participant += @'

func (p *ParticipantImpl) VoltyWaitMediaClosed(ctx context.Context) error {
    select {case <-p.voltyMediaClosed: return nil; case <-ctx.Done(): return ctx.Err()}
}
'@
Write-Source 'pkg/rtc/participant.go' $participant
$auth = Read-Source 'pkg/service/auth.go'
$auth = Replace-One $auth 'errors.New("invalid token: "+authToken+", error: "+err.Error())' 'ErrInvalidAuthorizationToken'
Write-Source 'pkg/service/auth.go' $auth
& gofmt -w (Join-Path $Destination 'pkg/voltylease/gate.go') (Join-Path $Destination 'pkg/voltylease/readiness.go') (Join-Path $Destination 'pkg/service/voltyreadiness.go') (Join-Path $Destination 'pkg/service/voltyleases.go') (Join-Path $Destination 'pkg/service/voltybarrier.go') (Join-Path $Destination 'pkg/service/roommanager.go') (Join-Path $Destination 'pkg/service/rtcservice.go') (Join-Path $Destination 'pkg/rtc/participant.go') (Join-Path $Destination 'pkg/service/auth.go')
if ($LASTEXITCODE -ne 0) {throw 'gofmt failed'}
Write-Output $Destination
