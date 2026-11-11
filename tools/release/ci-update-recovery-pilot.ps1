param([Parameter(Mandatory=$true)][string]$CandidateConfig,[Parameter(Mandatory=$true)][string]$Bench,[Parameter(Mandatory=$true)][string]$LabArtifact,[Parameter(Mandatory=$true)][string]$Proof)
$ErrorActionPreference='Stop'
$root='C:\epharm-pr160-pilot';$install="$root\install";$control="$install\.epharm-update";$psExe=Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$c=(Get-Content -LiteralPath $CandidateConfig -Raw|ConvertFrom-Json).main
if($c.version -cne '1.0.84' -or $c.commit -cnotmatch '^[a-f0-9]{40}$'){throw 'Final merged main84 identity is mandatory'}
if(Test-Path -LiteralPath $root){throw 'Refuse existing recovery pilot root'}
if(-not [Environment]::UserInteractive -or [Diagnostics.Process]::GetCurrentProcess().SessionId -eq 0){throw 'A real interactive Windows session is required'}
foreach($fwProfile in @(Get-NetFirewallProfile -PolicyStore ActiveStore)){if(-not $fwProfile.Enabled){throw 'Normal Windows firewall must already be enabled'}}
$assets=Join-Path $PSScriptRoot 'ci-recovery-assets';$names=@('CustomerDisplay.exe','CustomerDisplay.dll','CustomerDisplay.deps.json','CustomerDisplay.runtimeconfig.json','QRCoder.dll')
$created=[DateTime]::UtcNow;$nonce=[Guid]::NewGuid().ToString('N');$rules=@();$tasks=@();$mock=$null;$helperIdentities=@();$baseline=$null
$summary=[ordered]@{Status='failed';SourceCommit=$c.commit;Version='1.0.84';OsRebootPerformed=$false;TrueOsRebootAcceptance='PENDING_SEPARATE_EXCLUSIVE_VM';PhysicalPowerLossTested=$false;ProductionRegistration=$false;Cases=@();CleanupErrors=@()}
$null=New-Item -ItemType Directory -Path $Proof
foreach($n in @('','install','data','evidence','temp','mock','profile','appdata','localappdata','profile\AppData\Local','profile\AppData\Roaming','profile\AppData\Local\Microsoft\Windows\PowerShell')){$null=New-Item -ItemType Directory -Path (Join-Path $root $n)}
function Emit([string]$name,$value){[IO.File]::AppendAllText((Join-Path $Proof 'events.jsonl'),(([ordered]@{Event=$name;Utc=[DateTime]::UtcNow.ToString('o');Data=$value}|ConvertTo-Json -Depth 8 -Compress)+"`r`n"))}
function Hash([string]$p){(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant()}
function OwnApps { @(Get-Process CustomerDisplay -ErrorAction SilentlyContinue|Where-Object {try{$_.Path -ieq "$install\CustomerDisplay.exe"}catch{$false}}) }
function Stop-OwnedApp([switch]$Force){
 foreach($p in @(OwnApps)){
  if($p.StartTime.ToUniversalTime() -lt $created){throw 'Own path is occupied by an older foreign process'}
  $id=$p.Id;$ticks=$p.StartTime.ToUniversalTime().Ticks;$now=Get-Process -Id $id -ErrorAction Stop
  if($now.Path -ine "$install\CustomerDisplay.exe" -or $now.StartTime.ToUniversalTime().Ticks -ne $ticks){throw 'App identity changed'}
  if(-not $Force){
   $window=[EpharmRecoveryWindows]::Find($id)
   if($window){
    $now=Get-Process -Id $id -ErrorAction Stop
    if($now.Path -ine "$install\CustomerDisplay.exe" -or $now.StartTime.ToUniversalTime().Ticks -ne $ticks){throw 'App identity changed before WM_CLOSE'}
    [EpharmRecoveryWindows]::RequestClose($window.Handle,$id)
    Emit 'owned-app-wm-close' @{Pid=$id;StartTicks=$ticks;WindowHandle=$window.Handle;Title=$window.Title}
   }
   if($now.WaitForExit(10000)){continue}
  }
  $now=Get-Process -Id $id -ErrorAction Stop
  if($now.StartTime.ToUniversalTime().Ticks -ne $ticks -or $now.Path -ine "$install\CustomerDisplay.exe"){throw 'Refuse foreign process termination'}
  $now.Kill();if(-not $now.WaitForExit(10000)){throw 'Owned app did not terminate'}
 }
}
function Child([string]$Exe,[string]$Arguments){
 $p=New-Object Diagnostics.ProcessStartInfo;$p.FileName=$Exe;$p.Arguments=$Arguments;$p.WorkingDirectory=$root;$p.UseShellExecute=$false;$p.EnvironmentVariables.Clear()
 $allow=@{SystemRoot=$env:SystemRoot;WINDIR=$env:SystemRoot;SystemDrive=$env:SystemDrive;ComSpec=(Join-Path $env:SystemRoot 'System32\cmd.exe');PATH=((Join-Path $env:SystemRoot 'System32')+';'+$env:SystemRoot);TEMP="$root\temp";TMP="$root\temp";USERPROFILE="$root\profile";APPDATA="$root\profile\AppData\Roaming";LOCALAPPDATA="$root\profile\AppData\Local";PSModuleAnalysisCachePath="$root\profile\AppData\Local\Microsoft\Windows\PowerShell\ModuleAnalysisCache";EPHARM_POSM_CONFIG="$root\data\posm.json";EPHARM_APP_LOG="$root\evidence\app.log";EPHARM_STANDARDN_DB_ENABLED='false';EPHARM_STAFF_AUTH_ENABLED='false';EPHARM_NO_VIDEO='true';EPHARM_DEBUG='0';EPHARM_DISABLE_CONSOLE_LOG='true'}
 foreach($k in $allow.Keys){$p.EnvironmentVariables[$k]=[string]$allow[$k]}
 if(@($p.EnvironmentVariables.Keys|Where-Object {$_ -match 'TOKEN|SECRET|PASSWORD|GITHUB|ACTIONS'}).Count){throw 'Unexpected credential environment'}
 [Diagnostics.Process]::Start($p)
}
function Start-App { if(@(OwnApps).Count){throw 'App already running'};$p=Child "$install\CustomerDisplay.exe" '';Emit 'app-launched' @{Pid=$p.Id;StartTicks=$p.StartTime.ToUniversalTime().Ticks;SessionId=$p.SessionId};$p }
function Assert-Alive([string]$Version){
 $until=[DateTime]::UtcNow.AddSeconds(60)
 do{
  $a=@(OwnApps);$heartbeat=if(Test-Path -LiteralPath "$root\evidence\heartbeat.txt"){(Get-Item -LiteralPath "$root\evidence\heartbeat.txt").LastWriteTimeUtc}else{[DateTime]::MinValue}
  if($a.Count -eq 1 -and $a[0].SessionId -eq [Diagnostics.Process]::GetCurrentProcess().SessionId -and $heartbeat -gt $a[0].StartTime.ToUniversalTime() -and ([DateTime]::UtcNow-$heartbeat).TotalSeconds -lt 15 -and [Reflection.AssemblyName]::GetAssemblyName("$install\CustomerDisplay.dll").Version.ToString() -ceq $Version){
   $window=[EpharmRecoveryWindows]::Find($a[0].Id)
   if($window -and $window.Responding){$script:lastAppWindow=$window;return $a[0]}
  }
  Start-Sleep -Milliseconds 250
 }while([DateTime]::UtcNow -lt $until)
 throw 'No usable visible PID-owned app HWND with fresh UI heartbeat'
}
function Stop-Mock {
 if(-not $script:mock){return}
 $server=$script:mock
 try{$server.Dispose();$script:lastOfflineProof=$server.AssertOffline()}
 finally{[IO.File]::AppendAllLines((Join-Path $Proof 'mock-http-trace.txt'),[string[]]$server.Trace.ToArray())}
 $script:mock=$null
 Emit 'owned-loopback-stopped' @{ListenerClosed=$script:lastOfflineProof.ListenerClosed;ThreadJoined=$script:lastOfflineProof.ThreadJoined;HttpUnavailable=$script:lastOfflineProof.HttpUnavailable;FailureKind=$script:lastOfflineProof.FailureKind;SocketFailureKind=$script:lastOfflineProof.SocketFailureKind;HttpConnectionRefused=$script:lastOfflineProof.HttpConnectionRefused;ProbeMilliseconds=$script:lastOfflineProof.ProbeMilliseconds}
}
function Start-Mock {
 if($script:mock){throw 'Mock already running'}
 try{
  if(-not ('EpharmRecoveryLoopback' -as [type])){Add-Type -Path (Join-Path $PSScriptRoot 'ci-recovery-loopback.cs')}
  $script:mock=New-Object EpharmRecoveryLoopback($root);$script:lastOfflineProof=$null
  Emit 'owned-loopback-ready' @{OwnerProcessId=$PID;Origin='http://127.0.0.1:18761';ReadyIdentityVerified=$true;BackgroundThread=$true}
 }catch{
  $failure=$_.Exception.GetBaseException();$code=$failure.HResult
  if($failure -is [ComponentModel.Win32Exception]){$code=$failure.NativeErrorCode}
  Emit 'owned-loopback-startup-failed' @{Type=$failure.GetType().FullName;Code=$code}
  throw ('Owned in-process loopback mock failed to start: '+$failure.GetType().Name+' code='+$code)
 }
}
function No-Release {[IO.File]::WriteAllText("$root\mock\manifest.current.json",'{"platform":"win-x64","current":false}')}
function Activate([string]$Case){if(Test-Path -LiteralPath "$control\active.json"){throw 'Cannot change manifest during active update'};$dir="$root\cases\$Case";$p=Get-Content -LiteralPath "$dir\provenance.json" -Raw|ConvertFrom-Json;if($p.case -cne $Case -or $p.productionPublicationAllowed -ne $false -or (Hash "$dir\candidate.zip") -cne $p.servedSha256){throw 'Private case provenance mismatch'};Copy-Item -LiteralPath "$dir\candidate.zip" -Destination "$root\mock\candidate.zip" -Force;Copy-Item -LiteralPath "$dir\manifest.current.json" -Destination "$root\mock\manifest.current.json" -Force;return $p.servedSha256}
function Assert-Preserved([string]$Archive,[string]$Version){
 if(@(OwnApps).Count -or (Test-Path -LiteralPath "$control\active.json")){throw 'Quiescent verification requires no app/active transaction'}
 $cfg=Hash "$root\data\posm.json";$db=Hash "$root\data\outbox.db";$wal=if(Test-Path -LiteralPath "$root\data\outbox.db-wal"){(Get-Item -LiteralPath "$root\data\outbox.db-wal").Length}else{0}
 if($cfg -cne $script:baseline.ConfigSha256 -or $db -cne $script:baseline.OutboxSha256 -or $wal -ne 0){throw 'Config/real SQLite bytes or WAL changed'}
 if([Reflection.AssemblyName]::GetAssemblyName("$install\CustomerDisplay.dll").Version.ToString() -cne $Version){throw 'Installed assembly version mismatch'}
 Add-Type -AssemblyName System.IO.Compression.FileSystem;$z=[IO.Compression.ZipFile]::OpenRead($Archive);$fileHashProofs=@()
 try{foreach($n in $names){$e=$z.GetEntry($n);if(-not $e){throw 'Missing expected bridge file'};$stream=$e.Open();$h=[Security.Cryptography.SHA256]::Create();try{$expected=([BitConverter]::ToString($h.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$h.Dispose();$stream.Dispose()};$actual=Hash (Join-Path $install $n);if($actual -cne $expected){throw ('File mismatch: '+$n)};$fileHashProofs+=@{Path=$n;Sha256=$actual;MatchesArchive=$true}}}finally{$z.Dispose()}
 $result=@{Version=$Version;ConfigSha256=$cfg;OutboxSha256=$db;WalLength=$wal;FiveFiles=$fileHashProofs;Status='PASS'};Emit 'quiescent-preservation' $result;return $result
}
function Observe([string]$Case,[string]$Sha,[string]$Expected,[switch]$Offline,[switch]$Interrupt){
 $start=[DateTime]::UtcNow;$last='';$interrupted=$false;$offlineStopped=$false;$until=$start.AddSeconds(180)
 do{foreach($dir in @(Get-ChildItem -LiteralPath $control -Directory -ErrorAction SilentlyContinue)){
  if($dir.Name -notmatch '^[a-f0-9]{32}$'){continue};$pp=Join-Path $dir.FullName 'plan.json';$sp=Join-Path $dir.FullName 'state.json';if(-not(Test-Path $pp) -or -not(Test-Path $sp)){continue}
  try{$p=Get-Content -LiteralPath $pp -Raw|ConvertFrom-Json;$s=Get-Content -LiteralPath $sp -Raw|ConvertFrom-Json}catch{continue}
  if($p.PackageSha256 -cne $Sha -or ([DateTime]::Parse($s.UpdatedAt).ToUniversalTime() -lt $start -and $s.Status -in @('committed','rolled-back','recovery-required'))){continue}
  if($s.ProcessId -and $s.ProcessStartTicks -and -not @($script:helperIdentities|Where-Object {$_.Id -eq $s.ProcessId -and $_.Ticks -eq $s.ProcessStartTicks}).Count){$script:helperIdentities+=@{Id=$s.ProcessId;Ticks=$s.ProcessStartTicks}}
  $key=$p.Id+':'+$s.Status;if($key -cne $last){Emit 'transaction-state' @{Case=$Case;Id=$p.Id;Version=$p.Version;Sha256=$Sha;Status=$s.Status;StateUpdatedUtc=$s.UpdatedAt;HelperPid=$s.ProcessId;HelperStartTicks=$s.ProcessStartTicks};$last=$key}
  $ready=Join-Path $dir.FullName 'ready.json'
  if($s.Status -eq 'awaiting-health' -and $Offline -and -not $offlineStopped){
   if(Test-Path -LiteralPath $ready){throw 'Offline fault missed the pre-readiness window'}
   Stop-Mock
   if(Test-Path -LiteralPath $ready){throw 'Candidate acknowledged before offline HTTP unavailability was proven'}
   $offlineStopped=$true;Emit 'mock-stopped-before-local-readiness' @{Case=$Case;BackendOffline=$true;HttpUnavailable=$script:lastOfflineProof.HttpUnavailable;FailureKind=$script:lastOfflineProof.FailureKind;HttpConnectionRefused=$script:lastOfflineProof.HttpConnectionRefused;ReadyAbsent=$true}
  }
  if($s.Status -eq 'awaiting-health' -and $Interrupt -and -not $interrupted){
   if(Test-Path -LiteralPath $ready){throw 'Interruption missed the pre-readiness window'}
   $helper=Get-Process -Id $s.ProcessId -ErrorAction Stop;if($helper.Path -ine $psExe -or $helper.StartTime.ToUniversalTime().Ticks -ne $s.ProcessStartTicks){throw 'Helper PID/start/path changed'}
   $apps=@(OwnApps);if($apps.Count -eq 0){continue};if($apps.Count -ne 1 -or $apps[0].StartTime.ToUniversalTime() -lt $start){throw 'Expected exactly one owned candidate process'}
   $helper.Kill();if(-not $helper.WaitForExit(5000)){throw 'Owned helper did not terminate'};Stop-OwnedApp -Force;if(Test-Path -LiteralPath $ready){throw 'Candidate acknowledged before interruption completed'};$interrupted=$true
   if(-not(Test-Path -LiteralPath "$control\active.json")){throw 'No pending marker after helper interruption'}
   Emit 'helper-killed-and-candidate-process-restarted' @{Case=$Case;HelperPid=$s.ProcessId;HelperStartTicks=$s.ProcessStartTicks;ReadyAbsent=$true;ActiveMarkerPresent=$true;OsRebootPerformed=$false;RecoveryTrigger='Ordinary application startup gate'}
   $null=Start-App
  }
  if($s.Status -in @('committed','rolled-back','recovery-required')){
   if($s.Status -cne $Expected){throw ('Unexpected terminal update state: '+$s.Status)};if(Test-Path -LiteralPath "$control\active.json"){continue}
   if($Offline -and -not $offlineStopped){throw 'Requested offline fault was not injected before commit'}
   if($Interrupt -and -not $interrupted){throw 'Requested helper interruption was not injected before rollback'}
   $fileProof=@(foreach($f in $p.Files){$hash=Hash (Join-Path $install $f.RelativePath);$expectedHash=if($Expected -eq 'committed'){$f.NewSha256}else{$f.OldSha256};if($hash -ine $expectedHash){throw 'Terminal live files do not match transaction verdict'};@{Path=$f.RelativePath;Sha256=$hash;MatchesExpected=$true}})
   $result=[ordered]@{Case=$Case;Id=$p.Id;PackageSha256=$Sha;Version=$p.Version;Status=$s.Status;ActiveMarkerPresent=$false;Files=$fileProof;BackendStoppedBeforeAck=$offlineStopped;OfflineHttpUnavailable=$offlineStopped;OfflineFailureKind=if($offlineStopped){$script:lastOfflineProof.FailureKind}else{$null};HelperInterrupted=$interrupted;OsRebootPerformed=$false}
   if($Expected -eq 'committed'){$ack=Get-Content -LiteralPath $ready -Raw|ConvertFrom-Json;$ap=Get-Process -Id $ack.ProcessId -ErrorAction Stop;$elapsed=((Get-Item -LiteralPath $ready).LastWriteTimeUtc-$ap.StartTime.ToUniversalTime()).TotalSeconds;if($ack.Token -cne $p.Token -or $ack.UiTicks -lt 3 -or $ap.Path -ine "$install\CustomerDisplay.exe" -or $ap.StartTime.ToUniversalTime().Ticks -ne $ack.ProcessStartTicks -or $elapsed -lt 14){throw 'Invalid local UI readiness ACK'};$result.Ready=@{TokenMatches=$true;ProcessId=$ap.Id;ProcessStartTicks=$ack.ProcessStartTicks;UiTicks=$ack.UiTicks;ProcessToAckSeconds=$elapsed}}
   if($Expected -eq 'rolled-back' -and -not $Interrupt){$logDeadline=[DateTime]::UtcNow.AddSeconds(5);while(-not(Test-Path -LiteralPath (Join-Path $dir.FullName 'apply.log')) -and [DateTime]::UtcNow -lt $logDeadline){Start-Sleep -Milliseconds 50}}
   if($Expected -eq 'rolled-back' -and (Test-Path -LiteralPath (Join-Path $dir.FullName 'apply.log'))){$log=Get-Content -LiteralPath (Join-Path $dir.FullName 'apply.log') -Raw;$result.FailureLogPresent=$true;$reason=$log.Replace([string]$p.Token,'[redacted-local-token]');$result.FailureReason=$reason.Substring(0,[Math]::Min(4096,$reason.Length));$result.SharingViolationRecorded=($log -match 'being used|another process|sharing|cannot access|80070020|access.*denied|used by|другим процессом|используется')}
   [IO.File]::WriteAllText((Join-Path $Proof ('case-'+$Case+'.json')),($result|ConvertTo-Json -Depth 8));return $result
  }
 };Start-Sleep -Milliseconds 100}while([DateTime]::UtcNow -lt $until);throw ('Update observation timed out: '+$Case)
}
try{
 # Prepare the genuine released82 runtime from exact source, then overwrite its five appfiles with immutable released CI bytes.
 $baselineZip=Join-Path $assets 'released82.zip'
 if((Hash $baselineZip) -cne 'cd825a2d00f86937c48212f830cd899ad37c91007628dc0d54d2f0df1ed712dc'){throw 'Released82 immutable ZIP changed'}
 if((Hash (Join-Path $assets 'outbox.db')) -cne '4120b74447060f5daea6c12fc66ee497bd89bec5d30a0a092bda85df8131b354'){throw 'Synthetic SQLite seed changed'}
 $source82=Join-Path $Bench 'source82';$null=New-Item -ItemType Directory -Path $source82;$tar82=Join-Path $Bench 'source82.tar'
 & git cat-file -e 'fbb4545e32c9d1c3030ca856c1c0f02e754ca4a0:assets/fonts/Manrope/Manrope-Variable.ttf';if($LASTEXITCODE -ne 0){throw 'Exact82 required font Git object missing'}
 & git archive --format=tar --output $tar82 fbb4545e32c9d1c3030ca856c1c0f02e754ca4a0 -- App Models assets/fonts/Manrope;if($LASTEXITCODE -ne 0){throw 'Exact82 Git source missing'}
 & tar.exe -xf $tar82 -C $source82;if($LASTEXITCODE -ne 0){throw 'Exact82 source extraction failed'}
 if(-not(Test-Path -LiteralPath (Join-Path $source82 'assets/fonts/Manrope/Manrope-Variable.ttf') -PathType Leaf)){throw 'Scoped82 source archive lacks required exact-commit Manrope font'}
 & dotnet publish (Join-Path $source82 'App/CustomerDisplay.csproj') -c Release -r win-x64 --self-contained true -p:EnableWindowsTargeting=true -p:Version=1.0.82 -p:RuntimeFrameworkVersion=10.0.14 -p:ContinuousIntegrationBuild=true -p:SourceRevisionId=fbb4545e32c9d1c3030ca856c1c0f02e754ca4a0 -p:DebugType=None -p:DebugSymbols=false -o $install
 if($LASTEXITCODE -ne 0){throw 'Full baseline82 runtime preparation failed'}
 $released82=Join-Path $Bench 'released82-files';Expand-Archive -LiteralPath $baselineZip -DestinationPath $released82
 foreach($n in $names){Copy-Item -LiteralPath (Join-Path $released82 $n) -Destination (Join-Path $install $n) -Force}
 if((Hash "$install\CustomerDisplay.dll") -cne 'fba46e258dbcae1de91f62b7bd79b544414020225be3570f9c0f256faf5f1ff8'){throw 'Genuine baseline82 identity mismatch'}
 Copy-Item -LiteralPath (Join-Path $assets 'outbox.db') -Destination "$root\data\outbox.db"
 # Fresh fake signing key inside this temporary runner only; never exported/uploaded.
 $openssl=(Get-Command openssl.exe -ErrorAction SilentlyContinue|Select-Object -First 1).Source
 if(-not $openssl){$openssl=Join-Path $env:ProgramFiles 'Git\usr\bin\openssl.exe'}
 if(-not(Test-Path -LiteralPath $openssl)){throw 'Existing runner OpenSSL is required; do not install software'}
 & $openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$root\fresh-test-signing-private.pem";if($LASTEXITCODE -ne 0){throw 'Test key generation failed'}
 & $openssl pkey -in "$root\fresh-test-signing-private.pem" -pubout -outform DER -out "$root\test-public.der";if($LASTEXITCODE -ne 0){throw 'Test public key extraction failed'}
 $config=[ordered]@{Enabled=$true;BackendBaseUrl='http://127.0.0.1:18761';BackendFallbackBaseUrls=@();DeviceKey='synthetic-pr160-not-production';DeviceId='synthetic-pr160-device';PharmacyId='synthetic-pharmacy';PharmacistId='synthetic-pharmacist';UpdateManifestPublicKeySpki=[Convert]::ToBase64String([IO.File]::ReadAllBytes("$root\test-public.der"));UpdateEnabled=$true;UpdatePollSec=30;StaffAuthenticationEnabled=$false;StandardNDbEnabled=$false;ReceiptCaptureEnabled=$false;FulfillmentEnabled=$false;ScreenMode='dev';VideoEnabled=$false;PlaylistPollSec=0;OutboxDbPath="$root\data\outbox.db";OutboxFlushSec=3600;AppLogPath="$root\evidence\app.log";HeartbeatPath="$root\evidence\heartbeat.txt";HeartbeatSec=3;FulfillmentCredentialPath="$root\data\never-read.dat";FulfillmentCachePath="$root\data\fulfillment.json";ReceiptCaptureDir="$root\data\receipts";FiscalReceiptInboxDir="$root\data\fiscal";MediaCacheDir="$root\data\media";StandardNLogPaths=@("$root\data\zkassa.log");StandardNDbPath="$root\data\never-open.fdb"}
 [IO.File]::WriteAllText("$root\data\posm.json",($config|ConvertTo-Json -Depth 5));[IO.File]::WriteAllText("$root\data\zkassa.log",'')
 $script:baseline=@{ConfigSha256=(Hash "$root\data\posm.json");OutboxSha256=(Hash "$root\data\outbox.db")};$summary.Baseline=$script:baseline;$summary.BaselineSourceCommit='fbb4545e32c9d1c3030ca856c1c0f02e754ca4a0';$summary.BaselineZipSha256=(Hash $baselineZip);$summary.BaselineDllSha256=(Hash "$install\CustomerDisplay.dll");$summary.BaselineScopedSourceArchiveSha256=(Hash $tar82)
 $mainZip=Join-Path $Bench ('main-artifact/epharm-posm-1.0.84-'+$c.commit.Substring(0,12)+'-compat.zip');$mainProv=Join-Path $Bench 'main-artifact/provenance.json';$labProv=Get-Content -LiteralPath (Join-Path $LabArtifact 'provenance.json') -Raw|ConvertFrom-Json;$labZip=Join-Path $LabArtifact $labProv.archive
 $fixtureInputs=[ordered]@{main=@{version='1.0.84';commit=$c.commit;ciRun=$c.ciRun;artifactId=$c.artifactId;zip=$mainZip;sha256=$c.sha256;dllSha256=$c.dllSha256;provenance=$mainProv};lab=@{version='9.99.1';commit=$c.commit;zip=$labZip;sha256=$labProv.sha256;dllSha256=$labProv.dllSha256;provenance=(Join-Path $LabArtifact 'provenance.json')}}
 [IO.File]::WriteAllText("$root\inputs.json",($fixtureInputs|ConvertTo-Json -Depth 5));$env:EPHARM_RECOVERY_FIXTURE_ROOT=$root;$env:EPHARM_PILOT_OPENSSL=$openssl
 foreach($scope in @('main','lab')){& python (Join-Path $PSScriptRoot 'ci-recovery-build-cases.py') --inputs "$root\inputs.json" --scope $scope;if($LASTEXITCODE -ne 0){throw 'Private signed case preparation failed'}}
 foreach($kind in @('IPv4','IPv6')){$name='EpharmPr160Recovery-'+$nonce+'-'+$kind;$addresses=if($kind -eq 'IPv4'){@('128.0.0.0/1','0.0.0.0/2','64.0.0.0/3','96.0.0.0/4','112.0.0.0/5','120.0.0.0/6','124.0.0.0/7','126.0.0.0/8')}else{@('::/1','8000::/1')};$null=New-NetFirewallRule -Name $name -DisplayName $name -Program "$install\CustomerDisplay.exe" -Direction Outbound -Action Block -Profile Any -RemoteAddress $addresses;$rules+=$name}
 # Windows PowerShell5.1 needs real canonical profile folders; its cache must never fall into a transaction directory.
 $probeText="[IO.File]::WriteAllText('$root\evidence\profile-preflight.json',(@{PowerShellVersion=[string]`$PSVersionTable.PSVersion;LocalAppData=[Environment]::GetFolderPath('LocalApplicationData');CachePath=`$env:PSModuleAnalysisCachePath}|ConvertTo-Json))"
 $probeArgs='-NoLogo -NoProfile -NonInteractive -EncodedCommand '+[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($probeText))
 $profileProbe=Child $psExe $probeArgs
 if(-not $profileProbe.WaitForExit(30000)){$profileProbe.Kill();$null=$profileProbe.WaitForExit(5000);throw 'Owned profile probe exceeded its deadline'}
 if($profileProbe.ExitCode -ne 0 -or -not(Test-Path -LiteralPath "$root\evidence\profile-preflight.json")){throw 'Actual Windows PowerShell profile preflight failed'}
 $profileProof=Get-Content -LiteralPath "$root\evidence\profile-preflight.json" -Raw|ConvertFrom-Json
 if(-not [IO.Path]::IsPathRooted([string]$profileProof.LocalAppData) -or $profileProof.CachePath -cne "$root\profile\AppData\Local\Microsoft\Windows\PowerShell\ModuleAnalysisCache"){throw 'PowerShell cache isolation was not proven'}
 $summary.ProfilePreflight=$profileProof;Emit 'profile-preflight' $profileProof
 $null=Assert-Preserved $baselineZip '1.0.82.0';No-Release;Start-Mock
 $sha=Activate 'main84';$null=Start-App;$r=Observe 'main84' $sha 'committed';$summary.Cases+=,$r;$null=Assert-Alive '1.0.84.0';Stop-OwnedApp;$null=Assert-Preserved $mainZip '1.0.84.0';No-Release
 foreach($case in @('lab-bad-exe','lab-locked')){
  $null=Start-App;$null=Assert-Alive '1.0.84.0';$lock=$null
  try{if($case -eq 'lab-locked'){$lock=[IO.File]::Open("$install\CustomerDisplay.dll",[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read);Emit 'real-ntfs-lock-held' @{Path='install/CustomerDisplay.dll';Share='Read only; denies write/delete';OwnerProcessId=$PID}}
   $sha=Activate $case;$r=Observe $case $sha 'rolled-back';if($case -eq 'lab-locked' -and -not $r.SharingViolationRecorded){throw 'Expected actual NTFS sharing failure was not recorded'};$summary.Cases+=,$r
  }finally{if($lock){$lock.Dispose();Emit 'real-ntfs-lock-released' @{OwnerProcessId=$PID}}}
  No-Release;$null=Assert-Alive '1.0.84.0';Stop-OwnedApp;$null=Assert-Preserved $mainZip '1.0.84.0'
 }
 $null=Start-App;$null=Assert-Alive '1.0.84.0';$sha=Activate 'lab-healthy';$r=Observe 'lab-healthy' $sha 'committed' -Offline;$summary.Cases+=,$r;$null=Assert-Alive '9.99.1.0';Stop-OwnedApp;$null=Assert-Preserved $labZip '9.99.1.0'
 # Manual reset only restores the test starting point; automatic rollback evidence comes from the other cases.
 foreach($n in $names){Copy-Item -LiteralPath (Join-Path $Bench ('main-bridge/'+$n)) -Destination (Join-Path $install $n) -Force};Emit 'manual-test-fixture-reset' @{From='9.99.1';To='1.0.84';IsUpdateOrRollbackEvidence=$false};$null=Assert-Preserved $mainZip '1.0.84.0'
 No-Release;Start-Mock;$null=Start-App;$null=Assert-Alive '1.0.84.0';$sha=Activate 'lab-interrupted';$r=Observe 'lab-interrupted' $sha 'rolled-back' -Interrupt;$summary.Cases+=,$r;No-Release;$null=Assert-Alive '1.0.84.0';Stop-OwnedApp;$null=Assert-Preserved $mainZip '1.0.84.0';Stop-Mock
 # Actual source watchdog, normal one-minute timer, owned InteractiveToken app/task only. No manual watchdog run.
 Copy-Item -LiteralPath (Join-Path $assets 'launch-interactive.ps1') -Destination "$root\launch-interactive.ps1"
 Copy-Item -LiteralPath (Join-Path $Bench 'source/App/scripts/watchdog.ps1') -Destination "$root\watchdog.ps1"
 $service=New-Object -ComObject Schedule.Service;$service.Connect();$folder=$service.GetFolder('\');$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
 $appTask='EpharmPr160RecoveryApp-'+$nonce;$watchTask='EpharmPr160RecoveryWatch-'+$nonce
 foreach($taskName in @($appTask,$watchTask)){try{$null=$folder.GetTask($taskName);throw 'Owned task name already exists'}catch{if($_.Exception.Message -eq 'Owned task name already exists'){throw}}}
 $def=$service.NewTask(0);$def.Principal.UserId=$sid;$def.Principal.LogonType=3;$def.Principal.RunLevel=1;$def.Settings.Enabled=$true;$def.Settings.AllowDemandStart=$true;$def.Settings.ExecutionTimeLimit='PT0S';$a=$def.Actions.Create(0);$a.Path=$psExe;$a.Arguments="-NoLogo -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File $root\launch-interactive.ps1";$a.WorkingDirectory=$root;$null=$folder.RegisterTaskDefinition($appTask,$def,6,$sid,$null,3,$null);$tasks+=$appTask
 $wd=$service.NewTask(0);$wd.Principal.UserId=$sid;$wd.Principal.LogonType=3;$wd.Principal.RunLevel=1;$wd.Settings.Enabled=$true;$wd.Settings.AllowDemandStart=$true;$wd.Settings.ExecutionTimeLimit='PT5M';$wa=$wd.Actions.Create(0);$wa.Path=$psExe;$wa.Arguments="-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File $root\watchdog.ps1 -ExePath $install\CustomerDisplay.exe -ConfigPath $root\data\posm.json -ScreenMode dev -AppLogPath $root\evidence\app.log -HeartbeatPath $root\evidence\heartbeat.txt -TaskName $appTask -LogPath $root\evidence\watchdog.log";$wa.WorkingDirectory=$root;$tr=$wd.Triggers.Create(1);$tr.Enabled=$true;$tr.StartBoundary=(Get-Date).AddMinutes(1).ToString('s');$tr.Repetition.Interval='PT1M';$tr.Repetition.Duration='P1D';$tr.Repetition.StopAtDurationEnd=$false;$null=$folder.RegisterTaskDefinition($watchTask,$wd,6,$sid,$null,3,$null);$tasks+=$watchTask
 $null=Start-App;$old=Assert-Alive '1.0.84.0';$oldId=$old.Id;$oldTicks=$old.StartTime.ToUniversalTime().Ticks;Stop-OwnedApp -Force;Emit 'controlled-own-app-termination' @{Pid=$oldId;StartTicks=$oldTicks;BackendOffline=$true;RecoveryTrigger='Normal one-minute watchdog timer; no manual task run';OsRebootPerformed=$false}
 $deadline=[DateTime]::UtcNow.AddSeconds(150);do{$alive=@(OwnApps);if($alive.Count -eq 1 -and $alive[0].Id -ne $oldId -and $alive[0].StartTime.ToUniversalTime().Ticks -gt $oldTicks){break};Start-Sleep -Milliseconds 500}while([DateTime]::UtcNow -lt $deadline)
 $new=Assert-Alive '1.0.84.0';if($new.Id -eq $oldId -or $new.StartTime.ToUniversalTime().Ticks -le $oldTicks){throw 'Watchdog did not recover a genuinely new app process'}
 $summary.Watchdog=@{Status='PASS';OldPid=$oldId;OldStartTicks=$oldTicks;NewPid=$new.Id;NewStartTicks=$new.StartTime.ToUniversalTime().Ticks;NewSession=$new.SessionId;WindowHandle=$script:lastAppWindow.Handle;WindowTitle=$script:lastAppWindow.Title;WindowBounds=@($script:lastAppWindow.Left,$script:lastAppWindow.Top,$script:lastAppWindow.Right,$script:lastAppWindow.Bottom);Responding=$script:lastAppWindow.Responding;HeartbeatUtc=(Get-Item -LiteralPath "$root\evidence\heartbeat.txt").LastWriteTimeUtc.ToString('o');BackendOffline=$true;ManualWatchdogTaskRun=$false;TimerInterval='PT1M';OsRebootPerformed=$false}
 foreach($taskName in $tasks){$task=$folder.GetTask($taskName);$actionDefinition=$task.Definition;for($i=1;$i -le $actionDefinition.Actions.Count;$i++){$ownedAction=$actionDefinition.Actions.Item($i);if($ownedAction.Path -ine $psExe -or $ownedAction.Arguments -notlike ('*'+$root+'*')){throw 'Owned task action changed before quiescence'}};$task.Enabled=$false;try{$task.Stop(0)}catch{}};Stop-OwnedApp;$summary.FinalPreservation=Assert-Preserved $mainZip '1.0.84.0'
 & python -c "import sqlite3; c=sqlite3.connect(r'$root\data\outbox.db'); assert c.execute('pragma integrity_check').fetchone()[0]=='ok'; assert c.execute('select count(*) from outbox').fetchone()[0]==1; print('Real SQLite integrity_check=ok; synthetic row preserved')";if($LASTEXITCODE -ne 0){throw 'Real SQLite integrity check failed'}
 $summary.SqliteIntegrity='ok';$summary.AuthRecoveryEvidence='Exact-main native50 Windows CI; this fixture proves installed-process startup/update only';$summary.Status='passed';$summary.CompletedUtc=[DateTime]::UtcNow.ToString('o')
}catch{$summary.Error=@{Type=$_.Exception.GetType().Name;Message=$_.Exception.Message};throw}finally{
 $cleanup=@();try{Stop-Mock}catch{$cleanup+=$_.Exception.Message}
 foreach($taskName in $tasks){try{if($taskName -notmatch ('^EpharmPr160Recovery(App|Watch)-'+$nonce+'$')){throw 'Unknown task cleanup'};$task=$folder.GetTask($taskName);$actionDefinition=$task.Definition;for($i=1;$i -le $actionDefinition.Actions.Count;$i++){$ownedAction=$actionDefinition.Actions.Item($i);if($ownedAction.Path -ine $psExe -or $ownedAction.Arguments -notlike ('*'+$root+'*')){throw 'Task action changed'}};$task.Enabled=$false;try{$task.Stop(0)}catch{};$folder.DeleteTask($taskName,0)}catch{$cleanup+=$_.Exception.Message}}
 try{Stop-OwnedApp -Force}catch{$cleanup+=$_.Exception.Message}
 foreach($h in $helperIdentities){try{$p=Get-Process -Id $h.Id -ErrorAction SilentlyContinue;if($p -and $p.StartTime.ToUniversalTime().Ticks -eq $h.Ticks -and $p.Path -ieq $psExe){$p.Kill();$null=$p.WaitForExit(5000)}}catch{$cleanup+=$_.Exception.Message}}
 foreach($rule in $rules){try{$rr=Get-NetFirewallRule -Name $rule -ErrorAction Stop;if(($rr|Get-NetFirewallApplicationFilter).Program -ine "$install\CustomerDisplay.exe"){throw 'Firewall owner changed'};Remove-NetFirewallRule -Name $rule}catch{$cleanup+=$_.Exception.Message}}
 $summary.CleanupErrors=$cleanup;[IO.File]::WriteAllText((Join-Path $Proof 'recovery-proof.json'),($summary|ConvertTo-Json -Depth 12));if($cleanup.Count){throw ('Owned recovery cleanup failed: '+($cleanup -join '; '))}
}
