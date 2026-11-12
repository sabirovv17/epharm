using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Runtime.Loader;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation.Peers;
using System.Windows.Automation.Provider;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using CustomerDisplay;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Forms = System.Windows.Forms;
using WpfApplication = System.Windows.Application;

internal static class Bootstrap
{
    internal static readonly JsonSerializerOptions Json = new() {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true,
    };

    [STAThread]
    public static int Main(string[] args)
    {
        try {
            if (!OperatingSystem.IsWindows() || !Environment.UserInteractive || Process.GetCurrentProcess().SessionId == 0)
                throw new PilotFailure("InteractiveWindowsDesktopRequired");
            if (args.FirstOrDefault() == "--cashier-helper") return Cashier.Run(args[1..]);
            var options = Options.Parse(args);
            // No product type is touched until its path/hash/version have been checked.
            options.CheckArtifact();
            AssemblyLoadContext.Default.Resolving += (_, name) => {
                if (name.Name is not ("CustomerDisplay" or "QRCoder")) return null;
                var path = Path.Combine(options.ArtifactRoot, name.Name + ".dll");
                return File.Exists(path) ? AssemblyLoadContext.Default.LoadFromAssemblyPath(path) : null;
            };
            return StartUi(options);
        }
        catch (Exception error) {
            Console.Error.WriteLine(error is PilotFailure failure ? failure.Code : error.GetType().Name);
            return 2;
        }
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static int StartUi(Options options) => new Pilot(options).Run();

    internal static void WriteJson(string path, object value)
    {
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(value, Json) + Environment.NewLine, new UTF8Encoding(false));
        File.Move(temporary, path, overwrite: true);
    }

    internal static string Sha(string path) => Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path))).ToLowerInvariant();
    internal static void Require(bool value, string code) { if (!value) throw new PilotFailure(code); }
}

internal sealed class PilotFailure(string code) : Exception(code)
{
    public string Code { get; } = code;
}

internal sealed record Options(string ArtifactRoot, string Output, Version ExpectedVersion, string ExpectedSha, int ForegroundWaitSeconds)
{
    public static Options Parse(string[] args)
    {
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        for (var i = 0; i < args.Length; i += 2) {
            Bootstrap.Require(i + 1 < args.Length && args[i].StartsWith("--", StringComparison.Ordinal) && values.TryAdd(args[i], args[i + 1]), "InvalidArguments");
        }
        var allowed = new[] { "--artifact-root", "--output", "--expected-version", "--expected-dll-sha256", "--foreground-wait-seconds" };
        Bootstrap.Require(values.Keys.All(allowed.Contains) && allowed.Take(4).All(values.ContainsKey), "InvalidArguments");
        var sha = values["--expected-dll-sha256"].ToLowerInvariant();
        Bootstrap.Require(sha.Length == 64 && sha.All(Uri.IsHexDigit), "InvalidExpectedHash");
        var version = Version.Parse(values["--expected-version"]);
        Bootstrap.Require(version.Build >= 0, "ExpectedThreePartVersionRequired");
        var wait = values.TryGetValue("--foreground-wait-seconds", out var raw) ? int.Parse(raw) : 30;
        Bootstrap.Require(wait is >= 5 and <= 60, "InvalidForegroundWait");
        var artifact = Path.GetFullPath(values["--artifact-root"]);
        var output = Path.GetFullPath(values["--output"]);
        Bootstrap.Require(!artifact.StartsWith(@"\\", StringComparison.Ordinal) && !output.StartsWith(@"\\", StringComparison.Ordinal), "NetworkPathsRefused");
        Bootstrap.Require(new DriveInfo(Path.GetPathRoot(artifact)!).DriveType != DriveType.Network &&
            new DriveInfo(Path.GetPathRoot(output)!).DriveType != DriveType.Network, "NetworkDrivesRefused");
        CheckPhysical(artifact); CheckPhysical(Path.GetDirectoryName(output)!);
        Bootstrap.Require(!Under(output, artifact) && !Under(artifact, output) && !Under(AppContext.BaseDirectory, artifact) &&
            !Under(artifact, AppContext.BaseDirectory) && !Under(output, AppContext.BaseDirectory) && !Under(AppContext.BaseDirectory, output), "SeparateArtifactHostAndProofDirectoriesRequired");
        Bootstrap.Require(!Directory.Exists(output) || !Directory.EnumerateFileSystemEntries(output).Any(), "FreshProofDirectoryRequired");
        Directory.CreateDirectory(output); CheckPhysical(output);
        return new(artifact, output, version, sha, wait);
    }

    public string DllPath => Path.Combine(ArtifactRoot, "CustomerDisplay.dll");
    public bool MatchesVersion(Version? actual) => actual != null && actual.Major == ExpectedVersion.Major &&
        actual.Minor == ExpectedVersion.Minor && actual.Build == ExpectedVersion.Build && Math.Max(actual.Revision, 0) == Math.Max(ExpectedVersion.Revision, 0);
    public void CheckArtifact()
    {
        CheckPhysical(DllPath);
        Bootstrap.Require(File.Exists(DllPath) && Bootstrap.Sha(DllPath) == ExpectedSha, "OfficialDllHashMismatch");
        Bootstrap.Require(MatchesVersion(AssemblyName.GetAssemblyName(DllPath).Version), "OfficialDllVersionMismatch");
    }

    private static bool Under(string child, string parent)
    {
        child = Path.TrimEndingDirectorySeparator(Path.GetFullPath(child));
        parent = Path.TrimEndingDirectorySeparator(Path.GetFullPath(parent));
        return child.Equals(parent, StringComparison.OrdinalIgnoreCase) || child.StartsWith(parent + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase);
    }
    private static void CheckPhysical(string path)
    {
        for (string? current = Path.GetFullPath(path); current != null; current = Path.GetDirectoryName(current))
            if (File.Exists(current) || Directory.Exists(current)) Bootstrap.Require((File.GetAttributes(current) & FileAttributes.ReparsePoint) == 0, "ReparsePathRefused");
    }
}

internal sealed record CashierReady(string Instance, int Pid, long StartTicks, int SessionId, long Hwnd, long InputHwnd, bool ProductAssemblyLoaded);
internal sealed record RectangleProof(int Left, int Top, int Right, int Bottom);
internal sealed record NativeProof(string Hwnd, int Pid, int ThreadId, RectangleProof Bounds, string Style, string ExtendedStyle, bool Visible, bool Iconic);
internal sealed record FocusProof(string Hwnd, int Pid, string KeyboardFocusHwnd, bool CashierOwnsForeground, bool CashierInputOwnsKeyboardFocus);
internal sealed record ScreenshotProof(string File, int Width, int Height, string Sha256);
internal sealed record CaseProof(string Name, bool Passed, Dictionary<string, bool> Assertions, Dictionary<string, object?> Evidence, string? FailureCode);

internal sealed class Pilot(Options options)
{
    private readonly List<StaffAuthWindow> _windows = new();
    private readonly List<CaseProof> _cases = new();
    private readonly CancellationTokenSource _deadline = new();
    private Process? _cashier;
    private CashierReady? _ready;
    private string _instance = Guid.NewGuid().ToString("N");
    private string _step = "starting";
    private readonly long _hostStartTicks = CurrentStartTicks();
    private bool _cleaned;
    private bool _cashierWasKilled;
    private int? _cashierExitCode;
    private string? _cleanupFailure;
    private readonly object _cleanupLock = new();
    private int _exitCode = 1;
    private static readonly string[] ProductNames = ["CustomerDisplay.exe", "CustomerDisplay.dll", "CustomerDisplay.deps.json", "CustomerDisplay.runtimeconfig.json", "QRCoder.dll"];
    private Dictionary<string, string> _before = new();

    public int Run()
    {
        var loaded = typeof(StaffAuthWindow).Assembly;
        Bootstrap.Require(Path.GetFullPath(loaded.Location).Equals(options.DllPath, StringComparison.OrdinalIgnoreCase), "LoadedProductPathMismatch");
        Bootstrap.Require(options.MatchesVersion(loaded.GetName().Version), "LoadedProductVersionMismatch");
        var reference = typeof(Bootstrap).Assembly.GetReferencedAssemblies().Single(x => x.Name == "CustomerDisplay");
        Bootstrap.Require(options.MatchesVersion(reference.Version), "HostMustBeBuiltAgainstExpectedOfficialVersion");
        _before = ProductNames.Where(n => File.Exists(Path.Combine(options.ArtifactRoot, n))).ToDictionary(n => n, n => Bootstrap.Sha(Path.Combine(options.ArtifactRoot, n)));
        options.CheckArtifact();
        var app = new WpfApplication { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        app.DispatcherUnhandledException += (_, failure) => {
            failure.Handled = true;
            _cases.Add(new("dispatcher-unhandled-failure", false, new(), new(), SafeCode(failure.Exception)));
            _deadline.Cancel();
        };
        using var watchdog = new System.Threading.Timer(_ => EmergencyAbort(), null, TimeSpan.FromSeconds(140), Timeout.InfiniteTimeSpan);
        app.Startup += async (_, _) => {
            try {
                await StartCashier();
                await RunCase("automatic-known-source-focus-minimize-close", InitialWindow);
                await RunCase("pending-login-native-close-rejects-late-success", (a, e) => PendingExit(false, a, e));
                await RunCase("pending-login-continue-rejects-late-success", (a, e) => PendingExit(true, a, e));
            }
            catch (Exception error) { _cases.Add(new(_step, false, new(), new(), SafeCode(error))); }
            finally {
                foreach (var window in _windows) try { window.Dismiss(); } catch { }
                CleanupCashier();
                var after = new Dictionary<string, string>();
                try { foreach (var name in _before.Keys) after.Add(name, Bootstrap.Sha(Path.Combine(options.ArtifactRoot, name))); }
                catch { _cases.Add(new("product-fingerprint-after-failure", false, new(), new(), "ProductFingerprintAfterFailed")); }
                var unchanged = _before.Count == after.Count && _before.All(x => after.TryGetValue(x.Key, out var hash) && hash == x.Value);
                var passed = _cases.Count == 3 && _cases.All(x => x.Passed) && unchanged && _cleaned && _cleanupFailure == null;
                Bootstrap.WriteJson(Path.Combine(options.Output, "cross-process-ui-proof.json"), new {
                    Schema = "epharm-private-cross-process-staff-ui/v1", Status = passed ? "PASS" : "FAIL", CompletedUtc = DateTimeOffset.UtcNow,
                    ExpectedVersion = options.ExpectedVersion.ToString(), ActualVersion = loaded.GetName().Version?.ToString(),
                    ProductDllSha256 = _before["CustomerDisplay.dll"], ProductDllAfterSha256 = after.GetValueOrDefault("CustomerDisplay.dll"), LoadedProductPath = loaded.Location,
                    ProductFilesBefore = _before, ProductFilesAfter = after, ProductFilesUnchanged = unchanged,
                    HostPid = Environment.ProcessId, HostStartTicks = _hostStartTicks, HostSessionId = Process.GetCurrentProcess().SessionId,
                    HostExePath = Environment.ProcessPath, HostExeSha256 = Bootstrap.Sha(Environment.ProcessPath!),
                    HostAssemblySha256 = Bootstrap.Sha(typeof(Bootstrap).Assembly.Location),
                    HostRuntime = RuntimeInformation.FrameworkDescription, HostArchitecture = RuntimeInformation.ProcessArchitecture.ToString(),
                    DesktopAssemblyVersion = typeof(Window).Assembly.GetName().Version?.ToString(),
                    Cashier = _ready, Cases = _cases, Cleanup = new { OwnedCashierExited = _cleaned, ForcedTermination = _cashierWasKilled, ExitCode = _cashierExitCode, FailureCode = _cleanupFailure },
                    Limits = new { ProductMainWindowStartupInvoked = false, RealStandardN = false, PhysicalScanner = false,
                        LiveAccountsOrSms = false, HttpTransport = "in-memory handler only", ForegroundPolicyChanged = false, SecurityOrTimeSettingsChanged = false },
                });
                _exitCode = passed ? 0 : 1;
                app.Shutdown(_exitCode);
            }
        };
        app.Run();
        return _exitCode;
    }

    private async Task StartCashier()
    {
        _step = "start-independent-cashier";
        var executable = Environment.ProcessPath!;
        Bootstrap.Require(Path.GetFileNameWithoutExtension(executable).Equals("Epharm.PrivateStaffPilot", StringComparison.OrdinalIgnoreCase), "RunPublishedAppHostExe");
        var folder = Path.Combine(options.Output, "cashier"); Directory.CreateDirectory(folder);
        using var self = Process.GetCurrentProcess();
        var info = new ProcessStartInfo(executable) { UseShellExecute = false, WorkingDirectory = AppContext.BaseDirectory };
        foreach (var argument in new[] { "--cashier-helper", folder, _instance, Environment.ProcessId.ToString(), self.StartTime.ToUniversalTime().Ticks.ToString() }) info.ArgumentList.Add(argument);
        _cashier = Process.Start(info) ?? throw new PilotFailure("CashierLaunchFailed");
        var file = Path.Combine(folder, "cashier-ready.json");
        await Until(() => File.Exists(file), TimeSpan.FromSeconds(15));
        _ready = JsonSerializer.Deserialize<CashierReady>(File.ReadAllText(file), Bootstrap.Json) ?? throw new PilotFailure("InvalidCashierReady");
        CheckOwnedCashier();
        Bootstrap.Require(!_ready.ProductAssemblyLoaded && _ready.Pid != Environment.ProcessId && _ready.SessionId == self.SessionId, "SeparateCashierProcessRequired");
        Native.SetForegroundWindow(new(_ready.Hwnd));
        var grace = Stopwatch.GetTimestamp();
        while (!(Focus().CashierOwnsForeground && Focus().CashierInputOwnsKeyboardFocus) && Stopwatch.GetElapsedTime(grace) < TimeSpan.FromSeconds(1))
            await Task.Delay(50, _deadline.Token);
        var focusReady = Focus().CashierOwnsForeground && Focus().CashierInputOwnsKeyboardFocus;
        WriteReady(focusReady);
        if (!focusReady) {
            await Until(() => Focus().CashierOwnsForeground && Focus().CashierInputOwnsKeyboardFocus, TimeSpan.FromSeconds(options.ForegroundWaitSeconds));
            WriteReady(true);
        }
    }

    private void WriteReady(bool foregroundReady) => Bootstrap.WriteJson(Path.Combine(options.Output, "ready.json"), new {
        Status = foregroundReady ? "CASHIER_FOREGROUND_READY" : "WAITING_FOR_CASHIER_FOREGROUND", HostPid = Environment.ProcessId,
        HostStartTicks = _hostStartTicks, HostExePath = Environment.ProcessPath, Cashier = _ready,
        ForegroundWaitSeconds = options.ForegroundWaitSeconds, OneManualClickOnOwnedSyntheticCashierPermitted = !foregroundReady,
        Foreground = Focus(),
        Instruction = "Only if waiting: click this owned synthetic cashier once in the own VM. Never change foreground/security policy.",
    });

    private async Task RunCase(string name, Func<Dictionary<string, bool>, Dictionary<string, object?>, Task> body)
    {
        _step = name;
        var assertions = new Dictionary<string, bool>(); var evidence = new Dictionary<string, object?>(); string? error = null;
        try { await body(assertions, evidence); }
        catch (Exception failure) { error = SafeCode(failure); }
        finally { foreach (var window in _windows) if (window.IsVisible) try { window.Dismiss(); } catch { } }
        _cases.Add(new(name, error == null && assertions.Count > 0 && assertions.Values.All(x => x), assertions, evidence, error));
    }

    private async Task InitialWindow(Dictionary<string, bool> a, Dictionary<string, object?> e)
    {
        using var scenario = CreateWindow();
        await EstablishCashierFocus(); var before = Focus();
        scenario.Window.Show();
        var immediate = Focus(); e["immediatelyAfterShow"] = immediate;
        Check(a, "automatic-show-keeps-immediate-native-focus", immediate.CashierOwnsForeground && immediate.CashierInputOwnsKeyboardFocus);
        await Idle();
        var hwnd = new WindowInteropHelper(scenario.Window).Handle; var native = WindowProof(hwnd);
        e["beforeShow"] = before; e["window"] = native;
        Check(a, "real-cashier-foreground-before-show", before.CashierOwnsForeground && before.CashierInputOwnsKeyboardFocus);
        await VerifyNoFocusSteal(a, e);
        CheckWindowStyle(a, scenario.Window, hwnd);
        e["screenshot"] = Capture(scenario.Window, "01-auto-show.png");
        Check(a, "native-minimize-message-sent", Native.PostMessage(hwnd, Native.WM_SYSCOMMAND, new(Native.SC_MINIMIZE), IntPtr.Zero));
        await Until(() => Native.IsIconic(hwnd), TimeSpan.FromSeconds(3)); await Task.Delay(250, _deadline.Token);
        Check(a, "ordinary-minimize-remains-minimized", Native.IsIconic(hwnd) && scenario.Window.WindowState == WindowState.Minimized);
        e["minimizedWindow"] = WindowProof(hwnd);
        Check(a, "native-close-message-sent", Native.PostMessage(hwnd, Native.WM_CLOSE, IntPtr.Zero, IntPtr.Zero));
        await Until(() => !Native.IsWindow(hwnd), TimeSpan.FromSeconds(3));
        Check(a, "native-close-destroys-owned-window-and-cancels", !scenario.Window.IsVisible && scenario.Window.ClosedToken.IsCancellationRequested);
        Check(a, "no-authentication-or-http-on-presentation", scenario.Handler.TotalCalls == 0 && scenario.Completions == 0 && scenario.AuthenticatedEvents == 0 && scenario.State.Grant == null);
    }

    private async Task PendingExit(bool continueWork, Dictionary<string, bool> a, Dictionary<string, object?> e)
    {
        using var scenario = CreateWindow(); await EstablishCashierFocus();
        scenario.Window.Show();
        var immediate = Focus(); e["immediatelyAfterShow"] = immediate;
        Check(a, "automatic-show-keeps-immediate-native-focus", immediate.CashierOwnsForeground && immediate.CashierInputOwnsKeyboardFocus);
        await Idle(); var hwnd = new WindowInteropHelper(scenario.Window).Handle;
        await VerifyNoFocusSteal(a, e); CheckWindowStyle(a, scenario.Window, hwnd);
        Field<TextBox>(scenario.Window, "_iin").Text = DeferredHandler.FixtureIin;
        Field<PasswordBox>(scenario.Window, "_password").Password = DeferredHandler.FixturePassword;
        Invoke(Field<Button>(scenario.Window, "_submit"));
        await scenario.Handler.Started.Task.WaitAsync(TimeSpan.FromSeconds(5), _deadline.Token);
        Check(a, "deferred-request-pending", scenario.Window.IsRequestPending && !scenario.Handler.Returned.Task.IsCompleted);
        var leave = Field<Button>(scenario.Window, "_continueWithoutSignIn");
        Check(a, "continue-visible-and-enabled-during-http", leave.IsVisible && leave.IsEnabled);
        e["pendingWindow"] = WindowProof(hwnd); e["screenshot"] = Capture(scenario.Window, continueWork ? "03-pending-continue.png" : "02-pending-close.png");
        if (continueWork) { Invoke(leave); e["exitMethod"] = "actual-button-UIAutomation-InvokePattern"; }
        else { Check(a, "native-close-message-sent", Native.PostMessage(hwnd, Native.WM_CLOSE, IntPtr.Zero, IntPtr.Zero)); e["exitMethod"] = "WM_CLOSE"; }
        await Until(() => !Native.IsWindow(hwnd), TimeSpan.FromSeconds(3));
        Check(a, "exit-completes-before-http-response", scenario.Window.ClosedToken.IsCancellationRequested && !scenario.Handler.Returned.Task.IsCompleted);
        scenario.Handler.Release.TrySetResult();
        await scenario.Handler.Returned.Task.WaitAsync(TimeSpan.FromSeconds(3), _deadline.Token);
        await Until(() => !scenario.Window.IsRequestPending, TimeSpan.FromSeconds(3)); await Idle();
        Check(a, "valid-synthetic-success-was-returned-after-cancel", scenario.Handler.ResponseAfterCancellation && scenario.Handler.BodyMatched);
        Check(a, "late-result-never-reaches-authentication-parent", scenario.Completions == 0 && scenario.AuthenticatedEvents == 0 && scenario.State.Grant == null && !scenario.State.IsAuthenticated(DateTimeOffset.UtcNow));
        Check(a, "one-request-no-retry-or-unexpected-route", scenario.Handler.TotalCalls == 1 && scenario.Handler.UnexpectedCalls == 0);
        Check(a, "closed-fields-cleared", Field<TextBox>(scenario.Window, "_iin").Text.Length == 0 && Field<PasswordBox>(scenario.Window, "_password").Password.Length == 0);
        e["http"] = new { scenario.Handler.TotalCalls, scenario.Handler.UnexpectedCalls, scenario.Handler.BodyMatched, scenario.Handler.ResponseAfterCancellation, AuthenticationCompletions = scenario.Completions, LegacyAuthenticationEvents = scenario.AuthenticatedEvents };
    }

    private Scenario CreateWindow()
    {
        CheckOwnedCashier(); var ready = _ready!;
        var processIdentity = ready.Pid.ToString() + ":" + ready.StartTicks.ToString();
        var state = new StaffDeviceSessionState();
        state.Observe(new("resolved", processIdentity), new("resolved", new StaffObservedSession(processIdentity, "DEV-UI-USER", "DEV-UI-SESSION", "DEV-UI-WORKSTATION", DeferredHandler.FixtureIin, "available")));
        var context = state.Context ?? throw new PilotFailure("SyntheticContextMissing");
        var handler = new DeferredHandler(context); var client = new StaffAuthClient(new EpharmConfig {
            Enabled = false, BackendBaseUrl = "https://dev.invalid", BackendFallbackBaseUrls = new(),
            DeviceId = "DEV-PR164-CROSSPROCESS-UI", DeviceKey = "DEV-NOT-A-REAL-POSM-KEY",
        }, handler);
        var window = new StaffAuthWindow(client, context.StandardNContext, Forms.Screen.PrimaryScreen!, "Private synthetic pilot", requireSignIn: true, deviceContext: context, registrationSmsRequired: false);
        _windows.Add(window);
        return new Scenario(window, client, handler, state, context);
    }

    private async Task EstablishCashierFocus()
    {
        CheckOwnedCashier(); Native.SetForegroundWindow(new(_ready!.Hwnd));
        await Until(() => Focus().CashierOwnsForeground && Focus().CashierInputOwnsKeyboardFocus, TimeSpan.FromSeconds(3));
        await Task.Delay(250, _deadline.Token); Bootstrap.Require(Focus().CashierOwnsForeground && Focus().CashierInputOwnsKeyboardFocus, "CashierFocusPreconditionLost");
    }

    private async Task VerifyNoFocusSteal(Dictionary<string, bool> a, Dictionary<string, object?> e)
    {
        var samples = new List<FocusProof>();
        for (var i = 0; i < 10; i++) { samples.Add(Focus()); await Task.Delay(50, _deadline.Token); }
        e["foregroundSamplesAfterAutomaticShow"] = samples;
        Check(a, "automatic-show-keeps-separate-process-foreground-and-keyboard-focus", samples.All(x => x.CashierOwnsForeground && x.CashierInputOwnsKeyboardFocus));
    }

    private static void CheckWindowStyle(Dictionary<string, bool> a, StaffAuthWindow window, IntPtr hwnd)
    {
        var style = Native.GetWindowLongPtr(hwnd, -16).ToInt64(); var extended = Native.GetWindowLongPtr(hwnd, -20).ToInt64();
        Check(a, "ordinary-native-caption-resize-minimize", (style & 0x00C00000) == 0x00C00000 && (style & 0x00040000) != 0 && (style & 0x00020000) != 0);
        Check(a, "no-native-topmost-or-automatic-activation", (extended & 8) == 0 && !window.Topmost && !window.ShowActivated);
        var work = Forms.Screen.PrimaryScreen!.WorkingArea; Native.GetWindowRect(hwnd, out var rect);
        Check(a, "owned-window-smaller-than-working-area", rect.Right - rect.Left < work.Width && rect.Bottom - rect.Top < work.Height);
        Check(a, "actual-product-window-owned-by-host", Native.OwnerPid(hwnd) == Environment.ProcessId && Native.IsWindowVisible(hwnd));
    }

    private FocusProof Focus()
    {
        CheckOwnedCashier(); var ready = _ready!; var foreground = Native.GetForegroundWindow(); var pid = Native.OwnerPid(foreground);
        var thread = Native.GetWindowThreadProcessId(new(ready.Hwnd), out _); var gui = new Native.GuiThreadInfo { Size = (uint)Marshal.SizeOf<Native.GuiThreadInfo>() };
        var gotGui = Native.GetGUIThreadInfo(thread, ref gui);
        return new(Native.Hex(foreground), pid, Native.Hex(gui.Focus), pid == ready.Pid && foreground == new IntPtr(ready.Hwnd), gotGui && gui.Focus == new IntPtr(ready.InputHwnd));
    }

    private void CheckOwnedCashier()
    {
        var child = _cashier ?? throw new PilotFailure("CashierNotStarted"); var ready = _ready ?? throw new PilotFailure("CashierNotReady");
        Bootstrap.Require(!child.HasExited && child.Id == ready.Pid && child.StartTime.ToUniversalTime().Ticks == ready.StartTicks && ready.Instance == _instance, "OwnedCashierIdentityMismatch");
        Bootstrap.Require(Native.IsWindow(new(ready.Hwnd)) && Native.OwnerPid(new(ready.Hwnd)) == ready.Pid &&
            Native.IsChild(new(ready.Hwnd), new(ready.InputHwnd)) && Native.OwnerPid(new(ready.InputHwnd)) == ready.Pid, "OwnedCashierHwndMismatch");
    }

    private static NativeProof WindowProof(IntPtr hwnd)
    {
        Bootstrap.Require(Native.GetWindowRect(hwnd, out var rect), "WindowBoundsUnavailable");
        var thread = Native.GetWindowThreadProcessId(hwnd, out var pid);
        return new(Native.Hex(hwnd), (int)pid, (int)thread, new(rect.Left, rect.Top, rect.Right, rect.Bottom),
            Native.GetWindowLongPtr(hwnd, -16).ToInt64().ToString("X"), Native.GetWindowLongPtr(hwnd, -20).ToInt64().ToString("X"), Native.IsWindowVisible(hwnd), Native.IsIconic(hwnd));
    }

    private ScreenshotProof Capture(StaffAuthWindow window, string name)
    {
        var dpi = VisualTreeHelper.GetDpi(window); var width = (int)Math.Ceiling(window.ActualWidth * dpi.DpiScaleX); var height = (int)Math.Ceiling(window.ActualHeight * dpi.DpiScaleY);
        Bootstrap.Require(width > 0 && height > 0, "ScreenshotBoundsUnavailable");
        var image = new RenderTargetBitmap(width, height, dpi.PixelsPerInchX, dpi.PixelsPerInchY, PixelFormats.Pbgra32); image.Render(window);
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(image)); var path = Path.Combine(options.Output, name);
        using (var file = new FileStream(path, FileMode.CreateNew, FileAccess.Write)) encoder.Save(file);
        Bootstrap.Require(new FileInfo(path).Length > 1000, "EmptyScreenshotRefused");
        return new(name, width, height, Bootstrap.Sha(path));
    }

    private async Task Until(Func<bool> condition, TimeSpan budget)
    {
        var start = Stopwatch.GetTimestamp();
        while (!condition()) {
            _deadline.Token.ThrowIfCancellationRequested();
            if (Stopwatch.GetElapsedTime(start) >= budget) throw new PilotFailure("ConditionDeadlineExceeded");
            await Task.Delay(50, _deadline.Token);
        }
    }
    private static Task Idle() => WpfApplication.Current.Dispatcher.InvokeAsync(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle).Task;
    private static T Field<T>(StaffAuthWindow window, string name) where T : class => typeof(StaffAuthWindow).GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)?.GetValue(window) as T ?? throw new PilotFailure("ProductControlUnavailable");
    private static void Invoke(Button button)
    {
        Bootstrap.Require(button.IsEnabled && button.IsVisible, "ProductButtonUnavailable");
        var invoke = new ButtonAutomationPeer(button).GetPattern(PatternInterface.Invoke) as IInvokeProvider ?? throw new PilotFailure("ProductButtonInvokeUnavailable");
        invoke.Invoke();
    }
    private static void Check(Dictionary<string, bool> values, string key, bool condition) { values.Add(key, condition); Bootstrap.Require(condition, key); }
    private static string SafeCode(Exception error) => error is PilotFailure failure ? failure.Code : error.GetType().Name;
    private static long CurrentStartTicks() { using var self = Process.GetCurrentProcess(); return self.StartTime.ToUniversalTime().Ticks; }

    private void CleanupCashier()
    {
        lock (_cleanupLock) {
            if (_cashier == null) { _cleaned = true; return; }
            try {
                if (!_cashier.HasExited) {
                    if (_ready != null) {
                        Bootstrap.Require(_cashier.Id == _ready.Pid && _cashier.StartTime.ToUniversalTime().Ticks == _ready.StartTicks && _ready.Instance == _instance, "CleanupProcessIdentityMismatch");
                        if (Native.IsWindow(new(_ready.Hwnd)) && Native.OwnerPid(new(_ready.Hwnd)) == _cashier.Id)
                            Native.PostMessage(new(_ready.Hwnd), Native.WM_CLOSE, IntPtr.Zero, IntPtr.Zero);
                    }
                    else _cashier.CloseMainWindow();
                    if (!_cashier.WaitForExit(3000)) { _cashierWasKilled = true; _cashier.Kill(entireProcessTree: false); _cashier.WaitForExit(2000); }
                }
                _cleaned = _cashier.HasExited;
                if (!_cleaned) _cleanupFailure = "OwnedCashierDidNotExit";
                else {
                    _cashierExitCode = _cashier.ExitCode;
                    if (_cashierWasKilled || _cashierExitCode != 0) _cleanupFailure = "OwnedCashierDidNotExitNormally";
                }
            }
            catch { _cleanupFailure = "OwnedCashierCleanupFailed"; }
        }
    }

    private void EmergencyAbort()
    {
        _deadline.Cancel(); CleanupCashier();
        try { Bootstrap.WriteJson(Path.Combine(options.Output, "watchdog-failure.json"), new { Status = "FAIL", FailureCode = "HostWatchdogTimeout", Step = _step, HostPid = Environment.ProcessId, OwnedCashierExited = _cleaned, CleanupFailure = _cleanupFailure }); } catch { }
        Environment.Exit(3);
    }
}

internal sealed class Scenario : IDisposable
{
    public StaffAuthWindow Window { get; }
    public StaffAuthClient Client { get; }
    public DeferredHandler Handler { get; }
    public StaffDeviceSessionState State { get; }
    public int Completions { get; private set; }
    public int AuthenticatedEvents { get; private set; }
    public Scenario(StaffAuthWindow window, StaffAuthClient client, DeferredHandler handler, StaffDeviceSessionState state, StaffDeviceContext context)
    {
        Window = window; Client = client; Handler = handler; State = state; var generation = state.Generation;
        window.AuthenticationStarted += () => generation = state.Generation;
        window.Authenticated += _ => AuthenticatedEvents++;
        window.AuthenticationCompletion = response => {
            Completions++;
            if (state.Accept(generation, context, response, DateTimeOffset.UtcNow)) window.Dismiss();
            return Task.CompletedTask;
        };
        window.BypassRequested += reason => state.Bypass(reason);
        window.Closed += (_, _) => { if (!state.IsAuthenticated(DateTimeOffset.UtcNow)) state.Bypass(StaffPromptBypassReason.IdentityUnavailable); };
    }
    public void Dispose() { Handler.Release.TrySetResult(); if (Window.IsVisible) Window.Dismiss(); Client.Dispose(); }
}

internal sealed class DeferredHandler(StaffDeviceContext expected) : HttpMessageHandler
{
    internal const string FixtureIin = "000101500011";
    internal const string FixturePassword = "F4x!";
    public readonly TaskCompletionSource Started = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public readonly TaskCompletionSource Release = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public readonly TaskCompletionSource Returned = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public int TotalCalls { get; private set; }
    public int UnexpectedCalls { get; private set; }
    public bool BodyMatched { get; private set; }
    public bool ResponseAfterCancellation { get; private set; }
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        TotalCalls++;
        if (request.Method != HttpMethod.Post || request.RequestUri?.Host != "dev.invalid" || request.RequestUri.AbsolutePath != "/api/posm/staff/device/login" || request.RequestUri.Query.Length != 0) {
            UnexpectedCalls++; throw new PilotFailure("UnexpectedSyntheticHttpRoute");
        }
        using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
        BodyMatched = JsonSerializer.Deserialize<StaffDeviceContext>(body.RootElement.GetProperty("deviceContext").GetRawText(), Bootstrap.Json) == expected &&
            body.RootElement.GetProperty("iin").GetString() == FixtureIin && body.RootElement.GetProperty("password").GetString() == FixturePassword;
        Bootstrap.Require(BodyMatched, "SyntheticRequestBodyMismatch"); Started.TrySetResult();
        // Deliberately return an otherwise valid success after cancellation to challenge the whole client/form chain.
        await Release.Task.ConfigureAwait(false);
        ResponseAfterCancellation = ct.IsCancellationRequested;
        var response = new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(JsonSerializer.Serialize(new StaffAuthResponse("authenticated", "DEV-INVALID-OUTSIDE-THIS-HANDLER", DateTimeOffset.UtcNow.AddHours(1), new StaffIdentity("DEV-PRIVATE-PERSON", "DEV synthetic employee"), DeviceContext: expected), Bootstrap.Json), Encoding.UTF8, "application/json") };
        Returned.TrySetResult(); return response;
    }
}

internal static class Cashier
{
    public static int Run(string[] args)
    {
        Bootstrap.Require(args.Length == 4, "InvalidCashierArguments");
        var folder = Path.GetFullPath(args[0]); var instance = args[1]; var parentPid = int.Parse(args[2]); var parentTicks = long.Parse(args[3]);
        Bootstrap.Require(Directory.Exists(folder) && instance.Length == 32 && instance.All(Uri.IsHexDigit), "InvalidCashierFolderOrInstance");
        using var parent = Process.GetProcessById(parentPid);
        Bootstrap.Require(!parent.HasExited && parent.StartTime.ToUniversalTime().Ticks == parentTicks, "CashierParentMismatch");
        Forms.Application.SetUnhandledExceptionMode(Forms.UnhandledExceptionMode.ThrowException);
        Forms.Application.EnableVisualStyles(); Forms.Application.SetCompatibleTextRenderingDefault(false);
        var input = new Forms.TextBox { Dock = Forms.DockStyle.Bottom, Text = "PRIVATE SYNTHETIC INPUT", Font = new System.Drawing.Font("Consolas", 14) };
        using var form = new Forms.Form { Text = "DEV PR164 independent cashier " + instance, Width = 470, Height = 180, StartPosition = Forms.FormStartPosition.CenterScreen };
        form.Controls.Add(new Forms.Label { Dock = Forms.DockStyle.Fill, Text = "PRIVATE QA CASHIER — separate process\r\nClick here once if the pilot waits for foreground.\r\nNo Standard-N, accounts, SMS or network.", AutoSize = false });
        form.Controls.Add(input);
        form.Activated += (_, _) => input.Focus();
        using var lifetime = new Forms.Timer { Interval = 500 }; var started = Stopwatch.GetTimestamp();
        lifetime.Tick += (_, _) => {
            try { if (parent.HasExited || parent.StartTime.ToUniversalTime().Ticks != parentTicks || Stopwatch.GetElapsedTime(started) > TimeSpan.FromSeconds(130)) form.Close(); }
            catch { form.Close(); }
        };
        form.Shown += (_, _) => {
            form.Activate(); input.Focus();
            using var self = Process.GetCurrentProcess();
            var productLoaded = AppDomain.CurrentDomain.GetAssemblies().Any(x => x.GetName().Name == "CustomerDisplay");
            Bootstrap.Require(!productLoaded, "CashierMustNotLoadProductAssembly");
            using (var bitmap = new Bitmap(form.Width, form.Height)) { form.DrawToBitmap(bitmap, new System.Drawing.Rectangle(0, 0, form.Width, form.Height)); bitmap.Save(Path.Combine(folder, "cashier.png"), System.Drawing.Imaging.ImageFormat.Png); }
            Bootstrap.WriteJson(Path.Combine(folder, "cashier-ready.json"), new CashierReady(instance, self.Id, self.StartTime.ToUniversalTime().Ticks, self.SessionId, form.Handle.ToInt64(), input.Handle.ToInt64(), productLoaded));
            lifetime.Start();
        };
        Forms.Application.Run(form); return 0;
    }
}

internal static class Native
{
    public const uint WM_CLOSE = 0x0010, WM_SYSCOMMAND = 0x0112;
    public const int SC_MINIMIZE = 0xF020;
    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct GuiThreadInfo { public uint Size, Flags; public IntPtr Active, Focus, Capture, MenuOwner, MoveSize, Caret; public Rect CaretRect; }
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool GetGUIThreadInfo(uint threadId, ref GuiThreadInfo info);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")] public static extern IntPtr GetWindowLongPtr(IntPtr hwnd, int index);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool IsWindow(IntPtr hwnd);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool IsIconic(IntPtr hwnd);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool IsChild(IntPtr parent, IntPtr child);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] public static extern bool PostMessage(IntPtr hwnd, uint message, IntPtr wParam, IntPtr lParam);
    public static int OwnerPid(IntPtr hwnd) { GetWindowThreadProcessId(hwnd, out var pid); return (int)pid; }
    public static string Hex(IntPtr value) => "0x" + value.ToInt64().ToString("X");
}
