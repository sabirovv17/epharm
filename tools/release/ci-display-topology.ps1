#requires -version 5.1
<#
Ephemeral hosted Windows x64 ONLY. Run with powershell.exe -NoProfile -STA -File.
Setup leaves its owned device for the app pilot. Always call Cleanup with the SAME Output.
Assert repeats fresh-process Screen enumeration and real GDI sentinel capture, without setup.
No downloads, vendor installers, certificate imports, reboot or security/session changes.
Only Amyuni's pinned enableidd 1 command activates the already-installed owned device.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Output,
    [string]$DriverRoot,
    [ValidateSet('Setup','Assert','Cleanup')][string]$Mode = 'Setup'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Need([bool]$ok, [string]$code) { if (-not $ok) { throw $code } }
Need ($env:OS -eq 'Windows_NT' -and [Environment]::Is64BitProcess) 'WINDOWS_X64_REQUIRED'
Need ($env:GITHUB_ACTIONS -eq 'true' -and $env:RUNNER_OS -eq 'Windows') 'EPHEMERAL_GITHUB_WINDOWS_RUNNER_REQUIRED'
Need ([Threading.Thread]::CurrentThread.ApartmentState -eq 'STA') 'RUN_WITH_POWERSHELL_STA'
Need ([IO.Path]::IsPathRooted($Output)) 'ABSOLUTE_OUTPUT_REQUIRED'
$Output = [IO.Path]::GetFullPath($Output).TrimEnd('\')
Need ($Output.Length -gt 10 -and $Output -ne [IO.Path]::GetPathRoot($Output).TrimEnd('\')) 'UNSAFE_OUTPUT'
function NoLinks([string]$path) {
    $p = $path
    while ($p) {
        if (Test-Path -LiteralPath $p) { Need (((Get-Item -LiteralPath $p -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) 'REPARSE_PATH_REFUSED' }
        $next = [IO.Path]::GetDirectoryName($p)
        if ($next -eq $p) { break }; $p = $next
    }
}
NoLinks $Output
$statePath = Join-Path $Output 'owned-state.json'
if ($Mode -eq 'Cleanup' -and -not (Test-Path -LiteralPath $statePath)) { Write-Output 'CI_DISPLAY_CLEANUP_NO_OWNED_STATE'; return }
$sourcePath = Join-Path $PSScriptRoot 'ci-display-topology-native.cs'
$exe = Join-Path $Output 'ci-display-topology-native.exe'
function Save-State { [IO.File]::WriteAllText($statePath, ($script:state | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false))) }
function Own-Process([string]$file, [string]$arguments, [int]$seconds, [bool]$isolated = $false) {
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName=$file; $start.Arguments=$arguments; $start.UseShellExecute=$false
    $start.CreateNoWindow=$true; $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
    if ($isolated) {
        $start.EnvironmentVariables.Clear()
        foreach ($name in @('SystemRoot','WINDIR','SystemDrive','ComSpec','PATH','TEMP','TMP','USERPROFILE','APPDATA','LOCALAPPDATA')) {
            $value=[Environment]::GetEnvironmentVariable($name)
            if ($value) { $start.EnvironmentVariables[$name]=$value }
        }
        $start.WorkingDirectory=[IO.Path]::GetDirectoryName($file)
    }
    $p = [Diagnostics.Process]::Start($start)
    try {
        # Both streams are drained asynchronously, including failed native tools.
        $outTask=$p.StandardOutput.ReadToEndAsync(); $errTask=$p.StandardError.ReadToEndAsync()
        if (-not $p.WaitForExit($seconds*1000)) { $p.Kill(); $p.WaitForExit(); throw 'OWNED_PROCESS_DEADLINE' }
        $text=$outTask.Result; $errorText=$errTask.Result.Trim()
        Need ($text.Length -lt 1048576) 'OWNED_PROCESS_OUTPUT_LIMIT'
        $diagnostic=if ($errorText -match '^CI_DISPLAY_NATIVE_FAILURE type=[A-Za-z0-9_.]+ code=-?[0-9]+$') { $errorText } else { $null }
        return [pscustomobject]@{ Code=$p.ExitCode; Text=$text; ErrorText=$errorText; Diagnostic=$diagnostic }
    } finally { $p.Dispose() }
}
function Security-State {
    $boot=Own-Process (Join-Path $env:windir 'System32\bcdedit.exe') '/enum {current}' 10
    Need ($boot.Code -eq 0) 'BOOT_POLICY_UNREADABLE'
    $test=$boot.Text -match '(?im)^\s*testsigning\s+(yes|on|true|1)\s*$'
    $bypass=$boot.Text -match '(?im)^\s*nointegritychecks\s+(yes|on|true|1)\s*$'
    # Hosted runner boot policy is provided by GitHub, not this pilot. Keep it
    # unchanged and disclose any pre-existing test mode. This gate tests display
    # topology/rendering, never qualifies the fixture driver for production.
    $secure='Unsupported'
    try { $secure=([bool](Confirm-SecureBootUEFI -ErrorAction Stop)).ToString() } catch { }
    $hvci=$null
    $key='HKLM:\SYSTEM\CurrentControlSet\Control\DeviceGuard\Scenarios\HypervisorEnforcedCodeIntegrity'
    if (Test-Path $key) { $hvci=(Get-Item $key).GetValue('Enabled',$null) }
    return [pscustomobject][ordered]@{ TestSigning=$test; NoIntegrityChecks=$bypass; SecureBoot=$secure; HvciEnabled=$hvci; ProductionDriverQualification=$false }
}
function Fresh-Screens {
    $r=Own-Process $exe 'screens' 10
    Need ($r.Code -eq 0) 'FRESH_SCREEN_ENUMERATION_FAILED'
    return ($r.Text | ConvertFrom-Json)
}
function Sentinels($screens, [string]$prefix) {
    Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase
    $i=0
    foreach ($screen in $screens) {
        Need ($screen.width -ge 320 -and $screen.height -ge 240) 'SCREEN_TOO_SMALL'
        $w=New-Object Windows.Window
        $w.WindowStyle='None'; $w.ResizeMode='NoResize'; $w.ShowInTaskbar=$false; $w.Topmost=$true
        $w.WindowStartupLocation='Manual'; $w.Width=240; $w.Height=120
        $w.Left=$screen.x+40; $w.Top=$screen.y+40
        $grid=New-Object Windows.Controls.Grid
        foreach ($color in @('#E11D48','#22C55E','#2563EB')) {
            $col=New-Object Windows.Controls.ColumnDefinition; $null=$grid.ColumnDefinitions.Add($col)
            $cell=New-Object Windows.Controls.Border; $cell.Background=$color
            [Windows.Controls.Grid]::SetColumn($cell,$grid.Children.Count); $null=$grid.Children.Add($cell)
        }
        $w.Content=$grid
        try {
            $w.Show(); $w.UpdateLayout()
            $frame=New-Object Windows.Threading.DispatcherFrame
            $timer=New-Object Windows.Threading.DispatcherTimer
            $timer.Interval=[TimeSpan]::FromMilliseconds(400)
            $timer.Add_Tick({ $timer.Stop(); $frame.Continue=$false })
            $timer.Start(); [Windows.Threading.Dispatcher]::PushFrame($frame)
            $hwnd=(New-Object Windows.Interop.WindowInteropHelper($w)).Handle
            $path=Join-Path $Output ($prefix+'-'+$i+'.png')
            Need ([EpharmCiDisplay.Native]::Sentinel($hwnd,$path,$screen.x,$screen.y,$screen.width,$screen.height)) 'GDI_VISIBLE_COLOR_SENTINEL_FAILED'
        } finally { $w.Close() }
        $i++
    }
}
function Confirm-Topology($screens) {
    Need ($screens.Count -ge 2) 'TWO_ACTIVE_WINDOWS_SCREENS_REQUIRED'
    Need (@($screens | Where-Object primary).Count -eq 1) 'ONE_PRIMARY_REQUIRED'
    for ($i=0;$i -lt $screens.Count;$i++) {
        for ($j=$i+1;$j -lt $screens.Count;$j++) {
            $a=$screens[$i]; $b=$screens[$j]
            $overlap=([Math]::Min($a.x+$a.width,$b.x+$b.width)-[Math]::Max($a.x,$b.x) -gt 0) -and
                ([Math]::Min($a.y+$a.height,$b.y+$b.height)-[Math]::Max($a.y,$b.y) -gt 0)
            Need (-not $overlap) 'EXTENDED_NONOVERLAPPING_BOUNDS_REQUIRED'
        }
    }
}
function Cleanup-Owned {
    if ($script:state.InstanceId) {
        $d=Get-PnpDevice -InstanceId $script:state.InstanceId -ErrorAction SilentlyContinue
        if ($d) { [EpharmCiDisplay.Native]::Remove($script:state.InstanceId,$script:state.Owner) }
        Need (-not (Get-PnpDevice -InstanceId $script:state.InstanceId -PresentOnly -ErrorAction SilentlyContinue)) 'OWNED_DEVICE_REMOVE_FAILED'
        $script:state.InstanceId=$null
    }
    $script:state.CleanupComplete=$true; Save-State
}
if ($Mode -eq 'Setup') {
    if (Test-Path -LiteralPath $Output) { Need (@(Get-ChildItem -LiteralPath $Output -Force).Count -eq 0) 'SETUP_OUTPUT_MUST_BE_EMPTY' }
    else { $null=New-Item -ItemType Directory -Path $Output }
    $script:state=[pscustomobject][ordered]@{ Owner=('EpharmCiDisplay-'+[Guid]::NewGuid().ToString('N')); InstanceId=$null; CleanupComplete=$false; Security=$null; Session=$null; HelperSha256=$null; DriverName='Amyuni USB Mobile Monitor'; DriverVersion='2.0.0.1'; DriverArchiveSha256='629b51e9944762bae73948171c65d09a79595cf4c771a82ebc003fbba5b24f51'; DriverVerified=$false; SignatureSubject=$null; MembershipChecked=$false; NativeInstallDiagnostic=$null; NativeInstallExitCode=$null; PnpStatus=$null; DeviceProblemCode=$null; EnableToolSignatureStatus=$null; EnableToolUnsigned=$false; EnableExitCode=$null; EnableErrorCodes=@(); EnableNoActiveInterfaces=$false; EnableReportedError=$false; ExtendResult=$null; AdvertisementPromptAccepted=$true; ProductionDriverQualification=$false; FailureType=$null; FailureCode=$null; Status='FAIL'; Screens=@(); Stage='preflight' }
    $null=Add-Type -TypeDefinition ([IO.File]::ReadAllText($sourcePath)) -ReferencedAssemblies System.dll,System.Core.dll,System.Drawing.dll,System.Windows.Forms.dll -OutputAssembly $exe -OutputType ConsoleApplication -PassThru
    $null=[Reflection.Assembly]::LoadFrom($exe)
    $script:state.HelperSha256=(Get-FileHash $exe -Algorithm SHA256).Hash; Save-State
} else {
    $script:state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    Need ($script:state.Owner -match '^EpharmCiDisplay-[0-9a-f]{32}$') 'INVALID_OWNER_STATE'
    Need ((Get-FileHash $exe -Algorithm SHA256).Hash -ceq $script:state.HelperSha256) 'OWNED_HELPER_CHANGED'
    $null=[Reflection.Assembly]::LoadFrom($exe)
}
try {
    if ($Mode -eq 'Cleanup') { Cleanup-Owned; Write-Output 'CI_DISPLAY_CLEANUP_COMPLETE'; return }
    $session=[pscustomobject][ordered]@{ UserInteractive=[Environment]::UserInteractive; SessionId=[Diagnostics.Process]::GetCurrentProcess().SessionId; Station=[EpharmCiDisplay.Native]::Station(); Desktop=[EpharmCiDisplay.Native]::Desktop(); ActiveConsoleSessionId=[EpharmCiDisplay.Native]::WTSGetActiveConsoleSessionId() }
    Need ($session.UserInteractive -and $session.SessionId -gt 0) 'INTERACTIVE_SESSION_REQUIRED'
    Need ($session.Station -eq 'WinSta0' -and $session.Desktop -eq 'Default') 'INTERACTIVE_DEFAULT_DESKTOP_REQUIRED'
    # A legitimate interactive RDP session can differ from WTS's active console session.
    $script:state.Session=$session; Save-State
    $security=Security-State
    if ($Mode -eq 'Setup') { $script:state.Security=$security } else { Need (($security | ConvertTo-Json -Compress) -ceq ($script:state.Security | ConvertTo-Json -Compress)) 'SECURITY_STATE_CHANGED' }
    $screens=@(Fresh-Screens)
    Need ($screens.Count -gt 0) 'BASELINE_SCREEN_REQUIRED'
    Sentinels $screens 'preflight-sentinel'
    if ($Mode -eq 'Setup' -and $screens.Count -lt 2) {
        $script:state.Stage='verify-driver'; Save-State
        Need (-not [string]::IsNullOrWhiteSpace($DriverRoot) -and [IO.Path]::IsPathRooted($DriverRoot)) 'ABSOLUTE_DRIVER_ROOT_REQUIRED'
        $DriverRoot=[IO.Path]::GetFullPath($DriverRoot); NoLinks $DriverRoot
        $pins=@{
            'usbmmIdd.inf'='a8750ca15a86742f3012886c9932bb974158cd2d9779cf891c730d976a47726a'
            'x64\usbmmIdd.dll'='dc135d675127113915a7e5aa9fe57c84edad6be41d0890b265ef124ab26ea9e3'
            'usbmmidd.cat'='470149c4cf9970ba59070aa7c9409c9f63a15727de99bab53e7e51f55310779f'
            'deviceinstaller64.exe'='e9baa02cdae921acf0aae4d8e8c29a4cdf4057ab61f9c60862b7cc439e2753f7'
        }
        foreach ($name in $pins.Keys) { $p=Join-Path $DriverRoot $name; NoLinks $p; Need ((Get-FileHash $p -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $pins[$name]) 'PINNED_DRIVER_FILE_MISMATCH' }
        $inf=Join-Path $DriverRoot 'usbmmIdd.inf'; $cat=Join-Path $DriverRoot 'usbmmidd.cat'
        $signature=Get-AuthenticodeSignature -LiteralPath $cat
        Need ($signature.Status -eq 'Valid' -and $signature.SignerCertificate.Subject -match 'CN=Microsoft Windows Hardware Compatibility Publisher(?:,|$)' -and $signature.SignerCertificate.Thumbprint -ceq '984E03B613E8C2AE9C692F0DB2C031BF3EE3A0FA') 'NORMAL_AUTHENTICODE_TRUST_REQUIRED'
        $script:state.SignatureSubject=$signature.SignerCertificate.Subject
        $signTool=Get-ChildItem -Path "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
        Need ($null -ne $signTool) 'CATALOG_MEMBERSHIP_VERIFIER_REQUIRED'
        if ($signTool) {
            foreach ($name in @('usbmmIdd.inf','x64\usbmmIdd.dll')) { $null=& $signTool.FullName verify /pa /c $cat (Join-Path $DriverRoot $name) 2>&1; Need ($LASTEXITCODE -eq 0) 'CATALOG_MEMBERSHIP_VERIFICATION_FAILED' }
            $script:state.MembershipChecked=$true
        }
        $enableTool=Join-Path $DriverRoot 'deviceinstaller64.exe'
        $enableSignature=Get-AuthenticodeSignature -LiteralPath $enableTool
        $script:state.EnableToolSignatureStatus=$enableSignature.Status.ToString()
        $script:state.EnableToolUnsigned=($enableSignature.Status -eq 'NotSigned'); Save-State
        Need ($enableSignature.Status -eq 'Valid' -or $enableSignature.Status -eq 'NotSigned') 'ENABLE_TOOL_SIGNATURE_INVALID'
        # Vendor's exact unsigned activation tool is hash-pinned, never used to install/remove or change trust.
        $existing=@(Get-CimInstance Win32_PnPEntity | Where-Object { @($_.HardwareID) -icontains 'usbmmIdd' })
        Need ($existing.Count -eq 0) 'EXISTING_AMYUNI_DEVICE_REFUSED'
        $script:state.DriverVerified=$true; $script:state.Stage='create-owned-device'; Save-State
        $script:state.InstanceId=[EpharmCiDisplay.Native]::Create($script:state.Owner); Save-State
        $script:state.Stage='normal-driver-install'; Save-State
        $install=Own-Process $exe ('install "'+$inf+'"') 90
        $script:state.NativeInstallExitCode=$install.Code; $script:state.NativeInstallDiagnostic=$install.Diagnostic; Save-State
        Need ($install.Code -ne 3010 -and $install.Code -ne 1641) 'REBOOT_REQUIRED_STOP'
        Need ($install.Code -eq 0) 'NORMAL_WINDOWS_DRIVER_INSTALL_REJECTED'
        $script:state.Stage='owned-device-health'; Save-State
        [EpharmCiDisplay.Native]::Mark($script:state.InstanceId,$script:state.Owner)
        $device=Get-PnpDevice -InstanceId $script:state.InstanceId
        $problem=(Get-PnpDeviceProperty -InstanceId $script:state.InstanceId -KeyName DEVPKEY_Device_ProblemCode).Data
        $script:state.PnpStatus=$device.Status.ToString(); $script:state.DeviceProblemCode=$problem; Save-State
        Need ($device.Status -eq 'OK' -and $problem -eq 0) 'OWNED_DEVICE_NOT_HEALTHY'
        $script:state.Stage='activate-owned-display'; Save-State
        $enabled=Own-Process $enableTool 'enableidd 1' 30 $true
        $script:state.EnableExitCode=$enabled.Code
        $enableText=$enabled.Text+' '+$enabled.ErrorText
        $script:state.EnableErrorCodes=@([regex]::Matches($enableText,'(?i)\b0x[0-9a-f]{1,8}\b') | ForEach-Object Value | Select-Object -Unique)
        $script:state.EnableNoActiveInterfaces=($enableText -match '(?i)no active device interfaces')
        $script:state.EnableReportedError=($enableText -match '(?im)^\s*error(?:\s|:)')
        Save-State
        Need ($enabled.Code -ne 3010 -and $enabled.Code -ne 1641) 'REBOOT_REQUIRED_STOP'
        Need ($enabled.Code -eq 0 -and -not $script:state.EnableNoActiveInterfaces -and -not $script:state.EnableReportedError) 'AMYUNI_DISPLAY_ACTIVATION_FAILED'
        $script:state.Stage='extend-real-displays'; Save-State
        $deadline=[DateTime]::UtcNow.AddSeconds(30)
        do {
            Start-Sleep -Milliseconds 500
            $script:state.ExtendResult=[EpharmCiDisplay.Native]::Extend()
            $screens=@(Fresh-Screens)
        } while (($screens.Count -lt 2 -or $script:state.ExtendResult -ne 0) -and [DateTime]::UtcNow -lt $deadline)
        $script:state.Screens=$screens; Save-State
        Need ($script:state.ExtendResult -eq 0) 'NATIVE_EXTEND_REJECTED'
    }
    Confirm-Topology $screens
    Sentinels $screens 'extended-sentinel'
    Need (((Security-State) | ConvertTo-Json -Compress) -ceq ($script:state.Security | ConvertTo-Json -Compress)) 'SECURITY_STATE_CHANGED'
    $script:state.Screens=$screens; $script:state.Status='PASS'; $script:state.Stage='ready-for-app-pilot'; Save-State
    Write-Output ('CI_DISPLAY_TOPOLOGY_PASS count='+$screens.Count)
} catch {
    $script:state.Status='FAIL'
    $failure=$_.Exception
    while ($failure.InnerException) { $failure=$failure.InnerException }
    $script:state.FailureType=$failure.GetType().FullName
    $script:state.FailureCode=if ($failure -is [ComponentModel.Win32Exception]) { $failure.NativeErrorCode } else { $failure.HResult }
    Save-State
    try { Cleanup-Owned } catch { Write-Warning 'CI_DISPLAY_OWNED_CLEANUP_FAILED' }
    throw
}
