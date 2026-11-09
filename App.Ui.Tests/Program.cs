using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using CustomerDisplay;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Screen = System.Windows.Forms.Screen;

internal static class Program
{
    private const string FixtureIin = "000101500011";
    private const string FixturePhone = "+77000000012";
    private const string FixturePassword = "F4x!";
    private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = true, WriteIndented = true };
    private static readonly List<CaseProof> Results = new();
    private static readonly List<StaffAuthWindow> Windows = new();
    private static string Output = Path.Combine(AppContext.BaseDirectory, "proof");
    private static bool _allowNoninteractive;
    private static bool _interactiveSession;
    private static Screen _screen = null!;

    [STAThread]
    public static int Main(string[] args)
    {
        foreach (var argument in args)
        {
            if (argument == "--allow-noninteractive") _allowNoninteractive = true;
            else if (argument.StartsWith("--output=", StringComparison.Ordinal) && argument.Length > "--output=".Length)
                Output = Path.GetFullPath(argument["--output=".Length..]);
            else return 2;
        }
        Directory.CreateDirectory(Output);
        using var process = Process.GetCurrentProcess();
        _interactiveSession = Environment.UserInteractive && process.SessionId > 0;
        if (!_interactiveSession && !_allowNoninteractive)
        {
            WriteProof(false, "Interactive Windows desktop required; session 0 is refused.");
            return 2;
        }
        var application = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        var watchdog = new DispatcherTimer { Interval = TimeSpan.FromSeconds(90) };
        watchdog.Tick += (_, _) => {
            watchdog.Stop();
            Results.Add(new("watchdog", false, new(), "TimeoutException"));
            foreach (var window in Windows) if (window.IsVisible) window.Dismiss();
            WriteProof(false, "Ninety-second UI watchdog expired.");
            application.Shutdown(3);
        };
        application.Startup += async (_, _) => {
            watchdog.Start();
            try
            {
                _screen = Screen.PrimaryScreen ?? throw new InvalidOperationException("No primary display.");
                await Run("required-screen-and-safe-dismiss", RequiredBoundsAndDismiss);
                await Run("registration-local-and-api-errors-retain-passwords-until-success", RegistrationPasswordRetention);
                await Run("initial-registration-never-offers-sms-for-any-capability", RegistrationNeverSms);
                await Run("registration-grant-closes-after-parent-check", RegisterAndClose);
                await Run("wrong-registration-context-does-not-authenticate", WrongContext);
                await Run("service-error-bypasses-without-grant", proof => ServiceFailure(false, proof));
                await Run("sms-error-bypasses-without-grant", proof => ServiceFailure(true, proof));
                await Run("sms-wait-bypasses-without-grant", SmsWait);
                await Run("unknown-cashier-context-is-closable", UnknownContext);
                await Run("responsive-inputs-preserve-native-text-height", InputGeometry);
                await Run("missing-iin-with-full-name-prompts-and-closes-after-login", FullNameLogin);
                await Run("changed-full-name-rejects-earlier-login-grant", ChangedFullName);
                await Run("process-login-with-unreadable-source-closes-and-survives-db-flaps", DeviceLogin);
                await Run("process-registration-without-source-autocloses", DeviceRegister);
                await Run("unknown-source-close-does-not-repeat", proof => DeviceDismiss(false, proof));
                await Run("unknown-source-continue-does-not-repeat", proof => DeviceDismiss(true, proof));
                await Run("changed-process-rejects-inflight-device-login", DeviceChangedProcess);
                await Run("process-service-error-bypasses-without-grant", proof => DeviceServiceFailure(false, proof));
                await Run("process-sms-error-bypasses-without-grant", proof => DeviceServiceFailure(true, proof));
                await Run("recovery-sms-source-flap-keeps-filled-form-and-challenge", SmsSourceFlap);
                await Run("recovery-sms-failure-retains-form-and-can-retry", SmsRetry);
                await Run("device-pending-with-known-source-releases-work-without-grant", DevicePending);
                await Run("explicit-no-sms-registration-uses-direct-route-and-closes", DirectRegistration);
                await Run("registration-policy-rollback-rejects-stale-direct-grant", RegistrationPolicyRollback);
                await Run("sms-free-registration-does-not-bypass-password-recovery", DirectRegistrationRecovery);
                await Run("background-capability-outage-keeps-inflight-registration-valid", BackgroundCapabilityOutage);
                await Run("registration-rate-limit-keeps-specific-error-and-releases-work", proof => RateLimited(false, proof));
                await Run("login-rate-limit-keeps-specific-error-and-releases-work", proof => RateLimited(true, proof));
            }
            catch (Exception error)
            {
                Results.Add(new("harness-fatal", false, new(), error.GetType().Name));
            }
            finally
            {
                watchdog.Stop();
                foreach (var window in Windows) if (window.IsVisible) window.Dismiss();
                var success = Results.Count == 28 && Results.All(result => result.Passed);
                WriteProof(success, null);
                application.Shutdown(success ? 0 : 1);
            }
        };
        return application.Run();
    }

    private static async Task Run(string name, Func<Dictionary<string, bool>, Task> test)
    {
        var assertions = new Dictionary<string, bool>();
        try
        {
            await test(assertions);
            Results.Add(new(name, assertions.Count > 0 && assertions.Values.All(value => value), assertions, null));
        }
        catch (Exception error)
        {
            Results.Add(new(name, false, assertions, error.GetType().Name));
        }
        finally
        {
            foreach (var window in Windows) if (window.IsVisible) window.Dismiss();
        }
        WriteProof(false, "In progress");
    }

    private static EpharmConfig Config() => new() {
        Enabled = false, BackendBaseUrl = "https://dev.invalid", BackendFallbackBaseUrls = new(),
        DeviceId = "DEV-IN-MEMORY-STAFF-UI", DeviceKey = "DEV-FAKE-KEY-NOT-VALID-ON-ANY-SERVER",
    };

    private static StaffAuthContext Context() => new("DEV-CASHIER", "DEV-SESSION", "DEV-WORKSTATION", "DEV-PROCESS", FixtureIin, "available");
    private static StaffSessionState State(StaffAuthContext context)
    {
        var state = new StaffSessionState();
        state.Observe(new("resolved", new StaffObservedSession(context.ProcessInstanceId, context.StandardNUserId,
            context.StandardNSessionId, context.WorkstationId, context.StandardNIin, context.StandardNIinStatus, context.StandardNFullName, context.StandardNFullNameStatus)));
        return state;
    }
    private static StaffAuthWindow Window(StaffAuthClient client, StaffAuthContext? context, bool required)
    {
        var window = new StaffAuthWindow(client, context, _screen, "DEV: isolated test context.", required);
        Windows.Add(window);
        window.Show();
        return window;
    }
    private static T Field<T>(StaffAuthWindow window, string name) where T : class =>
        typeof(StaffAuthWindow).GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)?.GetValue(window) as T
        ?? throw new MissingFieldException("Own harness control not found: " + name);
    private static bool Busy(StaffAuthWindow window) =>
        (bool)(typeof(StaffAuthWindow).GetField("_busy", BindingFlags.Instance | BindingFlags.NonPublic)?.GetValue(window)
        ?? throw new MissingFieldException("Own harness busy marker not found."));
    private static void Click(Button button)
    {
        if (!button.IsEnabled) throw new InvalidOperationException("Own test button is disabled.");
        button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
    }
    private static async Task Idle() => await Dispatcher.CurrentDispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
    private static async Task Until(Func<bool> condition)
    {
        var timeout = DateTimeOffset.UtcNow.AddSeconds(10);
        while (!condition())
        {
            if (DateTimeOffset.UtcNow > timeout) throw new TimeoutException("Own UI test timed out.");
            await Task.Delay(15);
        }
        await Idle();
    }
    private static void Check(Dictionary<string, bool> proof, string name, bool passed)
    {
        proof[name] = passed;
        if (!passed) throw new InvalidOperationException("Assertion failed: " + name);
    }
    private static void RegistrationFields(StaffAuthWindow window)
    {
        Field<ComboBox>(window, "_mode").SelectedIndex = 1;
        Field<TextBox>(window, "_iin").Text = FixtureIin;
        Field<TextBox>(window, "_phone").Text = FixturePhone;
        Field<TextBox>(window, "_name").Text = "DEV synthetic employee";
        Field<PasswordBox>(window, "_password").Password = FixturePassword;
        Field<PasswordBox>(window, "_passwordConfirmation").Password = FixturePassword;
    }
    private static void RecoveryFields(StaffAuthWindow window, int mode = 2)
    {
        Field<ComboBox>(window, "_mode").SelectedIndex = mode;
        Field<TextBox>(window, "_iin").Text = FixtureIin;
        Field<TextBox>(window, "_phone").Text = FixturePhone;
        Field<PasswordBox>(window, "_password").Password = FixturePassword;
        Field<PasswordBox>(window, "_passwordConfirmation").Password = FixturePassword;
    }
    private static async Task RequestOtp(StaffAuthWindow window, FakeHandler handler)
    {
        Click(Field<Button>(window, "_request"));
        await Until(() => handler.OtpCalls == 1 && !Busy(window));
    }
    private static void SaveOwnWindow(StaffAuthWindow window, string name)
    {
        window.UpdateLayout();
        var dpi = VisualTreeHelper.GetDpi(window);
        var bitmap = new RenderTargetBitmap(Math.Max(1, (int)Math.Ceiling(window.ActualWidth * dpi.DpiScaleX)),
            Math.Max(1, (int)Math.Ceiling(window.ActualHeight * dpi.DpiScaleY)), dpi.PixelsPerInchX, dpi.PixelsPerInchY, PixelFormats.Pbgra32);
        bitmap.Render(window);
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = File.Create(Path.Combine(Output, name + ".png")); encoder.Save(file);
    }

    private static async Task RequiredBoundsAndDismiss(Dictionary<string, bool> proof)
    {
        using var handler = new FakeHandler(); using var client = new StaffAuthClient(Config(), handler);
        var window = Window(client, Context(), true); await Idle();
        var dpi = VisualTreeHelper.GetDpi(window);
        Check(proof, "session-mode-explicit", _interactiveSession || _allowNoninteractive);
        Check(proof, "covers-cashier-monitor", Math.Abs(window.Left - _screen.Bounds.Left / dpi.DpiScaleX) < 2 &&
            Math.Abs(window.Top - _screen.Bounds.Top / dpi.DpiScaleY) < 2 &&
            Math.Abs(window.ActualWidth - _screen.Bounds.Width / dpi.DpiScaleX) < 2 &&
            Math.Abs(window.ActualHeight - _screen.Bounds.Height / dpi.DpiScaleY) < 2);
        Check(proof, "topmost-borderless", window.Topmost && window.WindowStyle == WindowStyle.None && window.ResizeMode == ResizeMode.NoResize);
        SaveOwnWindow(window, "01-required-login");
        window.Close(); await Idle(); Check(proof, "ordinary-close-is-cancelled", window.IsVisible);
        window.WindowState = WindowState.Minimized; await Idle();
        Check(proof, "ordinary-minimize-is-reversed", window.WindowState == WindowState.Normal && window.IsVisible);
        window.Dismiss(); await Idle(); Check(proof, "parent-dismiss-closes", !window.IsVisible);
        Check(proof, "no-http-requests", handler.TotalCalls == 0);
    }

    private static async Task RegisterAndClose(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var process = state.ProcessInstanceId!;
        state.Observe(new("resolved", process), new("resolved", new StaffObservedSession(process, "DEV-USER", "DEV-SESSION", "DEV-WS", FixtureIin, "available")));
        var context = state.Context!; var generation = state.Generation;
        using var handler = new FakeHandler { DeviceContext = context }; using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state, registrationSmsRequired: false); var parentAccepted = false;
        window.Authenticated += response => { parentAccepted = state.Accept(generation, context, response, DateTimeOffset.UtcNow); if (parentAccepted) window.Dismiss(); };
        RegistrationFields(window); SaveOwnWindow(window, "02-registration-ready");
        Click(Field<Button>(window, "_submit")); await Until(() => !window.IsVisible || !Busy(window));
        Check(proof, "four-character-password-is-accepted", FixturePassword.Length == 4 && handler.DirectBodyMatched);
        Check(proof, "exact-source-bound-device-context-is-used", handler.DeviceBodyMatched && context.StandardNContext != null);
        Check(proof, "parent-generation-context-grant-accepted", parentAccepted && state.IsAuthenticated(DateTimeOffset.UtcNow));
        Check(proof, "successful-registration-closes-window", !window.IsVisible);
        Check(proof, "inputs-cleared-after-close", Field<TextBox>(window, "_iin").Text.Length == 0 && Field<TextBox>(window, "_phone").Text.Length == 0 &&
            Field<PasswordBox>(window, "_password").Password.Length == 0 && Field<PasswordBox>(window, "_passwordConfirmation").Password.Length == 0);
        Check(proof, "only-one-mocked-request-and-no-registration-sms", handler.TotalCalls == 1 && handler.WithoutSmsCalls == 1 && handler.OtpCalls == 0 && handler.DeviceRegisterCalls == 0 && handler.RegisterCalls == 0 && handler.UnexpectedCalls == 0);
    }

    private static async Task WrongContext(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var context = state.Context!;
        using var handler = new FakeHandler { DeviceContext = context, WrongRegistrationContext = true }; using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state, registrationSmsRequired: false);
        var eventSeen = false; window.Authenticated += _ => eventSeen = true;
        RegistrationFields(window); Click(Field<Button>(window, "_submit")); await Until(() => handler.WithoutSmsCalls == 1 && !Busy(window));
        Check(proof, "foreign-context-never-authenticated", !eventSeen && state.Grant == null);
        Check(proof, "foreign-context-does-not-close-or-clear-password", window.IsVisible && Field<PasswordBox>(window, "_password").Password == FixturePassword && Field<PasswordBox>(window, "_passwordConfirmation").Password == FixturePassword);
    }

    private static async Task ServiceFailure(bool sms, Dictionary<string, bool> proof)
    {
        using var handler = new FakeHandler { UnavailableLogin = !sms, UnavailableOtp = sms }; using var client = new StaffAuthClient(Config(), handler);
        var context = Context(); var state = State(context); var policy = new StaffPromptPolicy(); var window = Window(client, context, true);
        StaffPromptBypassReason? reason = null; var eventSeen = false;
        window.Authenticated += _ => eventSeen = true;
        window.BypassRequested += value => { reason = value; policy.Bypass(state, value); window.ReleaseWork(); };
        if (sms) { RecoveryFields(window); Click(Field<Button>(window, "_request")); }
        else { Field<TextBox>(window, "_iin").Text = FixtureIin; Field<PasswordBox>(window, "_password").Password = FixturePassword; Click(Field<Button>(window, "_submit")); }
        await Until(() => !window.IsVisible || !Busy(window));
        Check(proof, "service-failure-reports-safe-bypass", reason == (sms ? StaffPromptBypassReason.SmsUnavailable : StaffPromptBypassReason.ServiceUnavailable));
        Check(proof, "service-bypass-retains-form-and-releases-presentation", window.IsVisible && window.WindowStyle != WindowStyle.None);
        Check(proof, "bypass-does-not-create-grant", !eventSeen && state.Grant == null && !state.IsAuthenticated(DateTimeOffset.UtcNow));
        Check(proof, "bypass-records-operational-status", policy.BypassStatus(state.Generation) == (sms ? "staff_bypass_sms_unavailable" : "staff_bypass_service_unavailable"));
    }

    private static async Task SmsWait(Dictionary<string, bool> proof)
    {
        using var handler = new FakeHandler(); using var client = new StaffAuthClient(Config(), handler);
        var context = Context(); var state = State(context); var policy = new StaffPromptPolicy(); var window = Window(client, context, true);
        window.BypassRequested += reason => { policy.Bypass(state, reason); window.Dismiss(); };
        RecoveryFields(window); await RequestOtp(window, handler);
        var bypass = Field<Button>(window, "_continueWithoutSignIn");
        Check(proof, "sms-wait-choice-is-visible", bypass.Visibility == Visibility.Visible && bypass.IsEnabled);
        Click(bypass); await Idle();
        Check(proof, "sms-wait-closes-without-grant", !window.IsVisible && state.Grant == null && policy.BypassStatus(state.Generation) == "staff_bypass_sms_wait");
    }

    private static async Task UnknownContext(Dictionary<string, bool> proof)
    {
        using var handler = new FakeHandler(); using var client = new StaffAuthClient(Config(), handler);
        var state = new StaffSessionState(); state.Observe(new("no_session")); var policy = new StaffPromptPolicy();
        Check(proof, "unknown-session-does-not-require-prompt", !policy.RequiresSignIn(state.Observation, state.Generation, false));
        var window = Window(client, null, true); await Idle();
        Check(proof, "unknown-session-cannot-be-forced-into-fullscreen", window.WindowStyle != WindowStyle.None);
        Check(proof, "unknown-session-login-is-disabled", !Field<Button>(window, "_submit").IsEnabled);
        Check(proof, "unknown-session-retains-registration", Field<Button>(window, "_newStaff").IsEnabled);
        window.Close(); await Idle(); Check(proof, "unknown-session-window-can-close", !window.IsVisible && state.Grant == null);
        Check(proof, "no-http-requests", handler.TotalCalls == 0);
    }

    private static async Task InputGeometry(Dictionary<string, bool> proof)
    {
        using var handler = new FakeHandler(); using var client = new StaffAuthClient(Config(), handler);
        var window = Window(client, Context(), false);
        window.Width = 720; window.Height = 520;
        RegistrationFields(window); Field<TextBox>(window, "_code").Text = "5445";
        await Idle(); window.UpdateLayout();
        Check(proof, "compact-layout-hides-brand-instead-of-cropping-form", Field<Border>(window, "_brandPanel").Visibility == Visibility.Collapsed);
        foreach (var name in new[] { "_iin", "_name", "_phone", "_password", "_passwordConfirmation" })
        {
            var input = Field<Control>(window, name);
            input.BringIntoView(); await Idle(); window.UpdateLayout(); input.ApplyTemplate();
            var host = input.Template.FindName("PART_ContentHost", input) as ScrollViewer;
            Check(proof, name + "-native-host-retained", host != null);
            Check(proof, name + "-padding-applied-once", host!.Margin == new Thickness(0));
            Check(proof, name + "-text-has-enough-height", host.ActualHeight - host.Padding.Top - host.Padding.Bottom >= input.FontSize * 1.2);
        }
        RecoveryFields(window); Field<TextBox>(window, "_code").Text = "5445";
        var code = Field<TextBox>(window, "_code"); code.BringIntoView(); await Idle(); code.ApplyTemplate();
        var codeHost = (ScrollViewer)code.Template.FindName("PART_ContentHost", code);
        Check(proof, "recovery-code-text-has-enough-height", codeHost.ActualHeight - codeHost.Padding.Top - codeHost.Padding.Bottom >= code.FontSize * 1.2);
        Click(Field<Button>(window, "_languageKk"));
        Check(proof, "kazakh-form-keeps-four-character-policy", Field<TextBlock>(window, "_passwordHint").Text.Contains("4–128", StringComparison.Ordinal));
        Check(proof, "language-change-retains-entered-password", Field<PasswordBox>(window, "_password").Password == FixturePassword);
        SaveOwnWindow(window, "03-recovery-compact-kazakh");
        Check(proof, "presentation-never-sends-requests", handler.TotalCalls == 0);
    }

    private static StaffAuthContext NameContext() => Context() with {
        StandardNIin = null, StandardNIinStatus = "missing", StandardNFullName = "Иванов Иван", StandardNFullNameStatus = "available",
    };

    private static async Task FullNameLogin(Dictionary<string, bool> proof)
    {
        var context = NameContext(); var state = State(context); var policy = new StaffPromptPolicy();
        using var handler = new FakeHandler { ObservedContext = context }; using var client = new StaffAuthClient(Config(), handler);
        Check(proof, "no-source-iin-is-invented", context.StandardNIin == null);
        Check(proof, "actual-full-name-allows-presentation", policy.RequiresSignIn(state.Observation, state.Generation, false));
        var window = Window(client, context, true); await Idle();
        Check(proof, "name-only-context-is-fullscreen", window.WindowStyle == WindowStyle.None && window.Topmost);
        var accepted = false;
        window.Authenticated += response => {
            accepted = policy.AcceptAuthentication(state, state.Generation, state.Observation.Session!.Key, context, response, DateTimeOffset.UtcNow);
            if (accepted) window.Dismiss();
        };
        Field<TextBox>(window, "_iin").Text = FixtureIin; Field<PasswordBox>(window, "_password").Password = FixturePassword;
        SaveOwnWindow(window, "04-full-name-fallback-login");
        Click(Field<Button>(window, "_submit")); await Until(() => !window.IsVisible || !Busy(window));
        Check(proof, "fresh-context-grant-is-accepted", accepted && state.IsAuthenticated(DateTimeOffset.UtcNow));
        Check(proof, "name-fallback-login-closes-window", !window.IsVisible);
        Check(proof, "authentication-still-uses-iin-password", handler.LoginContextMatched);
    }

    private static async Task ChangedFullName(Dictionary<string, bool> proof)
    {
        var context = NameContext(); var state = State(context); var policy = new StaffPromptPolicy();
        using var handler = new FakeHandler { ObservedContext = context }; using var client = new StaffAuthClient(Config(), handler);
        var window = Window(client, context, true); var generation = state.Generation; var key = state.Observation.Session!.Key;
        var eventSeen = false; var accepted = false;
        window.Authenticated += response => {
            eventSeen = true;
            state.Observe(state.Observation with { Session = state.Observation.Session! with { StandardNFullName = "Петров Петр" } });
            accepted = policy.AcceptAuthentication(state, generation, key, context, response, DateTimeOffset.UtcNow);
            if (accepted) window.Dismiss();
        };
        Field<TextBox>(window, "_iin").Text = FixtureIin; Field<PasswordBox>(window, "_password").Password = FixturePassword;
        Click(Field<Button>(window, "_submit")); await Until(() => eventSeen && !Busy(window));
        Check(proof, "source-name-change-rejects-old-grant", !accepted && !state.IsAuthenticated(DateTimeOffset.UtcNow));
        Check(proof, "unconfirmed-window-is-not-released-by-old-grant", window.IsVisible);
    }

    private static StaffDeviceSessionState DeviceState()
    {
        var state = new StaffDeviceSessionState();
        state.Observe(StaffProcessPresence.FromInstances(["443:639295055776695955"]), new("database_unavailable"));
        return state;
    }
    private static StaffAuthWindow DeviceWindow(StaffAuthClient client, StaffDeviceSessionState state, bool registrationSmsRequired = true)
    {
        var context = state.Context!;
        var window = new StaffAuthWindow(client, context.StandardNContext, _screen, requireSignIn: true, deviceContext: context, registrationSmsRequired: registrationSmsRequired);
        state.MarkPrompted(); Windows.Add(window); window.Show(); return window;
    }
    private static void FillLogin(StaffAuthWindow window)
    {
        Field<TextBox>(window, "_iin").Text = FixtureIin;
        Field<PasswordBox>(window, "_password").Password = FixturePassword;
    }
    private static async Task DeviceLogin(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var context = state.Context!; var generation = state.Generation;
        using var handler = new FakeHandler { DeviceContext = context }; using var client = new StaffAuthClient(Config(), handler);
        Check(proof, "real-process-presence-policy-prompts-without-db", state.NeedsPrompt(DateTimeOffset.UtcNow));
        var window = DeviceWindow(client, state); await Idle();
        Check(proof, "source-unavailable-window-is-ordinary-and-closable", window.WindowStyle != WindowStyle.None);
        Check(proof, "own-login-is-enabled-without-source-ids", Field<Button>(window, "_submit").IsEnabled && context.StandardNContext == null);
        Check(proof, "unknown-source-is-explained-without-denying-login", Field<TextBlock>(window, "_status").Text.Contains("Вход ePharm доступен", StringComparison.Ordinal));
        Check(proof, "continue-work-is-always-visible", Field<Button>(window, "_continueWithoutSignIn").Visibility == Visibility.Visible);
        var accepted = false;
        window.Authenticated += response => { accepted = state.Accept(generation, context, response, DateTimeOffset.UtcNow); if (accepted) window.Dismiss(); };
        FillLogin(window); SaveOwnWindow(window, "05-process-login-unknown-source");
        Click(Field<Button>(window, "_submit")); await Until(() => !window.IsVisible || !Busy(window));
        Check(proof, "four-character-password-uses-device-login-only", handler.DeviceBodyMatched && handler.DeviceLoginCalls == 1 && handler.UnexpectedCalls == 0);
        Check(proof, "own-login-grant-closes-window", accepted && !window.IsVisible && state.IsAuthenticated(DateTimeOffset.UtcNow));
        foreach (var status in new[] { "ambiguous_session", "no_session", "database_unavailable" })
            state.Observe(new("resolved", context.ProcessInstanceId), new(status));
        state.Observe(new("resolved", context.ProcessInstanceId), new("resolved", new StaffObservedSession(context.ProcessInstanceId, "DEV-SOURCE-USER", "DEV-SOURCE-SESSION", "DEV-SOURCE-WS")));
        Check(proof, "late-source-binding-keeps-login-without-another-popup", state.Generation == generation && state.IsAuthenticated(DateTimeOffset.UtcNow) && !state.NeedsPrompt(DateTimeOffset.UtcNow));
    }
    private static async Task DeviceRegister(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var context = state.Context!; var generation = state.Generation;
        using var handler = new FakeHandler { DeviceContext = context }; using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state, registrationSmsRequired: false); var accepted = false;
        window.Authenticated += response => { accepted = state.Accept(generation, context, response, DateTimeOffset.UtcNow); if (accepted) window.Dismiss(); };
        RegistrationFields(window);
        Click(Field<Button>(window, "_submit")); await Until(() => !window.IsVisible || !Busy(window));
        Check(proof, "registration-uses-no-sms-and-four-character-password", handler.WithoutSmsCalls == 1 && handler.OtpCalls == 0 && handler.DeviceRegisterCalls == 0 && handler.DeviceBodyMatched);
        Check(proof, "registration-without-source-ids-autocloses-after-parent-acceptance", accepted && !window.IsVisible && state.IsAuthenticated(DateTimeOffset.UtcNow));
        Check(proof, "registration-clears-password-and-sms-controls", Field<PasswordBox>(window, "_password").Password.Length == 0 && Field<TextBox>(window, "_code").Text.Length == 0);
    }
    private static async Task DeviceDismiss(bool continueWork, Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var context = state.Context!;
        using var handler = new FakeHandler { DeviceContext = context }; using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state);
        window.BypassRequested += reason => { state.Bypass(reason); window.Dismiss(); };
        window.Closed += (_, _) => { if (state.BypassStatus == null) state.Bypass(StaffPromptBypassReason.IdentityUnavailable); };
        if (continueWork) Click(Field<Button>(window, "_continueWithoutSignIn")); else window.Close();
        await Idle();
        state.Observe(new("resolved", context.ProcessInstanceId), new("ambiguous_session"));
        state.Observe(new("resolved", context.ProcessInstanceId), new("database_unavailable"));
        Check(proof, "ordinary-window-close-releases-work-without-a-grant", !window.IsVisible && state.Grant == null);
        Check(proof, "source-flaps-do-not-reopen-a-dismissed-window", !state.NeedsPrompt(DateTimeOffset.UtcNow) && state.BypassStatus == "staff_bypass_identity_unavailable");
        Check(proof, "dismissal-does-not-send-http", handler.TotalCalls == 0);
    }
    private static async Task DeviceChangedProcess(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var context = state.Context!; var generation = state.Generation;
        using var handler = new FakeHandler { DeviceContext = context, BeforeDeviceReply = () => state.Observe(
            StaffProcessPresence.FromInstances(["443:639295055776695956"]), new("database_unavailable")) };
        using var client = new StaffAuthClient(Config(), handler); var window = DeviceWindow(client, state);
        var eventSeen = false; var accepted = false;
        window.Authenticated += response => { eventSeen = true; accepted = state.Accept(generation, context, response, DateTimeOffset.UtcNow); if (accepted) window.Dismiss(); };
        FillLogin(window); Click(Field<Button>(window, "_submit")); await Until(() => eventSeen && !Busy(window));
        Check(proof, "pid-reuse-with-new-start-time-rejects-late-login", !accepted && state.Grant == null && state.Generation != generation);
        Check(proof, "foreign-process-grant-does-not-release-current-presentation", window.IsVisible);
    }
    private static async Task DeviceServiceFailure(bool sms, Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var context = state.Context!;
        using var handler = new FakeHandler { DeviceContext = context, UnavailableLogin = !sms, UnavailableOtp = sms };
        using var client = new StaffAuthClient(Config(), handler); var window = DeviceWindow(client, state);
        window.BypassRequested += reason => { state.Bypass(reason); window.ReleaseWork(); };
        if (sms) { RecoveryFields(window); Click(Field<Button>(window, "_request")); }
        else { FillLogin(window); Click(Field<Button>(window, "_submit")); }
        await Until(() => !window.IsVisible || !Busy(window));
        Check(proof, "outage-retains-form-without-a-grant", window.IsVisible && state.Grant == null && window.WindowStyle != WindowStyle.None);
        Check(proof, "outage-keeps-safe-operational-status", state.BypassStatus == (sms ? "staff_bypass_sms_unavailable" : "staff_bypass_service_unavailable"));
        Check(proof, "outage-does-not-immediately-reopen", !state.NeedsPrompt(DateTimeOffset.UtcNow));
    }

    private static async Task SmsSourceFlap(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var process = state.ProcessInstanceId!;
        state.Observe(new("resolved", process), new("resolved", new StaffObservedSession(process, "DEV-SOURCE-USER", "DEV-SOURCE-SESSION", "DEV-SOURCE-WS", FixtureIin, "available")));
        var context = state.Context!; var generation = state.Generation;
        using var handler = new FakeHandler { DeviceContext = context,
            BeforeOtpReply = () => state.Observe(new("resolved", process), new("database_unavailable")) };
        using var client = new StaffAuthClient(Config(), handler); var window = DeviceWindow(client, state);
        RecoveryFields(window); await RequestOtp(window, handler);
        Check(proof, "sms-success-keeps-the-filled-window-open", window.IsVisible && Field<TextBox>(window, "_iin").Text == FixtureIin &&
            Field<TextBox>(window, "_phone").Text == FixturePhone && Field<PasswordBox>(window, "_password").Password == FixturePassword);
        Check(proof, "source-flap-keeps-generation-and-valid-sms-challenge", state.Generation == generation &&
            typeof(StaffAuthWindow).GetField("_challenge", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(window) != null);
        var challenge = typeof(StaffAuthWindow).GetField("_challenge", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(window);
        state.Bypass(StaffPromptBypassReason.ServiceUnavailable); window.ReleaseWork("DEV: temporary capability outage; form retained.");
        Check(proof, "later-service-release-keeps-sms-challenge-and-filled-form", window.IsVisible && window.WindowStyle != WindowStyle.None &&
            Equals(challenge, typeof(StaffAuthWindow).GetField("_challenge", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(window)) &&
            Field<PasswordBox>(window, "_password").Password == FixturePassword);
        var accepted = false; window.Authenticated += response => { accepted = state.Accept(generation, context, response, DateTimeOffset.UtcNow); if (accepted) window.Dismiss(); };
        Field<TextBox>(window, "_code").Text = "5445";
        Click(Field<Button>(window, "_submit")); await Until(() => !window.IsVisible || !Busy(window));
        Check(proof, "recovery-after-source-outage-saves-password-without-forging-login", !accepted && state.Grant == null && window.IsVisible && handler.PasswordChangeCalls == 1 && handler.LastOtpPurpose == "reset" && Field<TextBlock>(window, "_status").Text.Contains("Пароль сохранён", StringComparison.Ordinal));
    }
    private static async Task SmsRetry(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var context = state.Context!; var generation = state.Generation;
        using var handler = new FakeHandler { DeviceContext = context, OtpFailuresBeforeSuccess = 1 };
        using var client = new StaffAuthClient(Config(), handler); var window = DeviceWindow(client, state);
        window.BypassRequested += reason => { state.Bypass(reason); window.ReleaseWork(); };
        window.AuthenticationStarted += () => generation = state.Generation;
        var accepted = false; window.Authenticated += response => { accepted = state.Accept(generation, context, response, DateTimeOffset.UtcNow); if (accepted) window.Dismiss(); };
        RecoveryFields(window); Click(Field<Button>(window, "_request")); await Until(() => handler.OtpCalls == 1 && !Busy(window));
        Check(proof, "sms-failure-keeps-all-entered-registration-fields", window.IsVisible &&
            Field<TextBox>(window, "_iin").Text == FixtureIin && Field<TextBox>(window, "_phone").Text == FixturePhone &&
            Field<PasswordBox>(window, "_password").Password == FixturePassword && Field<PasswordBox>(window, "_passwordConfirmation").Password == FixturePassword);
        Check(proof, "sms-failure-releases-work-without-pretending-login", state.Grant == null &&
            state.BypassStatus == "staff_bypass_sms_unavailable" && !state.NeedsPrompt(DateTimeOffset.UtcNow) &&
            Field<Button>(window, "_continueWithoutSignIn").Visibility == Visibility.Visible);
        Click(Field<Button>(window, "_request")); await Until(() => handler.OtpCalls == 2 && !Busy(window));
        Field<TextBox>(window, "_code").Text = "5445";
        Click(Field<Button>(window, "_submit")); await Until(() => !window.IsVisible || !Busy(window));
        Check(proof, "retry-on-same-retained-form-recovers-password-without-authentication", !accepted && window.IsVisible && state.Grant == null && handler.PasswordChangeCalls == 1 && handler.LastOtpPurpose == "reset" && Field<TextBlock>(window, "_status").Text.Contains("Пароль сохранён", StringComparison.Ordinal));
    }

    private static async Task DevicePending(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var process = state.ProcessInstanceId!;
        state.Observe(new("resolved", process), new("resolved", new StaffObservedSession(process, "DEV-SOURCE-USER", "DEV-SOURCE-SESSION", "DEV-SOURCE-WS", FixtureIin, "available")));
        var context = state.Context!;
        using var handler = new FakeHandler { DeviceContext = context, PendingDeviceLogin = true };
        using var client = new StaffAuthClient(Config(), handler); var window = DeviceWindow(client, state);
        var authenticated = false; window.Authenticated += _ => authenticated = true;
        Check(proof, "known-source-initially-uses-required-presentation", window.WindowStyle == WindowStyle.None);
        FillLogin(window); Click(Field<Button>(window, "_submit")); await Until(() => handler.DeviceLoginCalls == 1 && !Busy(window));
        Check(proof, "pending-profile-does-not-emit-authentication-or-grant", !authenticated && state.Grant == null && !state.IsAuthenticated(DateTimeOffset.UtcNow));
        Check(proof, "pending-keeps-the-form-open-and-releases-work", window.IsVisible && window.WindowStyle != WindowStyle.None &&
            Field<Button>(window, "_continueWithoutSignIn").Visibility == Visibility.Visible && Field<Button>(window, "_continueWithoutSignIn").IsEnabled);
        Check(proof, "pending-explanation-is-preserved", Field<TextBlock>(window, "_status").Text.Contains("Назначение аптеки для этого профиля пока не подтверждено", StringComparison.Ordinal));
        window.Closed += (_, _) => state.Bypass(StaffPromptBypassReason.IdentityUnavailable);
        window.Close(); await Idle();
        Check(proof, "pending-profile-can-close-without-forging-login", !window.IsVisible && !state.IsAuthenticated(DateTimeOffset.UtcNow) && !state.NeedsPrompt(DateTimeOffset.UtcNow));
    }

    private static async Task RegistrationNeverSms(Dictionary<string, bool> proof)
    {
        foreach (var device in new[] { false, true })
            foreach (var required in new[] { true, false })
            {
                var state = DeviceState();
                using var handler = new FakeHandler { DeviceContext = device ? state.Context : null };
                using var client = new StaffAuthClient(Config(), handler);
                StaffAuthWindow window;
                if (device) window = DeviceWindow(client, state, registrationSmsRequired: required);
                else {
                    window = new StaffAuthWindow(client, Context(), _screen, registrationSmsRequired: required);
                    Windows.Add(window); window.Show();
                }
                Click(Field<Button>(window, "_newStaff")); await Idle();
                var prefix = $"device-{device}-policy-{required}-";
                Check(proof, prefix + "new-employee-link-opens-registration", Field<ComboBox>(window, "_mode").SelectedIndex == 1 && Field<StackPanel>(window, "_nameRow").Visibility == Visibility.Visible);
                Check(proof, prefix + "registration-never-shows-sms", Field<Button>(window, "_request").Visibility == Visibility.Collapsed && !Field<Button>(window, "_request").IsEnabled && Field<StackPanel>(window, "_codeRow").Visibility == Visibility.Collapsed);
                RegistrationFields(window);
                if (!device || required)
                {
                    Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
                    Check(proof, prefix + "unsupported-registration-keeps-data-and-work", window.IsVisible && window.WindowStyle != WindowStyle.None && Field<PasswordBox>(window, "_password").Password == FixturePassword && Field<PasswordBox>(window, "_passwordConfirmation").Password == FixturePassword && Field<TextBlock>(window, "_status").Text.Contains("Регистрация без SMS пока недоступна", StringComparison.Ordinal));
                    Check(proof, prefix + "unsupported-does-not-call-any-registration-or-otp", handler.TotalCalls == 0 && state.Grant == null);
                }
                Click(Field<Button>(window, "_back")); await Idle();
                Check(proof, prefix + "known-user-login-and-navigation-remain", Field<ComboBox>(window, "_mode").SelectedIndex == 0 && Field<Button>(window, "_newStaff").IsEnabled && Field<Button>(window, "_forgot").IsEnabled && Field<Button>(window, "_submit").IsEnabled);
                Check(proof, prefix + "intentional-navigation-clears-password", Field<PasswordBox>(window, "_password").Password.Length == 0 && Field<PasswordBox>(window, "_passwordConfirmation").Password.Length == 0);
                window.Close(); await Idle();
            }
    }

    private static async Task RegistrationPasswordRetention(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var context = state.Context!; var generation = state.Generation;
        using var handler = new FakeHandler { DeviceContext = context,
            DirectFailureStatuses = [HttpStatusCode.BadRequest, HttpStatusCode.Conflict, HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable] };
        using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state, registrationSmsRequired: false); RegistrationFields(window);
        var accepted = false;
        window.AuthenticationStarted += () => generation = state.Generation;
        window.BypassRequested += reason => state.Bypass(reason);
        window.Authenticated += response => { accepted = state.Accept(generation, context, response, DateTimeOffset.UtcNow); if (accepted) window.Dismiss(); };
        Field<TextBox>(window, "_name").Text = "";
        Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "local-name-error-retains-passwords-without-request", handler.TotalCalls == 0 && Field<PasswordBox>(window, "_password").Password == FixturePassword && Field<PasswordBox>(window, "_passwordConfirmation").Password == FixturePassword);
        Field<TextBox>(window, "_name").Text = "DEV synthetic employee";
        Field<PasswordBox>(window, "_passwordConfirmation").Password = "4567";
        Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "local-mismatch-preserves-each-entered-password", handler.TotalCalls == 0 && Field<PasswordBox>(window, "_password").Password == FixturePassword && Field<PasswordBox>(window, "_passwordConfirmation").Password == "4567");
        Field<PasswordBox>(window, "_password").Password = "12"; Field<PasswordBox>(window, "_passwordConfirmation").Password = "12";
        Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "local-length-error-retains-passwords-without-request", handler.TotalCalls == 0 && Field<PasswordBox>(window, "_password").Password == "12" && Field<PasswordBox>(window, "_passwordConfirmation").Password == "12");
        Field<PasswordBox>(window, "_password").Password = FixturePassword; Field<PasswordBox>(window, "_passwordConfirmation").Password = FixturePassword;
        for (var index = 0; index < handler.DirectFailureStatuses.Length; index++)
        {
            Click(Field<Button>(window, "_submit")); await Until(() => handler.WithoutSmsCalls == index + 1 && !Busy(window));
            Check(proof, $"http-{(int)handler.DirectFailureStatuses[index]}-retains-passwords-and-identity", window.IsVisible && !accepted && state.Grant == null && Field<PasswordBox>(window, "_password").Password == FixturePassword && Field<PasswordBox>(window, "_passwordConfirmation").Password == FixturePassword && Field<TextBox>(window, "_iin").Text == FixtureIin && Field<TextBox>(window, "_phone").Text == FixturePhone);
            Check(proof, $"http-{(int)handler.DirectFailureStatuses[index]}-no-automatic-retry-or-otp", handler.TotalCalls == index + 1 && handler.OtpCalls == 0 && handler.RegisterCalls == 0 && handler.DeviceRegisterCalls == 0);
        }
        Click(Field<Button>(window, "_submit")); await Until(() => !window.IsVisible || !Busy(window));
        Check(proof, "same-form-retry-succeeds-without-reentering-password", accepted && !window.IsVisible && state.IsAuthenticated(DateTimeOffset.UtcNow) && handler.WithoutSmsCalls == 5 && handler.DirectBodyMatched);
        Check(proof, "successful-close-clears-both-passwords", Field<PasswordBox>(window, "_password").Password.Length == 0 && Field<PasswordBox>(window, "_passwordConfirmation").Password.Length == 0);
    }

    private static async Task DirectRegistration(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var context = state.Context!; var generation = state.Generation;
        using var handler = new FakeHandler { DeviceContext = context, DirectFailuresBeforeSuccess = 1 }; using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state, registrationSmsRequired: false); RegistrationFields(window); await Idle();
        Check(proof, "direct-registration-hides-sms-controls", Field<Button>(window, "_request").Visibility == Visibility.Collapsed &&
            !Field<Button>(window, "_request").IsEnabled && Field<StackPanel>(window, "_codeRow").Visibility == Visibility.Collapsed);
        Check(proof, "phone-is-explicitly-unverified", Field<TextBlock>(window, "_intro").Text.Contains("номер телефона пока не подтверждён", StringComparison.Ordinal));
        var accepted = false;
        window.AuthenticationStarted += () => generation = state.Generation;
        window.Authenticated += response => { accepted = state.Accept(generation, context, response, DateTimeOffset.UtcNow); if (accepted) window.Dismiss(); };
        SaveOwnWindow(window, "06-registration-without-sms");
        Click(Field<Button>(window, "_submit")); await Until(() => handler.WithoutSmsCalls == 1 && !Busy(window));
        var error = Field<TextBlock>(window, "_status").Text;
        Check(proof, "direct-generic400-keeps-form-without-asking-for-sms-or-code", window.IsVisible && !accepted && state.Grant == null &&
            error.Contains("Проверьте ИИН", StringComparison.Ordinal) && !error.Contains("SMS", StringComparison.OrdinalIgnoreCase) && !error.Contains("код", StringComparison.OrdinalIgnoreCase));
        Check(proof, "direct-generic400-keeps-identity-and-zero-otp", Field<TextBox>(window, "_iin").Text == FixtureIin &&
            Field<TextBox>(window, "_phone").Text == FixturePhone && handler.OtpCalls == 0 && handler.DeviceRegisterCalls == 0);
        Check(proof, "direct400-preserves-both-passwords-for-retry", Field<PasswordBox>(window, "_password").Password == FixturePassword && Field<PasswordBox>(window, "_passwordConfirmation").Password == FixturePassword);
        Click(Field<Button>(window, "_submit")); await Until(() => !window.IsVisible || !Busy(window));
        Check(proof, "direct-route-has-no-code-challenge-or-sms-request", handler.WithoutSmsCalls == 2 && handler.OtpCalls == 0 && handler.DeviceRegisterCalls == 0 && handler.DirectBodyMatched);
        Check(proof, "direct-registration-closes-only-after-current-grant", accepted && state.IsAuthenticated(DateTimeOffset.UtcNow) && !window.IsVisible);
        Check(proof, "direct-registration-clears-password-controls", Field<PasswordBox>(window, "_password").Password.Length == 0 && Field<PasswordBox>(window, "_passwordConfirmation").Password.Length == 0);
    }
    private static async Task BackgroundCapabilityOutage(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var context = state.Context!; var generation = state.Generation;
        StaffAuthWindow? window = null;
        using var handler = new FakeHandler { DeviceContext = context, BeforeDeviceReply = () => {
            foreach (var status in new[] { "network_unavailable", "endpoint_unavailable" }) {
                var failure = new StaffCapabilityCheck(false, status);
                if (StaffAuthDiagnostics.RegistrationPolicy(failure) is { } required) window!.SetRegistrationSmsRequired(required);
                Check(proof, "registration-is-still-pending-during-background-failure", window!.IsRequestPending);
                state.Bypass(StaffPromptBypassReason.ServiceUnavailable, preservePendingRequest: window.IsRequestPending);
                window.ReleaseWork();
                Check(proof, "background-failure-does-not-create-a-grant", state.Grant == null && state.Generation == generation);
            }
        } };
        using var client = new StaffAuthClient(Config(), handler);
        window = DeviceWindow(client, state, registrationSmsRequired: false); RegistrationFields(window);
        var accepted = false;
        window.Authenticated += response => { accepted = state.Accept(generation, context, response, DateTimeOffset.UtcNow); if (accepted) window.Dismiss(); };
        Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "healthy-registration-survives-both-failed-capability-polls", accepted && !window.IsVisible && state.IsAuthenticated(DateTimeOffset.UtcNow));
        Check(proof, "no-policy-flip-or-replayed-registration", handler.WithoutSmsCalls == 1 && handler.OtpCalls == 0 && handler.DeviceRegisterCalls == 0 && handler.DeviceLogoutCalls == 0);
    }
    private static async Task RateLimited(bool login, Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var process = state.ProcessInstanceId!;
        state.Observe(new("resolved", process), new("resolved", new StaffObservedSession(process, "DEV-USER", "DEV-SESSION", "DEV-WS", FixtureIin, "available")));
        using var handler = new FakeHandler { DeviceContext = state.Context, RateLimitedRequests = true };
        using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state, registrationSmsRequired: false);
        if (login) FillLogin(window); else RegistrationFields(window);
        Check(proof, "known-source-starts-required-presentation", window.WindowStyle == WindowStyle.None);
        var bypassed = false; var authenticated = false;
        window.BypassRequested += _ => bypassed = true; window.Authenticated += _ => authenticated = true;
        Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        var message = Field<TextBlock>(window, "_status").Text;
        Check(proof, "rate-limit-is-not-reported-as-service-failure", message.Contains("Слишком много попыток", StringComparison.Ordinal) &&
            !message.Contains("недоступ", StringComparison.OrdinalIgnoreCase) && !bypassed);
        Check(proof, "rate-limit-keeps-form-without-authorizing", window.IsVisible && !authenticated && state.Grant == null && Field<TextBox>(window, "_iin").Text == FixtureIin);
        Check(proof, "rate-limit-releases-cashier-work", window.WindowStyle != WindowStyle.None && Field<Button>(window, "_continueWithoutSignIn").Visibility == Visibility.Visible);
        Check(proof, "rate-limit-does-not-retry-or-send-sms", handler.TotalCalls == 1 && handler.OtpCalls == 0 && (login ? handler.DeviceLoginCalls == 1 : handler.WithoutSmsCalls == 1));
        window.Close(); Check(proof, "rate-limited-window-is-closable", !window.IsVisible);
    }
    private static async Task RegistrationPolicyRollback(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var context = state.Context!;
        StaffAuthWindow? window = null;
        using var handler = new FakeHandler { DeviceContext = context, BeforeDeviceReply = () => window!.SetRegistrationSmsRequired(true) };
        using var client = new StaffAuthClient(Config(), handler); window = DeviceWindow(client, state, registrationSmsRequired: false);
        var eventSeen = false; window.Authenticated += _ => eventSeen = true;
        RegistrationFields(window); Click(Field<Button>(window, "_submit")); await Until(() => handler.WithoutSmsCalls == 1 && !Busy(window));
        Check(proof, "changed-registration-policy-does-not-authenticate-old-grant", !eventSeen && state.Grant == null && handler.DeviceLogoutCalls == 1);
        Check(proof, "rollback-never-restores-sms-or-replays-registration", window.IsVisible && Field<Button>(window, "_request").Visibility == Visibility.Collapsed &&
            Field<StackPanel>(window, "_codeRow").Visibility == Visibility.Collapsed && handler.OtpCalls == 0 && handler.WithoutSmsCalls == 1 && handler.DeviceRegisterCalls == 0);
        Check(proof, "rollback-keeps-identity-and-explains-no-duplicate-registration", Field<TextBox>(window, "_iin").Text == FixtureIin &&
            Field<TextBox>(window, "_phone").Text == FixturePhone && Field<TextBlock>(window, "_status").Text.Contains("не регистрируйтесь повторно", StringComparison.Ordinal));
    }
    private static async Task DirectRegistrationRecovery(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var context = state.Context!;
        using var handler = new FakeHandler { DeviceContext = context }; using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state, registrationSmsRequired: false); var eventSeen = false; window.Authenticated += _ => eventSeen = true;
        Field<ComboBox>(window, "_mode").SelectedIndex = 2;
        Field<TextBox>(window, "_iin").Text = FixtureIin; Field<TextBox>(window, "_phone").Text = FixturePhone;
        Field<PasswordBox>(window, "_password").Password = FixturePassword; Field<PasswordBox>(window, "_passwordConfirmation").Password = FixturePassword;
        Check(proof, "recovery-keeps-real-sms-controls", Field<Button>(window, "_request").Visibility == Visibility.Visible && Field<StackPanel>(window, "_codeRow").Visibility == Visibility.Visible);
        await RequestOtp(window, handler);
        var challenge = typeof(StaffAuthWindow).GetField("_challenge", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(window);
        window.SetRegistrationSmsRequired(true); window.SetRegistrationSmsRequired(false);
        Check(proof, "registration-policy-does-not-clear-recovery-challenge", Equals(challenge, typeof(StaffAuthWindow).GetField("_challenge", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(window)));
        Field<TextBox>(window, "_code").Text = "5445"; Click(Field<Button>(window, "_submit")); await Until(() => handler.PasswordChangeCalls == 1 && !Busy(window));
        Check(proof, "recovery-uses-existing-otp-reset-contract-only", handler.LastOtpPurpose == "reset" && handler.OtpCalls == 1 && handler.WithoutSmsCalls == 0 && handler.PasswordChangeBodyMatched);
        Check(proof, "password-reset-does-not-fake-an-authenticated-grant", !eventSeen && state.Grant == null && window.IsVisible);
    }

    private static void WriteProof(bool passed, string? note)
    {
        using var process = Process.GetCurrentProcess();
        File.WriteAllText(Path.Combine(Output, "qa-proof.json"), JsonSerializer.Serialize(new {
            scope = "DEV synthetic StaffAuthWindow only; in-process UI; fake HTTP; no backend, Standard-N, device config or SMS",
            passed, note, createdAt = DateTimeOffset.UtcNow, interactiveSession = _interactiveSession,
            nonInteractiveOptIn = _allowNoninteractive, sessionId = process.SessionId, processId = process.Id, cases = Results,
        }, Json), Encoding.UTF8);
    }
    private sealed record CaseProof(string Name, bool Passed, Dictionary<string, bool> Assertions, string? FailureType);

    private sealed class FakeHandler : HttpMessageHandler
    {
        public StaffAuthContext ObservedContext { get; init; } = Context();
        public StaffDeviceContext? DeviceContext { get; init; }
        public Action? BeforeDeviceReply { get; init; }
        public bool DeviceBodyMatched { get; private set; }
        public int DeviceLoginCalls { get; private set; }
        public int DeviceRegisterCalls { get; private set; }
        public int WithoutSmsCalls { get; private set; }
        public int DirectFailuresBeforeSuccess { get; init; }
        public HttpStatusCode[] DirectFailureStatuses { get; init; } = [];
        public int DeviceLogoutCalls { get; private set; }
        public int PasswordChangeCalls { get; private set; }
        public bool DirectBodyMatched { get; private set; }
        public bool PasswordChangeBodyMatched { get; private set; }
        public string? LastOtpPurpose { get; private set; }
        public bool LoginContextMatched { get; private set; }
        public bool WrongRegistrationContext { get; init; }
        public bool UnavailableLogin { get; init; }
        public bool PendingDeviceLogin { get; init; }
        public bool RateLimitedRequests { get; init; }
        public bool UnavailableOtp { get; init; }
        public int OtpFailuresBeforeSuccess { get; init; }
        public Action? BeforeOtpReply { get; init; }
        public int TotalCalls { get; private set; }
        public int OtpCalls { get; private set; }
        public int RegisterCalls { get; private set; }
        public int UnexpectedCalls { get; private set; }
        public bool RegisterContextMatched { get; private set; }
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            ct.ThrowIfCancellationRequested(); TotalCalls++;
            if (request.Method != HttpMethod.Post || request.RequestUri?.Host != "dev.invalid" || request.RequestUri.Query.Length != 0)
            { UnexpectedCalls++; throw new InvalidOperationException("Unexpected fake request."); }
            if (request.RequestUri.AbsolutePath == "/api/posm/staff/device/logout") {
                DeviceLogoutCalls++;
                if (!request.Headers.Contains("X-Staff-Session")) throw new InvalidOperationException("Missing fake grant header.");
                return new HttpResponseMessage(HttpStatusCode.NoContent);
            }
            var payload = await request.Content!.ReadAsStringAsync(ct); using var body = JsonDocument.Parse(payload);
            switch (request.RequestUri.AbsolutePath)
            {
                case "/api/posm/staff/device/login":
                case "/api/posm/staff/device/register":
                case "/api/posm/staff/device/register-without-sms":
                    var direct = request.RequestUri.AbsolutePath.EndsWith("/register-without-sms", StringComparison.Ordinal);
                    var registering = direct || request.RequestUri.AbsolutePath.EndsWith("/register", StringComparison.Ordinal);
                    if (direct) WithoutSmsCalls++; else if (registering) DeviceRegisterCalls++; else DeviceLoginCalls++;
                    var device = JsonSerializer.Deserialize<StaffDeviceContext>(body.RootElement.GetProperty("deviceContext").GetRawText(), Json);
                    DeviceBodyMatched = device == DeviceContext &&
                        body.RootElement.GetProperty("iin").GetString() == FixtureIin &&
                        body.RootElement.GetProperty("password").GetString() == FixturePassword &&
                        (!registering || body.RootElement.GetProperty("phone").GetString() == FixturePhone);
                    if (direct) DirectBodyMatched = DeviceBodyMatched && body.RootElement.EnumerateObject().Select(x => x.Name).Order().SequenceEqual(
                        new[] { "deviceContext", "iin", "name", "password", "phone" });
                    if (RateLimitedRequests) return Reply(new { code = "OTP_TOO_MANY_ATTEMPTS" }, HttpStatusCode.TooManyRequests);
                    if (direct && WithoutSmsCalls <= DirectFailureStatuses.Length) return Reply(new { code = "SYNTHETIC_ERROR" }, DirectFailureStatuses[WithoutSmsCalls - 1]);
                    if (direct && WithoutSmsCalls <= DirectFailuresBeforeSuccess) return Reply(new { code = "VALIDATION_FAILED" }, HttpStatusCode.BadRequest);
                    if (!registering && UnavailableLogin) return Reply(new { code = "UPSTREAM_UNAVAILABLE" }, HttpStatusCode.ServiceUnavailable);
                    if (!registering && PendingDeviceLogin) return Reply(new StaffAuthResponse("pending", null, null,
                        new StaffIdentity("DEV-PENDING-PERSON", "DEV pending employee"), DeviceContext: device));
                    BeforeDeviceReply?.Invoke();
                    if (registering && WrongRegistrationContext) device = device! with { ProcessInstanceId = "DEV-FOREIGN-PROCESS" };
                    return Reply(new StaffAuthResponse("authenticated", "DEV-SYNTHETIC-PROCESS-GRANT", DateTimeOffset.UtcNow.AddHours(1),
                        new StaffIdentity("DEV-PERSON", "DEV synthetic employee"), DeviceContext: device));
                case "/api/posm/staff/password/reset":
                case "/api/posm/staff/password/activate":
                    PasswordChangeCalls++;
                    PasswordChangeBodyMatched = body.RootElement.GetProperty("iin").GetString() == FixtureIin &&
                        body.RootElement.GetProperty("phone").GetString() == FixturePhone && body.RootElement.GetProperty("password").GetString() == FixturePassword &&
                        body.RootElement.GetProperty("challengeId").GetString() == "DEV-FAKE-CHALLENGE" && body.RootElement.GetProperty("code").GetString() == "5445";
                    return Reply(new StaffPasswordChanged(true));
                case "/api/posm/staff/otp/request":
                    OtpCalls++; LastOtpPurpose = body.RootElement.GetProperty("purpose").GetString();
                    if (UnavailableOtp || OtpCalls <= OtpFailuresBeforeSuccess) return Reply(new { code = "UPSTREAM_UNAVAILABLE" }, HttpStatusCode.ServiceUnavailable);
                    BeforeOtpReply?.Invoke();
                    return Reply(new StaffOtpResponse("DEV-FAKE-CHALLENGE", 300, "+7***0012"));
                case "/api/posm/staff/register":
                    RegisterCalls++;
                    var context = JsonSerializer.Deserialize<StaffAuthContext>(body.RootElement.GetProperty("context").GetRawText(), Json);
                    RegisterContextMatched = context == ObservedContext && body.RootElement.GetProperty("iin").GetString() == FixtureIin &&
                        body.RootElement.GetProperty("phone").GetString() == FixturePhone && body.RootElement.GetProperty("password").GetString() == FixturePassword;
                    if (WrongRegistrationContext) context = context! with { StandardNSessionId = "DEV-DIFFERENT-SESSION" };
                    return Reply(new StaffRegistrationResponse("authenticated", "DEV-SYNTHETIC-GRANT-NOT-A-VALID-TOKEN", DateTimeOffset.UtcNow.AddHours(1), new StaffIdentity("DEV-PERSON", "DEV synthetic employee"), context));
                case "/api/posm/staff/login":
                    var loginContext = JsonSerializer.Deserialize<StaffAuthContext>(body.RootElement.GetProperty("context").GetRawText(), Json);
                    LoginContextMatched = loginContext == ObservedContext && body.RootElement.GetProperty("iin").GetString() == FixtureIin &&
                        body.RootElement.GetProperty("password").GetString() == FixturePassword;
                    if (UnavailableLogin) return Reply(new { code = "UPSTREAM_UNAVAILABLE" }, HttpStatusCode.ServiceUnavailable);
                    return Reply(new StaffAuthResponse("authenticated", "DEV-SYNTHETIC-GRANT-NOT-A-VALID-TOKEN", DateTimeOffset.UtcNow.AddHours(1), new StaffIdentity("DEV-PERSON", "DEV synthetic employee"), ObservedContext));
                default:
                    UnexpectedCalls++; throw new InvalidOperationException("Unexpected fake route.");
            }
        }
        private static HttpResponseMessage Reply(object body, HttpStatusCode code = HttpStatusCode.OK) => new(code) {
            Content = new StringContent(JsonSerializer.Serialize(body, Json), Encoding.UTF8, "application/json"),
        };
    }
}
