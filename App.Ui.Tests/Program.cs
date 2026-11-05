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
        var watchdog = new DispatcherTimer { Interval = TimeSpan.FromSeconds(30) };
        watchdog.Tick += (_, _) => {
            watchdog.Stop();
            Results.Add(new("watchdog", false, new(), "TimeoutException"));
            foreach (var window in Windows) if (window.IsVisible) window.Dismiss();
            WriteProof(false, "Thirty-second UI watchdog expired.");
            application.Shutdown(3);
        };
        application.Startup += async (_, _) => {
            watchdog.Start();
            try
            {
                _screen = Screen.PrimaryScreen ?? throw new InvalidOperationException("No primary display.");
                await Run("required-screen-and-safe-dismiss", RequiredBoundsAndDismiss);
                await Run("registration-grant-closes-after-parent-check", RegisterAndClose);
                await Run("wrong-registration-context-does-not-authenticate", WrongContext);
                await Run("service-error-bypasses-without-grant", proof => ServiceFailure(false, proof));
                await Run("sms-error-bypasses-without-grant", proof => ServiceFailure(true, proof));
                await Run("sms-wait-bypasses-without-grant", SmsWait);
                await Run("unknown-cashier-context-is-closable", UnknownContext);
                await Run("responsive-inputs-preserve-native-text-height", InputGeometry);
                await Run("missing-iin-with-full-name-prompts-and-closes-after-login", FullNameLogin);
                await Run("changed-full-name-rejects-earlier-login-grant", ChangedFullName);
            }
            catch (Exception error)
            {
                Results.Add(new("harness-fatal", false, new(), error.GetType().Name));
            }
            finally
            {
                watchdog.Stop();
                foreach (var window in Windows) if (window.IsVisible) window.Dismiss();
                var success = Results.Count == 10 && Results.All(result => result.Passed);
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
        var context = Context(); var state = State(context); var policy = new StaffPromptPolicy();
        using var handler = new FakeHandler(); using var client = new StaffAuthClient(Config(), handler);
        var window = Window(client, context, true); var generation = state.Generation; var observedKey = state.Observation.Session!.Key;
        var eventSeen = false; var parentAccepted = false;
        window.Authenticated += response => {
            eventSeen = true;
            parentAccepted = policy.AcceptAuthentication(state, generation, observedKey, context, response, DateTimeOffset.UtcNow);
            if (parentAccepted) window.Dismiss();
        };
        RegistrationFields(window); await RequestOtp(window, handler);
        Field<TextBox>(window, "_code").Text = "5445";
        SaveOwnWindow(window, "02-registration-ready");
        Click(Field<Button>(window, "_submit")); await Until(() => !window.IsVisible || !Busy(window));
        Check(proof, "four-character-password-is-accepted", FixturePassword.Length == 4 && handler.RegisterContextMatched);
        Check(proof, "fake-register-request-has-exact-context", handler.RegisterContextMatched);
        Check(proof, "registration-authenticated-event", eventSeen);
        Check(proof, "parent-generation-context-grant-accepted", parentAccepted && state.IsAuthenticated(DateTimeOffset.UtcNow));
        Check(proof, "successful-registration-closes-window", !window.IsVisible);
        Check(proof, "inputs-cleared-after-close", Field<TextBox>(window, "_iin").Text.Length == 0 &&
            Field<TextBox>(window, "_phone").Text.Length == 0 && Field<PasswordBox>(window, "_password").Password.Length == 0 &&
            Field<PasswordBox>(window, "_passwordConfirmation").Password.Length == 0 && Field<TextBox>(window, "_code").Text.Length == 0);
        Check(proof, "only-two-mocked-requests", handler.TotalCalls == 2 && handler.OtpCalls == 1 && handler.RegisterCalls == 1 && handler.UnexpectedCalls == 0);
    }

    private static async Task WrongContext(Dictionary<string, bool> proof)
    {
        using var handler = new FakeHandler { WrongRegistrationContext = true }; using var client = new StaffAuthClient(Config(), handler);
        var context = Context(); var state = State(context); var window = Window(client, context, true);
        var eventSeen = false; window.Authenticated += _ => eventSeen = true;
        RegistrationFields(window); await RequestOtp(window, handler); Field<TextBox>(window, "_code").Text = "5445";
        Click(Field<Button>(window, "_submit")); await Until(() => handler.RegisterCalls == 1 && !Busy(window));
        Check(proof, "foreign-context-never-authenticated", !eventSeen && state.Grant == null);
        Check(proof, "foreign-context-does-not-close-window", window.IsVisible);
    }

    private static async Task ServiceFailure(bool sms, Dictionary<string, bool> proof)
    {
        using var handler = new FakeHandler { UnavailableLogin = !sms, UnavailableOtp = sms }; using var client = new StaffAuthClient(Config(), handler);
        var context = Context(); var state = State(context); var policy = new StaffPromptPolicy(); var window = Window(client, context, true);
        StaffPromptBypassReason? reason = null; var eventSeen = false;
        window.Authenticated += _ => eventSeen = true;
        window.BypassRequested += value => { reason = value; policy.Bypass(state, value); window.Dismiss(); };
        if (sms) { RegistrationFields(window); Click(Field<Button>(window, "_request")); }
        else { Field<TextBox>(window, "_iin").Text = FixtureIin; Field<PasswordBox>(window, "_password").Password = FixturePassword; Click(Field<Button>(window, "_submit")); }
        await Until(() => !window.IsVisible || !Busy(window));
        Check(proof, "service-failure-reports-safe-bypass", reason == (sms ? StaffPromptBypassReason.SmsUnavailable : StaffPromptBypassReason.ServiceUnavailable));
        Check(proof, "bypass-closes-window", !window.IsVisible);
        Check(proof, "bypass-does-not-create-grant", !eventSeen && state.Grant == null && !state.IsAuthenticated(DateTimeOffset.UtcNow));
        Check(proof, "bypass-records-operational-status", policy.BypassStatus(state.Generation) == (sms ? "staff_bypass_sms_unavailable" : "staff_bypass_service_unavailable"));
    }

    private static async Task SmsWait(Dictionary<string, bool> proof)
    {
        using var handler = new FakeHandler(); using var client = new StaffAuthClient(Config(), handler);
        var context = Context(); var state = State(context); var policy = new StaffPromptPolicy(); var window = Window(client, context, true);
        window.BypassRequested += reason => { policy.Bypass(state, reason); window.Dismiss(); };
        RegistrationFields(window); await RequestOtp(window, handler);
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
        foreach (var name in new[] { "_iin", "_name", "_phone", "_password", "_passwordConfirmation", "_code" })
        {
            var input = Field<Control>(window, name);
            input.BringIntoView(); await Idle(); window.UpdateLayout(); input.ApplyTemplate();
            var host = input.Template.FindName("PART_ContentHost", input) as ScrollViewer;
            Check(proof, name + "-native-host-retained", host != null);
            Check(proof, name + "-padding-applied-once", host!.Margin == new Thickness(0));
            Check(proof, name + "-text-has-enough-height", host.ActualHeight - host.Padding.Top - host.Padding.Bottom >= input.FontSize * 1.2);
        }
        Click(Field<Button>(window, "_languageKk"));
        Check(proof, "kazakh-form-keeps-four-character-policy", Field<TextBlock>(window, "_passwordHint").Text.Contains("4–128", StringComparison.Ordinal));
        Check(proof, "language-change-retains-entered-password", Field<PasswordBox>(window, "_password").Password == FixturePassword);
        SaveOwnWindow(window, "03-registration-compact-kazakh");
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
        public bool LoginContextMatched { get; private set; }
        public bool WrongRegistrationContext { get; init; }
        public bool UnavailableLogin { get; init; }
        public bool UnavailableOtp { get; init; }
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
            var payload = await request.Content!.ReadAsStringAsync(ct); using var body = JsonDocument.Parse(payload);
            switch (request.RequestUri.AbsolutePath)
            {
                case "/api/posm/staff/otp/request":
                    OtpCalls++;
                    if (UnavailableOtp) return Reply(new { code = "UPSTREAM_UNAVAILABLE" }, HttpStatusCode.ServiceUnavailable);
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
