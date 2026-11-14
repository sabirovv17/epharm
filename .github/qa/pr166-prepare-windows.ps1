param(
    [Parameter(Mandatory=$true)][string]$CandidateConfig,
    [Parameter(Mandatory=$true)][string]$Bench,
    [Parameter(Mandatory=$true)][string]$Proof
)
$ErrorActionPreference='Stop'
$c=Get-Content -LiteralPath $CandidateConfig -Raw|ConvertFrom-Json
if($c.exactMain -cnotmatch '^[a-f0-9]{40}$' -or $c.main.version -cne '1.0.86' -or $c.mode.windows -ne $true){throw 'Private exact-main86 Windows config is required'}
$token=$env:PILOT_READ_TOKEN
if([string]::IsNullOrWhiteSpace($token)){throw 'Missing read-only artifact token'}
$names=@('CustomerDisplay.exe','CustomerDisplay.dll','CustomerDisplay.deps.json','CustomerDisplay.runtimeconfig.json','QRCoder.dll')
function Hash([string]$Path){return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Need([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message}}
try {
    & "$PSScriptRoot/../../tools/release/ci-display-pilot-prepare.ps1" -CandidateConfig $CandidateConfig -Output $Bench -Proof $Proof
    $previous=$c.previous
    Add-Type -AssemblyName System.Net.Http
    $handler=New-Object System.Net.Http.HttpClientHandler
    $handler.AllowAutoRedirect=$false
    $client=New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout=[TimeSpan]::FromSeconds(60)
    $client.DefaultRequestHeaders.UserAgent.ParseAdd('epharm-private-pr166-qualification')
    $client.DefaultRequestHeaders.Authorization=New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer',$token)
    try {
        $response=$client.GetAsync(('https://api.github.com/repos/sabirovv17/epharm/actions/artifacts/'+$previous.artifactId+'/zip')).GetAwaiter().GetResult()
        try {
            Need ([int]$response.StatusCode -eq 302) 'Expected official artifact redirect'
            $location=$response.Headers.Location
            Need ($location.Scheme -eq 'https' -and $location.Host.EndsWith('.blob.core.windows.net',[StringComparison]::OrdinalIgnoreCase) -and $location.Port -eq 443 -and [string]::IsNullOrEmpty($location.UserInfo)) 'Unexpected artifact origin'
            $anonymousHandler=New-Object System.Net.Http.HttpClientHandler
            $anonymousHandler.AllowAutoRedirect=$false
            $anonymous=New-Object System.Net.Http.HttpClient($anonymousHandler)
            $anonymous.Timeout=[TimeSpan]::FromSeconds(90)
            try {
                $download=$anonymous.GetAsync($location).GetAwaiter().GetResult()
                try {
                    Need ([int]$download.StatusCode -eq 200) 'Unexpected anonymous artifact response'
                    [IO.File]::WriteAllBytes((Join-Path $Bench 'previous-artifact.zip'),$download.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult())
                } finally {$download.Dispose()}
            } finally {$anonymous.Dispose();$anonymousHandler.Dispose()}
        } finally {$response.Dispose()}
    } finally {$client.Dispose();$handler.Dispose()}
    $outer=Join-Path $Bench 'previous-artifact.zip'
    Need ((Hash $outer) -ceq $previous.outerSha256) 'Previous85 outer hash mismatch'
    $outerRoot=Join-Path $Bench 'previous-artifact'
    Expand-Archive -LiteralPath $outer -DestinationPath $outerRoot
    $zipName='epharm-posm-1.0.85-'+$previous.commit.Substring(0,12)+'-compat.zip'
    $previousZip=Join-Path $outerRoot $zipName
    Need ((Hash $previousZip) -ceq $previous.sha256) 'Previous85 bridge hash mismatch'
    $metadata=Get-Content -LiteralPath (Join-Path $outerRoot 'provenance.json') -Raw|ConvertFrom-Json
    Need ($metadata.version -ceq '1.0.85' -and $metadata.commit -ceq $previous.commit -and $metadata.sha256 -ceq $previous.sha256) 'Previous85 source provenance mismatch'
    $previousBridge=Join-Path $Bench 'previous-bridge'
    Expand-Archive -LiteralPath $previousZip -DestinationPath $previousBridge
    $actual=@(Get-ChildItem -LiteralPath $previousBridge -File|ForEach-Object Name)
    Need ($actual.Count -eq 5 -and @(Compare-Object ($names|Sort-Object) ($actual|Sort-Object)).Count -eq 0 -and @(Get-ChildItem -LiteralPath $previousBridge -Directory).Count -eq 0) 'Previous85 bridge allowlist mismatch'
    Need ((Hash (Join-Path $previousBridge 'CustomerDisplay.dll')) -ceq $previous.dllSha256) 'Previous85 DLL hash mismatch'

    $hostOutput=Join-Path $Bench 'ui-host'
    & dotnet publish "$PSScriptRoot/pr166-ui/Epharm.PrivateStaffPilot.csproj" -c Release -r win-x64 --self-contained true `
        -p:AppArtifactRoot="$Bench/main-bridge" -p:PublishSingleFile=false -o $hostOutput
    if($LASTEXITCODE -ne 0){throw 'Supplemental private host build failed'}
    Need (-not(Test-Path -LiteralPath (Join-Path $hostOutput 'CustomerDisplay.dll'))) 'Private=false must keep official product DLL outside host output'

    $project=Join-Path $Bench 'source/App/CustomerDisplay.csproj'
    Need ((Get-Content -LiteralPath $project -Raw).Contains('<Version>1.0.86</Version>')) 'Private fixture source is not exact-main86'
    $privateRuntime=Join-Path $Bench 'private-runtime-9.99.3'
    & dotnet publish $project -c Release -r win-x64 --self-contained true `
        -p:EnableWindowsTargeting=true -p:Version=9.99.3 -p:RuntimeFrameworkVersion=$($c.main.runtimeVersion) `
        -p:ContinuousIntegrationBuild=true -p:SourceRevisionId=$($c.exactMain) `
        -p:DebugType=None -p:DebugSymbols=false -o $privateRuntime
    if($LASTEXITCODE -ne 0){throw 'Private9.99.3 fixture build failed'}
    Need ([Reflection.AssemblyName]::GetAssemblyName((Join-Path $privateRuntime 'CustomerDisplay.dll')).Version.ToString() -ceq '9.99.3.0') 'Private fixture assembly version mismatch'
    $fixtures=Join-Path $Bench 'recovery-fixtures'
    New-Item -ItemType Directory -Path $fixtures|Out-Null
    $fixtureProof=@()
    foreach($variant in @('healthy','bad-start')){
        $bridge=Join-Path $Bench ('private-bridge-9.99.3-'+$variant)
        New-Item -ItemType Directory -Path $bridge|Out-Null
        foreach($name in $names){Copy-Item -LiteralPath (Join-Path $privateRuntime $name) -Destination (Join-Path $bridge $name)}
        if($variant -ceq 'bad-start'){
            # Deliberately invalid PE, only inside this private fault fixture.
            [IO.File]::WriteAllBytes((Join-Path $bridge 'CustomerDisplay.exe'),[Text.Encoding]::ASCII.GetBytes('PRIVATE-PR166-INVALID-PE-STARTUP-FAILURE-NEVER-PUBLISH'))
        }
        $fileHashes=[ordered]@{}
        foreach($name in $names){$fileHashes[$name]=Hash (Join-Path $bridge $name)}
        $archiveName='PRIVATE-NEVER-PUBLISH-posm-9.99.3-'+$variant+'-'+$c.exactMain.Substring(0,12)+'.zip'
        $archive=Join-Path $fixtures $archiveName
        Compress-Archive -Path (Join-Path $bridge '*') -DestinationPath $archive
        $record=[ordered]@{version='9.99.3';commit=$c.exactMain;variant=$variant;archive=$archiveName;sha256=(Hash $archive);dllSha256=$fileHashes['CustomerDisplay.dll'];files=$names;fileHashes=$fileHashes;SourceArchiveSha256=(Hash (Join-Path $Bench 'main-source.tar'));SourceMainCiRun=$c.main.ciRun;SourceMainArtifactId=$c.main.artifactId;VersionOverrideOnly=($variant -ceq 'healthy');NativeExeDeliberatelyInvalid=($variant -ceq 'bad-start');ProductionPublicationAllowed=$false;Purpose='Disconnected disposable-VM updater fault/recovery only'}
        $record|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $fixtures ($variant+'-provenance.json')) -Encoding UTF8
        $fixtureProof+=$record
    }
    $transfer=Join-Path $Bench 'vm-transfer'
    New-Item -ItemType Directory -Path $transfer|Out-Null
    Copy-Item -LiteralPath (Join-Path $Bench 'runtime') -Destination (Join-Path $transfer 'runtime86') -Recurse
    Copy-Item -LiteralPath (Join-Path $Bench 'main-bridge') -Destination (Join-Path $transfer 'official86') -Recurse
    Copy-Item -LiteralPath $previousBridge -Destination (Join-Path $transfer 'official85') -Recurse
    foreach($name in @('pilot-customer-display.mp4','vdd.zip')){Copy-Item -LiteralPath (Join-Path $Bench $name) -Destination (Join-Path $transfer $name)}
    Copy-Item -LiteralPath $CandidateConfig -Destination (Join-Path $transfer 'candidate.json')
    Copy-Item -LiteralPath (Join-Path $Proof 'source-provenance.json') -Destination (Join-Path $transfer 'source-provenance.json')
    $runtimeHashes=[ordered]@{}
    foreach($file in @(Get-ChildItem -LiteralPath $transfer -Recurse -File)){
        $relative=$file.FullName.Substring($transfer.Length+1).Replace('\','/')
        $runtimeHashes[$relative]=Hash $file.FullName
    }
    $hostHashes=[ordered]@{}
    foreach($file in @(Get-ChildItem -LiteralPath $hostOutput -Recurse -File)){$hostHashes[$file.FullName.Substring($hostOutput.Length+1).Replace('\','/')]=Hash $file.FullName}
    $record=[ordered]@{status='PREPARED_NOT_RUNTIME_ACCEPTANCE';main=$c.main;previous=$c.previous;officialBridgeNames=$names;runtime86Hashes=$runtimeHashes;privateHostHashes=$hostHashes;privateFixtureProof=$fixtureProof;ProductDllCopiedIntoHost=$false;ProductionPublicationAllowed=$false;MainCiStillRequiredBeforePromotion=$true;CapturedUtc=[DateTime]::UtcNow.ToString('o')}
    $record|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $Proof 'transfer-provenance.json') -Encoding UTF8
    Copy-Item -LiteralPath (Join-Path $Proof 'transfer-provenance.json') -Destination (Join-Path $transfer 'transfer-provenance.json')
    Copy-Item -LiteralPath (Join-Path $Proof 'transfer-provenance.json') -Destination (Join-Path $hostOutput 'transfer-provenance.json')
    Write-Output 'PRIVATE_WINDOWS_TRANSFER_PREPARED_NOT_ACCEPTED'
} finally {
    $token=$null
    $env:PILOT_READ_TOKEN=$null
}
