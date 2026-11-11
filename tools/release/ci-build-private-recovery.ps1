param([Parameter(Mandatory=$true)][string]$CandidateConfig,[Parameter(Mandatory=$true)][string]$Bench,[Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
$c=(Get-Content -LiteralPath $CandidateConfig -Raw|ConvertFrom-Json).main
if($c.version -cne '1.0.84' -or $c.commit -cnotmatch '^[a-f0-9]{40}$'){throw 'Final merged main84 identity is required'}
$sourceCommit=[string]$c.commit
if(Test-Path -LiteralPath $Output){throw 'Do not overwrite a private lab artifact'}
$source=Join-Path $Bench 'source';$project=Join-Path $source 'App/CustomerDisplay.csproj'
[xml]$p=Get-Content -LiteralPath $project
if([string]$p.Project.PropertyGroup.Version -cne '1.0.84'){throw 'Exact main source does not contain84'}
$runtime=Join-Path $Bench 'private-lab-runtime-9.99.1'
if(Test-Path -LiteralPath $runtime){throw 'Do not reuse a private test build directory'}
# No source edits: the only app change is the CLI assembly version override.
& dotnet publish $project -c Release -r win-x64 --self-contained true `
 -p:EnableWindowsTargeting=true -p:Version=9.99.1 -p:RuntimeFrameworkVersion=10.0.15 `
 -p:ContinuousIntegrationBuild=true -p:SourceRevisionId=$sourceCommit `
 -p:DebugType=None -p:DebugSymbols=false -o $runtime
if($LASTEXITCODE -ne 0){throw 'Private recovery-only build failed'}
if([Reflection.AssemblyName]::GetAssemblyName((Join-Path $runtime 'CustomerDisplay.dll')).Version.ToString() -cne '9.99.1.0'){throw 'Private test assembly version override failed'}
$bridge=Join-Path $Bench 'private-lab-bridge-9.99.1';$null=New-Item -ItemType Directory -Path $bridge,$Output
$files=@('CustomerDisplay.exe','CustomerDisplay.dll','CustomerDisplay.deps.json','CustomerDisplay.runtimeconfig.json','QRCoder.dll')
foreach($n in $files){Copy-Item -LiteralPath (Join-Path $runtime $n) -Destination (Join-Path $bridge $n)}
$name='PRIVATE-LAB-ONLY-posm-9.99.1-'+$c.commit.Substring(0,12)+'.zip';$zip=Join-Path $Output $name
Compress-Archive -Path (Join-Path $bridge '*') -DestinationPath $zip
$r=[ordered]@{version='9.99.1';commit=$c.commit;sha256=(Get-FileHash -LiteralPath $zip).Hash.ToLowerInvariant();dllSha256=(Get-FileHash -LiteralPath (Join-Path $bridge 'CustomerDisplay.dll')).Hash.ToLowerInvariant();archive=$name;files=$files;SourceArchiveSha256=(Get-FileHash -LiteralPath (Join-Path $Bench 'main-source.tar')).Hash.ToLowerInvariant();SourceMainCiRun=$c.ciRun;SourceMainArtifactId=$c.artifactId;VersionOverrideOnly=$true;ProductionPublicationAllowed=$false;Purpose='Fresh updater recovery pilot in disconnected disposable Windows VM only';RecordedUtc=[DateTime]::UtcNow.ToString('o')}
$r|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $Output 'provenance.json') -Encoding UTF8
Write-Output 'Prepared a private9.99.1 recovery fixture; actual display runtime remains immutable official main84.'
