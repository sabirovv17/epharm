[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$CandidateConfig,
    [Parameter(Mandatory=$true)][string]$BridgeRoot,
    [Parameter(Mandatory=$true)][string]$AppRoot,
    [Parameter(Mandatory=$true)][string]$VideoPath,
    [Parameter(Mandatory=$true)][string]$Output
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# This is an installed-process acceptance fixture, never a replacement for a real scanner pilot.
$AppRoot = [IO.Path]::GetFullPath($AppRoot)
$VideoPath = [IO.Path]::GetFullPath($VideoPath)
$Output = [IO.Path]::GetFullPath($Output)
$tempRoot = if ($env:RUNNER_TEMP) { [IO.Path]::GetFullPath($env:RUNNER_TEMP) } else { [IO.Path]::GetTempPath() }
if (-not $Output.StartsWith($tempRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must be a dedicated directory below RUNNER_TEMP.' }
if (Test-Path -LiteralPath $Output) {
    if (@(Get-ChildItem -LiteralPath $Output -Force).Count) { throw 'Refuse to overwrite an existing nonempty output directory.' }
} else { $null = New-Item -ItemType Directory -Path $Output }
$exe = Join-Path $AppRoot 'CustomerDisplay.exe'
$dll = Join-Path $AppRoot 'CustomerDisplay.dll'
$candidate=(Get-Content -LiteralPath $CandidateConfig -Raw|ConvertFrom-Json).main
if($candidate.version -cne '1.0.84' -or $candidate.commit -cnotmatch '^[a-f0-9]{40}$' -or $candidate.dllSha256 -cnotmatch '^[a-f0-9]{64}$'){throw 'Final main84 identity is required'}
if((Get-FileHash -LiteralPath $dll -Algorithm SHA256).Hash.ToLowerInvariant() -cne $candidate.dllSha256){throw 'Runtime is not the exact main84 CI application'}
$names=@('CustomerDisplay.exe','CustomerDisplay.dll','CustomerDisplay.deps.json','CustomerDisplay.runtimeconfig.json','QRCoder.dll')
foreach($name in $names){if((Get-FileHash -LiteralPath (Join-Path $AppRoot $name)).Hash -cne (Get-FileHash -LiteralPath (Join-Path $BridgeRoot $name)).Hash){throw 'Runtime application files differ from the immutable main CI bridge'}}
if ((Get-FileHash -LiteralPath $VideoPath -Algorithm SHA256).Hash -ne '6D99D36F2C2E616D968C4E6CE2D23243FB678E72BF93207EB132A9850377DB2F') { throw 'Unexpected synthetic moving-video fixture.' }
if (-not (Test-Path -LiteralPath (Join-Path $AppRoot 'libvlc\win-x64\libvlc.dll'))) { throw 'The pinned native VLC fixture is absent.' }
if (-not [Environment]::UserInteractive -or [Diagnostics.Process]::GetCurrentProcess().SessionId -eq 0) { throw 'A genuine interactive Windows session is required.' }

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type -ReferencedAssemblies @('System.dll','System.Core.dll','System.Drawing.dll') -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Collections.Concurrent;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public sealed class EpharmDisplayWindow {
    public long Handle; public int ProcessId; public string Title; public string ClassName;
    public int Left, Top, Right, Bottom; public bool Visible;
    public int Width { get { return Right - Left; } }
    public int Height { get { return Bottom - Top; } }
    public bool Recommendation { get { return Title == "\u0420\u0435\u043a\u043e\u043c\u0435\u043d\u0434\u0430\u0446\u0438\u0438 Epharm"; } }
}
public sealed class EpharmFrameDifference { public int Pixels, ChangedPixels, DistinctColors; public double ChangedRatio; }
public static class EpharmDisplayNative {
    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] struct MouseInput { public int X,Y; public uint Data,Flags,Time; public UIntPtr Extra; }
    [StructLayout(LayoutKind.Sequential)] struct KeyInput { public ushort Key,Scan; public uint Flags,Time; public UIntPtr Extra; }
    [StructLayout(LayoutKind.Explicit)] struct InputUnion { [FieldOffset(0)] public MouseInput Mouse; [FieldOffset(0)] public KeyInput Key; }
    [StructLayout(LayoutKind.Sequential)] struct Input { public uint Type; public InputUnion Value; }
    delegate bool EnumWindow(IntPtr window, IntPtr unused);
    delegate bool EnumMonitor(IntPtr monitor, IntPtr dc, ref Rect bounds, IntPtr unused);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindow callback, IntPtr unused);
    [DllImport("user32.dll")] static extern bool EnumDisplayMonitors(IntPtr dc, IntPtr clip, EnumMonitor callback, IntPtr unused);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr window);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr window, out Rect bounds);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr window, StringBuilder text, int size);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr window, StringBuilder text, int size);
    [DllImport("user32.dll")] static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
    [DllImport("user32.dll")] static extern IntPtr GetProcessWindowStation();
    [DllImport("user32.dll")] static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
    [DllImport("user32.dll")] static extern bool CloseDesktop(IntPtr desktop);
    [DllImport("user32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool GetUserObjectInformation(IntPtr handle, int index, StringBuilder text, int length, out int required);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll", SetLastError=true)] static extern uint SendInput(uint count, Input[] inputs, int size);
    public static void DpiAware() { if (SetThreadDpiAwarenessContext(new IntPtr(-4)) == IntPtr.Zero) throw new Win32Exception(); }
    static string ObjectName(IntPtr handle) { int required; var text = new StringBuilder(256); if (!GetUserObjectInformation(handle, 2, text, 512, out required)) throw new Win32Exception(); return text.ToString(); }
    public static string WindowStation() { return ObjectName(GetProcessWindowStation()); }
    public static string InputDesktop() { var h=OpenInputDesktop(0,false,1); if(h==IntPtr.Zero) throw new Win32Exception(); try { return ObjectName(h); } finally { CloseDesktop(h); } }
    public static Rect[] Monitors() { var result=new List<Rect>(); EnumMonitor fn=delegate(IntPtr m,IntPtr d,ref Rect r,IntPtr u){result.Add(r);return true;}; if(!EnumDisplayMonitors(IntPtr.Zero,IntPtr.Zero,fn,IntPtr.Zero)) throw new Win32Exception(); return result.ToArray(); }
    public static EpharmDisplayWindow[] Windows(int owner) {
        var result=new List<EpharmDisplayWindow>();
        EnumWindow fn=delegate(IntPtr h,IntPtr unused){ uint pid; GetWindowThreadProcessId(h,out pid); if(pid!=(uint)owner) return true;
            Rect r; if(!GetWindowRect(h,out r)) return true; var title=new StringBuilder(512); var cls=new StringBuilder(256);
            GetWindowText(h,title,title.Capacity);GetClassName(h,cls,cls.Capacity);
            result.Add(new EpharmDisplayWindow {Handle=h.ToInt64(),ProcessId=owner,Title=title.ToString(),ClassName=cls.ToString(),Left=r.Left,Top=r.Top,Right=r.Right,Bottom=r.Bottom,Visible=IsWindowVisible(h)}); return true;};
        if(!EnumWindows(fn,IntPtr.Zero)) throw new Win32Exception(); return result.ToArray();
    }
    public static void FocusAndPressDemo(long handle, int owner, int receiptX, int receiptY) {
        uint pid; var hwnd=new IntPtr(handle);GetWindowThreadProcessId(hwnd,out pid);if(pid!=(uint)owner) throw new InvalidOperationException("Foreign input target");
        SetForegroundWindow(hwnd);
        int left=GetSystemMetrics(76),top=GetSystemMetrics(77),width=GetSystemMetrics(78),height=GetSystemMetrics(79);
        if(width<2||height<2) throw new InvalidOperationException("Invalid desktop coordinates");
        var move=new Input {Type=0,Value=new InputUnion {Mouse=new MouseInput {X=(receiptX-left)*65535/(width-1),Y=(receiptY-top)*65535/(height-1),Flags=0xC001}}};
        var down=new Input {Type=0,Value=new InputUnion {Mouse=new MouseInput {Flags=2}}};
        var up=new Input {Type=0,Value=new InputUnion {Mouse=new MouseInput {Flags=4}}};
        var mouse=new Input[]{move,down,up}; if(SendInput(3,mouse,Marshal.SizeOf(typeof(Input)))!=3) throw new Win32Exception();
        Thread.Sleep(150);GetWindowThreadProcessId(GetForegroundWindow(),out pid);if(pid!=(uint)owner) throw new InvalidOperationException("Own app did not receive genuine foreground input");
        var keyDown=new Input {Type=1,Value=new InputUnion {Key=new KeyInput {Key=0x44}}};
        var keyUp=new Input {Type=1,Value=new InputUnion {Key=new KeyInput {Key=0x44,Flags=2}}};
        if(SendInput(2,new Input[]{keyDown,keyUp},Marshal.SizeOf(typeof(Input)))!=2) throw new Win32Exception();
    }
    public static void Capture(int x,int y,int width,int height,string path) {
        if(width<1||height<1) throw new ArgumentOutOfRangeException();
        using(var image=new Bitmap(width,height,PixelFormat.Format32bppArgb)) {
            using(var g=Graphics.FromImage(image)) { g.CopyFromScreen(x,y,0,0,new Size(width,height),CopyPixelOperation.SourceCopy); }
            image.Save(path,ImageFormat.Png);
        }
    }
    public static EpharmFrameDifference Difference(string first,string second) {
        using(var a=new Bitmap(first)) using(var b=new Bitmap(second)) {
            if(a.Size!=b.Size) throw new InvalidOperationException("Frame size changed");
            int changed=0,pixels=0; var colors=new HashSet<int>();
            for(int y=0;y<a.Height;y+=2) for(int x=0;x<a.Width;x+=2) {
                var c=a.GetPixel(x,y);var d=b.GetPixel(x,y);pixels++;
                if(Math.Abs(c.R-d.R)+Math.Abs(c.G-d.G)+Math.Abs(c.B-d.B)>30)changed++;
                colors.Add((c.R>>4)*256+(c.G>>4)*16+(c.B>>4));
            }
            return new EpharmFrameDifference {Pixels=pixels,ChangedPixels=changed,ChangedRatio=(double)changed/pixels,DistinctColors=colors.Count};
        }
    }
}
public sealed class EpharmDisplayFixtureServer : IDisposable {
    readonly HttpListener listener=new HttpListener(); readonly Thread thread; readonly byte[] video;
    public readonly ConcurrentQueue<string> Trace=new ConcurrentQueue<string>();
    public EpharmDisplayFixtureServer(string videoPath) { video=System.IO.File.ReadAllBytes(videoPath);listener.Prefixes.Add("http://127.0.0.1:18761/");listener.Start();thread=new Thread(Run);thread.IsBackground=true;thread.Start(); }
    void Run() {
        while(listener.IsListening) { HttpListenerContext c;try {c=listener.GetContext();}catch(HttpListenerException){break;}catch(ObjectDisposedException){break;}
            var r=c.Response;try {
                string path=c.Request.Url.AbsolutePath;Trace.Enqueue(DateTime.UtcNow.ToString("o")+" "+c.Request.HttpMethod+" "+path);r.KeepAlive=false;
                byte[] body; r.ContentType="application/json; charset=utf-8";
                if(c.Request.HttpMethod!="GET"&&c.Request.HttpMethod!="HEAD") {r.StatusCode=503;body=Encoding.UTF8.GetBytes("{\"syntheticOffline\":true}");}
                else if(path=="/api/posm/playlists/active") body=Encoding.UTF8.GetBytes("{\"playlistId\":\"synthetic-ci84\",\"name\":\"Synthetic display acceptance\",\"slides\":[{\"id\":\"synthetic-video84\",\"url\":\"http://127.0.0.1:18761/pilot.mp4\",\"kind\":\"video\",\"durationSec\":8,\"title\":\"SYNTHETIC CUSTOMER DISPLAY\",\"position\":0}]}");
                else if(path=="/pilot.mp4") {r.ContentType="video/mp4";body=video;}
                else if(path=="/api/posm/app/version") body=Encoding.UTF8.GetBytes("{\"current\":false,\"platform\":\"win-x64\"}");
                else {r.StatusCode=503;body=Encoding.UTF8.GetBytes("{\"syntheticOffline\":true}");}
                r.ContentLength64=body.Length;if(c.Request.HttpMethod!="HEAD")r.OutputStream.Write(body,0,body.Length);
            } catch(Exception e) {Trace.Enqueue("response-error:"+e.GetType().Name);} finally {r.Close();}
        }
    }
    public void Dispose() { listener.Stop();listener.Close();if(!thread.Join(2000))throw new InvalidOperationException("Own mock server failed to stop"); }
}
'@

[EpharmDisplayNative]::DpiAware()
$session = [Diagnostics.Process]::GetCurrentProcess().SessionId
$station = [EpharmDisplayNative]::WindowStation()
$desktop = [EpharmDisplayNative]::InputDesktop()
if ($station -ne 'WinSta0' -or $desktop -ne 'Default') { throw 'The actual interactive WinSta0/Default input desktop is required.' }
$nativeScreens = @([EpharmDisplayNative]::Monitors())
$screens = @([Windows.Forms.Screen]::AllScreens)
if ($nativeScreens.Count -lt 2 -or $screens.Count -lt 2) { throw 'Two real active Windows displays are required before rendering.' }
$primary = @($screens | Where-Object Primary)[0]
$secondary = @($screens | Where-Object { -not $_.Primary })[0]
if ($primary.Bounds.IntersectsWith($secondary.Bounds)) { throw 'Primary and secondary Windows display bounds overlap.' }
$screenProof = @($screens | ForEach-Object { [ordered]@{Device=$_.DeviceName;Primary=$_.Primary;Left=$_.Bounds.Left;Top=$_.Bounds.Top;Right=$_.Bounds.Right;Bottom=$_.Bounds.Bottom} })
foreach ($profile in @(Get-NetFirewallProfile -PolicyStore ActiveStore)) { if (-not $profile.Enabled) { throw 'Firewall profiles must be enabled before the app outbound-isolation fixture.' } }
$data = Join-Path $Output 'fixture'
$null = New-Item -ItemType Directory -Path $data
foreach ($name in @('temp','profile','appdata','localappdata','receipts','fiscal','media')) { $null = New-Item -ItemType Directory -Path (Join-Path $data $name) }
[IO.File]::WriteAllText((Join-Path $data 'zkassa.log'),'')
$configPath = Join-Path $data 'posm.json'
$config = [ordered]@{
    Enabled=$true;BackendBaseUrl='http://127.0.0.1:18761';BackendFallbackBaseUrls=@();DeviceKey='synthetic-ci-display-not-production';DeviceId='synthetic-ci-display';PharmacyId='synthetic-ci-pharmacy';PharmacistId='synthetic-ci-pharmacist'
    StaffAuthenticationEnabled=$false;StandardNDbEnabled=$false;ReceiptCaptureEnabled=$false;FulfillmentEnabled=$false;UpdateEnabled=$false
    ScreenMode='prod';VideoEnabled=$true;VlcArgs='--avcodec-hw=none --vout=wingdi --no-audio';PlaylistPollSec=10;PopupAutoCloseSec=60
    OutboxDbPath=(Join-Path $data 'outbox.db');OutboxFlushSec=3600;AppLogPath=(Join-Path $Output 'app.log');HeartbeatPath=(Join-Path $data 'heartbeat.txt');HeartbeatSec=3
    MediaCacheDir=(Join-Path $data 'media');StandardNLogPaths=@((Join-Path $data 'zkassa.log'));StandardNDbPath=(Join-Path $data 'never-open.fdb')
    ReceiptCaptureDir=(Join-Path $data 'receipts');FiscalReceiptInboxDir=(Join-Path $data 'fiscal');FulfillmentCredentialPath=(Join-Path $data 'never-read-credential.dat');FulfillmentCachePath=(Join-Path $data 'fulfillment.json')
}
[IO.File]::WriteAllText($configPath,($config|ConvertTo-Json -Depth 5))
$nonce = [Guid]::NewGuid().ToString('N')
$rules = @()
$app = $null
$server = $null
$startTicks = $null
$proof = [ordered]@{Status='failed';SourceCommit=$candidate.commit;MainVersion=$candidate.version;MainDllSha256=(Get-FileHash -LiteralPath $dll).Hash;VideoSha256=(Get-FileHash -LiteralPath $VideoPath).Hash;SessionId=$session;WindowStation=$station;InputDesktop=$desktop;Screens=$screenProof;NativeMonitorCount=$nativeScreens.Count;RealScannerTested=$false;PhysicalScreensTested=$false;BuiltInDemoOnly=$true}
function Covers($window,$bounds) { return [Math]::Abs($window.Left-$bounds.Left) -le 8 -and [Math]::Abs($window.Top-$bounds.Top) -le 8 -and [Math]::Abs($window.Right-$bounds.Right) -le 8 -and [Math]::Abs($window.Bottom-$bounds.Bottom) -le 8 }
function InBounds($window,$bounds) { return $window.Left -ge $bounds.Left -and $window.Top -ge $bounds.Top -and $window.Right -le $bounds.Right -and $window.Bottom -le $bounds.Bottom }
function Get-OwnWindows { if ($app.HasExited) { throw 'The exact application exited before display acceptance.' }; return @([EpharmDisplayNative]::Windows($app.Id)) }
try {
    foreach ($kind in @('IPv4','IPv6')) {
        $proof.FirewallRuleStage=$kind
        $name = 'EpharmCIDisplay-'+$nonce+'-'+$kind
        $addresses = if ($kind -eq 'IPv4') { @('128.0.0.0/1','0.0.0.0/2','64.0.0.0/3','96.0.0.0/4','112.0.0.0/5','120.0.0.0/6','124.0.0.0/7','126.0.0.0/8') } else { @('::/1','8000::/1') }
        $null = New-NetFirewallRule -Name $name -DisplayName $name -Group ('EpharmCIDisplay-'+$nonce) -Program $exe -Direction Outbound -Action Block -Profile Any -RemoteAddress $addresses
        $rules += $name
    }
    $server = [EpharmDisplayFixtureServer]::new($VideoPath)
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName=$exe;$psi.WorkingDirectory=$AppRoot;$psi.UseShellExecute=$false;$psi.EnvironmentVariables.Clear()
    $environment = @{
        SystemRoot=$env:SystemRoot;WINDIR=$env:SystemRoot;SystemDrive=$env:SystemDrive;ComSpec=(Join-Path $env:SystemRoot 'System32\cmd.exe');PATH=((Join-Path $env:SystemRoot 'System32')+';'+$env:SystemRoot)
        TEMP=(Join-Path $data 'temp');TMP=(Join-Path $data 'temp');USERPROFILE=(Join-Path $data 'profile');APPDATA=(Join-Path $data 'appdata');LOCALAPPDATA=(Join-Path $data 'localappdata')
        EPHARM_POSM_CONFIG=$configPath;EPHARM_APP_LOG=(Join-Path $Output 'app.log');EPHARM_NO_VIDEO='false';EPHARM_SCREEN_MODE='prod';EPHARM_DEBUG='0';EPHARM_DISABLE_CONSOLE_LOG='true';EPHARM_STAFF_AUTH_ENABLED='false';EPHARM_STANDARDN_DB_ENABLED='false';EPHARM_UPDATE_ENABLED='false'
    }
    foreach ($key in $environment.Keys) { $psi.EnvironmentVariables[$key]=[string]$environment[$key] }
    if (@($psi.EnvironmentVariables.Keys | Where-Object { $_ -match 'TOKEN|SECRET|PASSWORD|GITHUB|ACTIONS' }).Count) { throw 'Unexpected inherited credentials in the fixture app environment.' }
    $app = [Diagnostics.Process]::Start($psi)
    $startTicks = $app.StartTime.ToUniversalTime().Ticks
    if ($app.SessionId -ne $session) { throw 'App and rendering probe run in different sessions.' }
    $proof.App=[ordered]@{ProcessId=$app.Id;StartUtc=$app.StartTime.ToUniversalTime().ToString('o');StartTicks=$startTicks;SessionId=$app.SessionId;Executable=$exe;EnvironmentInherited=$false}
    $main=$null;$until=[DateTime]::UtcNow.AddSeconds(90)
    do {
        $main=@(Get-OwnWindows | Where-Object { $_.Visible -and $_.Title -eq 'Customer Display' -and (Covers $_ $secondary.Bounds) }) | Select-Object -First 1
        if ($main) { break };Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $until)
    if (-not $main) { throw 'Actual main HWND did not cover the nonprimary display in prod mode.' }
    $proof.CustomerWindow=$main
    # Interior of the 75% video panel, away from the receipt, cursor and taskbar.
    $roi=[ordered]@{Left=$main.Left+[int]($main.Width*0.12);Top=$main.Top+[int]($main.Height*0.42);Width=[int]($main.Width*0.44);Height=[int]($main.Height*0.16)}
    $first=Join-Path $Output 'customer-frame-1.png';$second=Join-Path $Output 'customer-frame-2.png'
    $motion=$null;$until=[DateTime]::UtcNow.AddSeconds(60)
    do {
        $null=Get-OwnWindows
        [EpharmDisplayNative]::Capture($roi.Left,$roi.Top,$roi.Width,$roi.Height,$first)
        Start-Sleep -Milliseconds 1100
        [EpharmDisplayNative]::Capture($roi.Left,$roi.Top,$roi.Width,$roi.Height,$second)
        $motion=[EpharmDisplayNative]::Difference($first,$second)
        if ($motion.ChangedRatio -ge 0.005 -and $motion.DistinctColors -ge 12) { break }
    } while ([DateTime]::UtcNow -lt $until)
    $proof.VideoRegion=$roi;$proof.VideoDifference=$motion
    if ($motion.ChangedRatio -lt 0.005 -or $motion.DistinctColors -lt 12) { throw 'The customer video interior did not show actual changing rendered pixels.' }
    if (-not @($server.Trace.ToArray()|Where-Object { $_ -match ' GET /pilot.mp4$' }).Count) { throw 'The normal media pipeline did not request the synthetic video.' }
    [EpharmDisplayNative]::Capture($secondary.Bounds.Left,$secondary.Bounds.Top,$secondary.Bounds.Width,$secondary.Bounds.Height,(Join-Path $Output 'customer-screen.png'))
    [EpharmDisplayNative]::FocusAndPressDemo($main.Handle,$app.Id,($main.Right-[int]($main.Width*0.10)),($main.Top+[int]($main.Height*0.15)))
    $proof.DemoInput='Real SendInput click in own receipt area, foreground PID verified, physical D key down/up; no reflection or private message injection.'
    $popup=$null;$until=[DateTime]::UtcNow.AddSeconds(15)
    do {
        $popup=@(Get-OwnWindows|Where-Object { $_.Visible -and $_.Recommendation -and (InBounds $_ $primary.WorkingArea) })|Select-Object -First 1
        if($popup){break};Start-Sleep -Milliseconds 100
    } while([DateTime]::UtcNow -lt $until)
    if(-not $popup){throw 'The real built-in D action did not display a PID-owned recommendation HWND on the primary pharmacist display.'}
    Start-Sleep -Milliseconds 400
    $proof.PharmacistPopup=$popup
    $concurrent1=Join-Path $Output 'concurrent-customer-frame-1.png';$concurrent2=Join-Path $Output 'concurrent-customer-frame-2.png'
    [EpharmDisplayNative]::Capture($roi.Left,$roi.Top,$roi.Width,$roi.Height,$concurrent1)
    Start-Sleep -Milliseconds 1100
    [EpharmDisplayNative]::Capture($roi.Left,$roi.Top,$roi.Width,$roi.Height,$concurrent2)
    $proof.ConcurrentVideoDifference=[EpharmDisplayNative]::Difference($concurrent1,$concurrent2)
    if($proof.ConcurrentVideoDifference.ChangedRatio -lt 0.005 -or $proof.ConcurrentVideoDifference.DistinctColors -lt 12){throw 'Rendered customer video stopped changing while the pharmacist popup was visible.'}
    if(-not @(Get-OwnWindows|Where-Object {$_.Handle -eq $popup.Handle -and $_.Visible -and (InBounds $_ $primary.WorkingArea)}).Count){throw 'Popup disappeared before concurrent display evidence.'}
    $wholeDesktop=[Drawing.Rectangle]::Union($primary.Bounds,$secondary.Bounds)
    [EpharmDisplayNative]::Capture($wholeDesktop.Left,$wholeDesktop.Top,$wholeDesktop.Width,$wholeDesktop.Height,(Join-Path $Output 'both-displays-concurrent.png'))
    [EpharmDisplayNative]::Capture($primary.Bounds.Left,$primary.Bounds.Top,$primary.Bounds.Width,$primary.Bounds.Height,(Join-Path $Output 'pharmacist-screen.png'))
    [EpharmDisplayNative]::Capture($popup.Left,$popup.Top,$popup.Width,$popup.Height,(Join-Path $Output 'pharmacist-popup.png'))
    $proof.Windows=@(Get-OwnWindows)
    $proof.Status='passed'
    $proof.CompletedUtc=[DateTime]::UtcNow.ToString('o')
} catch {
    $proof.Error=[ordered]@{Type=$_.Exception.GetType().Name;Message=$_.Exception.Message}
    if($app -and -not $app.HasExited){$proof.Windows=@([EpharmDisplayNative]::Windows($app.Id))}
    throw
} finally {
    $cleanupErrors=@()
    if($server){[IO.File]::WriteAllLines((Join-Path $Output 'mock-http-trace.txt'),[string[]]$server.Trace.ToArray());try{$server.Dispose()}catch{$cleanupErrors+=$_.Exception.Message}}
    if($app){try{if(-not $app.HasExited){$current=Get-Process -Id $app.Id -ErrorAction Stop;if($current.StartTime.ToUniversalTime().Ticks -ne $startTicks -or $current.Path -ine $exe){throw 'Own process identity changed; refuse cleanup'};$null=$app.CloseMainWindow();if(-not $app.WaitForExit(10000)){$current.Kill();$current.WaitForExit(5000)|Out-Null}}}catch{$cleanupErrors+=$_.Exception.Message};$app.Dispose()}
    foreach($name in $rules){try{$rule=Get-NetFirewallRule -Name $name -ErrorAction Stop;$filter=$rule|Get-NetFirewallApplicationFilter;if($filter.Program -ine $exe){throw 'Own firewall rule identity changed; refuse cleanup'};Remove-NetFirewallRule -Name $name}catch{$cleanupErrors+=$_.Exception.Message}}
    $proof.CleanupErrors=$cleanupErrors
    [IO.File]::WriteAllText((Join-Path $Output 'display-render-proof.json'),($proof|ConvertTo-Json -Depth 10))
    if($cleanupErrors.Count){throw ('Owned display fixture cleanup failed: '+($cleanupErrors -join '; '))}
}
