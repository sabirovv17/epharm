param(
    [Parameter(Mandatory=$true)][string]$CandidateConfig,
    [Parameter(Mandatory=$true)][string]$Output,
    [Parameter(Mandatory=$true)][string]$Proof,
    [ValidateSet('Prepare','Cleanup')][string]$Mode = 'Prepare'
)
$ErrorActionPreference = 'Stop'
if ($Mode -eq 'Cleanup') {
    $savedPolicy = Join-Path $Output 'firewall-state.json'
    if (Test-Path -LiteralPath $savedPolicy) {
        $state = Get-Content -LiteralPath $savedPolicy -Raw | ConvertFrom-Json
        foreach ($name in @($state.RuleNames)) {
            if ($name -notmatch '^EpharmPr160-CiMgmt-[a-f0-9-]+$') { throw 'Refuse unknown management rule cleanup' }
            Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
        }
        foreach ($profile in @($state.Profiles)) {
            if ($profile.Name -notin @('Domain','Private','Public')) { throw 'Unknown firewall profile' }
            Set-NetFirewallProfile -Name $profile.Name -Enabled $profile.Enabled
        }
        Write-Output 'Restored only the ephemeral pilot firewall profile settings and management rules.'
    }
    return
}
$candidate=(Get-Content -LiteralPath $CandidateConfig -Raw|ConvertFrom-Json).main
if($candidate.version -cne '1.0.84' -or $candidate.commit -cnotmatch '^[a-f0-9]{40}$' -or $candidate.sha256 -cnotmatch '^[a-f0-9]{64}$' -or $candidate.dllSha256 -cnotmatch '^[a-f0-9]{64}$' -or $candidate.outerSha256 -cnotmatch '^[a-f0-9]{64}$' -or [string]$candidate.artifactId -notmatch '^[0-9]+$' -or [string]$candidate.ciRun -notmatch '^[0-9]+$'){throw 'Exact final-main84 CI provenance is mandatory; pending/head/old83 values are forbidden'}
$commit=[string]$candidate.commit
$archiveSha=[string]$candidate.outerSha256
$bridgeSha=[string]$candidate.sha256
$dllSha=[string]$candidate.dllSha256
$bridgeName='epharm-posm-1.0.84-'+$commit.Substring(0,12)+'-compat.zip'
$driverSha = '629b51e9944762bae73948171c65d09a79595cf4c771a82ebc003fbba5b24f51'
$videoSha = '6d99d36f2c2e616d968c4e6ce2d23243fb678e72bf93207eb132a9850377db2f'
if (-not [IO.Path]::IsPathRooted($Output) -or -not [IO.Path]::IsPathRooted($Proof)) { throw 'Absolute isolated paths are required' }
if (Test-Path -LiteralPath $Output) { throw 'Refuse to overwrite a pilot workspace' }
New-Item -ItemType Directory -Path $Output -Force | Out-Null
New-Item -ItemType Directory -Path $Proof -Force | Out-Null
function Assert-Hash([string]$Path,[string]$Expected) {
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -cne $Expected) { throw "Artifact hash mismatch: $([IO.Path]::GetFileName($Path))" }
}
function Invoke-CheckedGit([string[]]$GitArguments) {
    & git @GitArguments
    if ($LASTEXITCODE -ne 0) { throw 'Pinned Git source command failed' }
}
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.AllowAutoRedirect = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(60)
$client.DefaultRequestHeaders.UserAgent.ParseAdd('epharm-isolated-display-pilot')
if ([string]::IsNullOrWhiteSpace($env:PILOT_READ_TOKEN)) { throw 'Missing read-only artifact authorization' }
$client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer',$env:PILOT_READ_TOKEN)
try {
    $response = $client.GetAsync(('https://api.github.com/repos/sabirovv17/epharm/actions/artifacts/'+$candidate.artifactId+'/zip')).GetAwaiter().GetResult()
    try {
        if ([int]$response.StatusCode -ne 302) { throw 'GitHub artifact did not return the expected redirect' }
        $location = $response.Headers.Location
        if ($location.Scheme -ne 'https' -or -not $location.Host.EndsWith('.blob.core.windows.net',[StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected artifact delivery origin' }
        $anonymous = New-Object System.Net.Http.HttpClient
        $anonymous.Timeout = [TimeSpan]::FromSeconds(60)
        try { $bytes = $anonymous.GetByteArrayAsync($location).GetAwaiter().GetResult() } finally { $anonymous.Dispose() }
        [IO.File]::WriteAllBytes((Join-Path $Output 'main-artifact.zip'),$bytes)
    } finally { $response.Dispose() }
} finally { $client.Dispose(); $handler.Dispose(); $env:PILOT_READ_TOKEN = $null }
$outer = Join-Path $Output 'main-artifact.zip'
Assert-Hash $outer $archiveSha
Expand-Archive -LiteralPath $outer -DestinationPath (Join-Path $Output 'main-artifact')
$bridge = Join-Path $Output ('main-artifact/'+$bridgeName)
Assert-Hash $bridge $bridgeSha
$metadata = Get-Content -LiteralPath (Join-Path $Output 'main-artifact/provenance.json') -Raw | ConvertFrom-Json
if ($metadata.commit -cne $commit -or $metadata.version -cne '1.0.84' -or $metadata.sha256 -cne $bridgeSha) { throw 'Main CI provenance mismatch' }
$bridgeRoot = Join-Path $Output 'main-bridge'
Expand-Archive -LiteralPath $bridge -DestinationPath $bridgeRoot
$expectedNames = @('CustomerDisplay.exe','CustomerDisplay.dll','CustomerDisplay.deps.json','CustomerDisplay.runtimeconfig.json','QRCoder.dll')
$actualNames = @(Get-ChildItem -LiteralPath $bridgeRoot -File | ForEach-Object Name)
if ($actualNames.Count -ne 5 -or @(Compare-Object ($expectedNames | Sort-Object) ($actualNames | Sort-Object)).Count -ne 0 -or @(Get-ChildItem -LiteralPath $bridgeRoot -Directory).Count -ne 0) { throw 'Unexpected bridge contents' }
Assert-Hash (Join-Path $bridgeRoot 'CustomerDisplay.dll') $dllSha
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
Push-Location $projectRoot
try {
    Invoke-CheckedGit -GitArguments @('cat-file','-e',($commit + '^{commit}'))
    Invoke-CheckedGit -GitArguments @('cat-file','-e',($commit + ':assets/fonts/Manrope/Manrope-Variable.ttf'))
    $sourceTar = Join-Path $Output 'main-source.tar'
    Invoke-CheckedGit -GitArguments @('archive','--format=tar','--output',$sourceTar,$commit,'--','App','Models','assets/fonts/Manrope')
    $sourceRoot = Join-Path $Output 'source'
    New-Item -ItemType Directory -Path $sourceRoot | Out-Null
    & tar.exe -xf $sourceTar -C $sourceRoot
    if ($LASTEXITCODE -ne 0) { throw 'Pinned source extraction failed' }
    if(-not(Test-Path -LiteralPath (Join-Path $sourceRoot 'assets/fonts/Manrope/Manrope-Variable.ttf') -PathType Leaf)){throw 'Scoped main source archive lacks the required exact-commit Manrope font'}
    $runtime = Join-Path $Output 'runtime'
    # The full runtime is only a test fixture. Replace all five application bridge
    # files with the immutable, already-tested main CI bytes before any launch.
    & dotnet publish (Join-Path $sourceRoot 'App/CustomerDisplay.csproj') -c Release -r win-x64 --self-contained true `
        -p:EnableWindowsTargeting=true -p:Version=1.0.84 -p:RuntimeFrameworkVersion=10.0.15 `
        -p:ContinuousIntegrationBuild=true -p:SourceRevisionId=$commit `
        -p:DebugType=None -p:DebugSymbols=false -o $runtime
    if ($LASTEXITCODE -ne 0) { throw 'Isolated full runtime preparation failed' }
    foreach ($name in $expectedNames) {
        Copy-Item -LiteralPath (Join-Path $bridgeRoot $name) -Destination (Join-Path $runtime $name) -Force
        Assert-Hash (Join-Path $runtime $name) ((Get-FileHash -LiteralPath (Join-Path $bridgeRoot $name)).Hash.ToLowerInvariant())
    }
} finally { Pop-Location }
$driverUrl = 'https://www.amyuni.com/downloads/usbmmidd_v2.zip'
$driverZip = Join-Path $Output 'vdd.zip'
Invoke-WebRequest -UseBasicParsing -Uri $driverUrl -OutFile $driverZip -TimeoutSec 60
Assert-Hash $driverZip $driverSha
Expand-Archive -LiteralPath $driverZip -DestinationPath (Join-Path $Output 'vdd')
$video = Join-Path $Output 'pilot-customer-display.mp4'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'ci-display-pilot-assets/pilot-customer-display.mp4') -Destination $video
Assert-Hash $video $videoSha
$policyFile = Join-Path $Output 'firewall-state.json'
$state = [ordered]@{ Profiles=@(Get-NetFirewallProfile | ForEach-Object { [ordered]@{Name=$_.Name;Enabled=$_.Enabled.ToString()} }); RuleNames=@() }
$state | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $policyFile -Encoding UTF8
$managementPorts = @(3389,5985,5986)
$rdpPolicy = Get-ItemProperty -LiteralPath 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp' -ErrorAction SilentlyContinue
if ($null -ne $rdpPolicy -and $rdpPolicy.PortNumber -ge 1 -and $rdpPolicy.PortNumber -le 65535) { $managementPorts += [int]$rdpPolicy.PortNumber }
# Preserve only already-established management peers if the runner image had its
# firewall disabled. No new public/RDP listener or broad access is introduced.
$managementPeers = @(Get-NetTCPConnection -State Established -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $managementPorts -and $_.RemoteAddress -notin @('127.0.0.1','::1') })
foreach ($peer in $managementPeers) {
    $ruleName = 'EpharmPr160-CiMgmt-' + [Guid]::NewGuid().ToString('D')
    $state.RuleNames += $ruleName
    $state | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $policyFile -Encoding UTF8
    New-NetFirewallRule -Name $ruleName -DisplayName $ruleName -Direction Inbound -Action Allow -Profile Any -Protocol TCP -LocalPort $peer.LocalPort -RemoteAddress $peer.RemoteAddress | Out-Null
}
Set-NetFirewallProfile -Name Domain,Private,Public -Enabled True
if (@(Get-NetFirewallProfile | Where-Object { $_.Enabled -ne 'True' }).Count -ne 0) { throw 'Cannot enable the normal Windows firewall for the isolated application' }
[ordered]@{ Status='PASS'; AllProfilesEnabled=$true; PreservedExistingManagementPeerCount=$managementPeers.Count; NewListenerCreated=$false } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Proof 'firewall-preflight.json') -Encoding UTF8
$proofRecord = [ordered]@{
    Status='PASS'; SourceCommit=$commit; MainCiRun=$candidate.ciRun; MainArtifactId=$candidate.artifactId
    OuterSha256=$archiveSha; BridgeSha256=$bridgeSha; ManagedDllSha256=$dllSha
    DriverUrl=$driverUrl; DriverZipSha256=$driverSha; VideoSha256=$videoSha
    FullRuntime='Prepared from exact main source; all five application files replaced with exact main CI archive bytes'
    Purpose='Temporary isolated display acceptance only; never a release or production registration'
    CapturedUtc=[DateTime]::UtcNow.ToString('o')
}
$proofRecord | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Proof 'source-provenance.json') -Encoding UTF8
Write-Output 'Prepared exact-main application and isolated display fixtures.'
