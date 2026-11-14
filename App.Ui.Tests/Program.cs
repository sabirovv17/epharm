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
    private static readonly List<RecommendationWindow> RecommendationWindows = new();
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
            foreach (var window in RecommendationWindows) if (window.IsVisible) window.Close();
            WriteProof(false, "Ninety-second UI watchdog expired.");
            application.Shutdown(3);
        };
        application.Startup += async (_, _) => {
            watchdog.Start();
            try
            {
                _screen = Screen.PrimaryScreen ?? throw new InvalidOperationException("No primary display.");
                await Run("fresh-login-never-displays-registration-or-sms-controls", FreshLoginPresentation);
                await Run("known-source-window-preserves-cashier-focus-and-can-minimize-or-close", NonblockingBoundsAndDismiss);
                await Run("known-source-errors-and-pending-requests-never-trap-cashier", NonblockingErrorsAndPendingRequests);
                await Run("registration-local-and-api-errors-retain-passwords-until-success", RegistrationPasswordRetention);
                await Run("initial-registration-never-offers-sms-for-any-capability", RegistrationNeverSms);
                await Run("activation-is-distinct-from-sms-free-registration-in-ru-and-kk", ActivationRegistrationNavigation);
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
                await Run("existing-required-window-source-loss-retains-form-and-otp", SourceLossPreservesForm);
                await Run("source-loss-during-login-releases-window-without-invalidating-request", SourceLossInFlight);
                await Run("login-password-policy-is-local-and-retains-input", LocalLoginPolicy);
                await Run("registration-unavailable-and-reset-local-errors-retain-passwords", LocalRegistrationValidation);
                await Run("all-auth-errors-retranslate-current-state-in-ru-and-kk", AllStatusTranslations);
                await Run("http-auth-errors-are-mode-specific-and-bilingual", ServerErrorLocalization);
                await Run("server-retry-after-countdown-is-bilingual-and-never-replays", RetryAfterCountdown);
                await Run("exhausted-otp-requires-new-code-without-invented-countdown-or-auto-send", ExhaustedOtp);
                await Run("rapid-submit-is-single-request-and-parent-accepted-grant-clears-password", RapidSubmit);
                await Run("awaited-parent-handoff-keeps-form-busy-and-rejects-dismissed-replies", AwaitedParentHandoff);
                await Run("created-profile-with-rejected-parent-handoff-retries-login-not-registration", RegistrationHandoffRecovery);
                await Run("assignment-denial-is-bilingual-actionable-and-never-registers-again", AssignmentDenial);
                await Run("oversized-existing-password-guides-reset-without-truncation", OversizedExistingPassword);
                await Run("registration-network-timeout-and-malformed-errors-retain-input", RegistrationTransportFailures);
                await Run("registration-invalid-success-response-never-clears-form", InvalidRegistrationResponse);
                await Run("both-login-routes-validate-success-status-without-changing-valid-flows", LoginSuccessStatuses);
                await Run("otp-retry-action-never-prompts-sms-for-login-or-registration", NonOtpRetryAction);
                await Run("new-registration-error-reveals-itself-without-stealing-focus", ErrorRevealsWithoutFocus);
                await Run("recommendation-empty-or-failed-refresh-closes-existing-window", RecommendationEmptyRefresh);
                await Run("recommendation-partial-cart-removal-keeps-other-offer-in-same-window", RecommendationPartialRemoval);
                await Run("recommendation-stock-refresh-renders-uncertainty-with-immutable-financial-quote", RecommendationStockRefresh);
                await Run("recommendation-cart-refresh-does-not-extend-ttl-but-fresh-response-does", RecommendationLifetime);
                await Run("recommendation-native-display-event-sees-new-current-rows", RecommendationDisplayUpdate);
            }
            catch (Exception error)
            {
                Results.Add(new("harness-fatal", false, new(), error.GetType().Name));
            }
            finally
            {
                watchdog.Stop();
                foreach (var window in Windows) if (window.IsVisible) window.Dismiss();
                foreach (var window in RecommendationWindows) if (window.IsVisible) window.Close();
                var success = Results.Count == 54 && Results.All(result => result.Passed);
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
            foreach (var window in RecommendationWindows) if (window.IsVisible) window.Close();
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
    private static async Task Until(Func<bool> condition, int timeoutSeconds = 10)
    {
        var timeout = DateTimeOffset.UtcNow.AddSeconds(timeoutSeconds);
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
    private static void SaveOwnWindow(Window window, string name)
    {
        window.UpdateLayout();
        var dpi = VisualTreeHelper.GetDpi(window);
        var bitmap = new RenderTargetBitmap(Math.Max(1, (int)Math.Ceiling(window.ActualWidth * dpi.DpiScaleX)),
            Math.Max(1, (int)Math.Ceiling(window.ActualHeight * dpi.DpiScaleY)), dpi.PixelsPerInchX, dpi.PixelsPerInchY, PixelFormats.Pbgra32);
        bitmap.Render(window);
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = File.Create(Path.Combine(Output, name + ".png")); encoder.Save(file);
    }

    private static async Task NonblockingBoundsAndDismiss(Dictionary<string, bool> proof)
    {
        using var handler = new FakeHandler(); using var client = new StaffAuthClient(Config(), handler);
        var cashierInput = new TextBox { Text = "DEV synthetic cashier input" };
        var cashier = new System.Windows.Window { Title = "DEV isolated cashier focus fixture", Width = 320, Height = 160, Content = cashierInput };
        try {
            cashier.Show(); cashier.Activate(); cashierInput.Focus(); await Idle();
            var focusBefore = System.Windows.Input.Keyboard.FocusedElement;
            var activeBefore = cashier.IsActive;
            Check(proof, "cashier-keyboard-focus-precondition", ReferenceEquals(focusBefore, cashierInput));
            var window = Window(client, Context(), true); await Idle();
            var dpi = VisualTreeHelper.GetDpi(window);
            Check(proof, "session-mode-explicit", _interactiveSession || _allowNoninteractive);
            Check(proof, "fits-working-area-with-taskbar-accessible", window.ActualWidth < _screen.WorkingArea.Width / dpi.DpiScaleX &&
                window.ActualHeight < _screen.WorkingArea.Height / dpi.DpiScaleY);
            Check(proof, "known-source-never-requests-activation-or-topmost", !window.ShowActivated && !window.Topmost &&
                window.WindowStyle == WindowStyle.SingleBorderWindow && window.ResizeMode == ResizeMode.CanResize);
            Check(proof, "show-keeps-cashier-keyboard-focus", ReferenceEquals(focusBefore, System.Windows.Input.Keyboard.FocusedElement) && cashier.IsActive == activeBefore);
            Check(proof, "continue-is-available-before-any-error", Field<Button>(window, "_continueWithoutSignIn").Visibility == Visibility.Visible && Field<Button>(window, "_continueWithoutSignIn").IsEnabled);
            SaveOwnWindow(window, "01-nonblocking-login");
            window.WindowState = WindowState.Minimized; await Idle();
            Check(proof, "ordinary-minimize-stays-minimized", window.WindowState == WindowState.Minimized);
            window.Close(); await Idle();
            Check(proof, "ordinary-close-succeeds-and-cancels-requests", !window.IsVisible && window.ClosedToken.IsCancellationRequested);
            Check(proof, "no-http-requests", handler.TotalCalls == 0);
        }
        finally { cashier.Close(); }
    }

    private static async Task NonblockingErrorsAndPendingRequests(Dictionary<string, bool> proof)
    {
        foreach (var serverError in new[] { false, true }) {
            var state = KnownDeviceState();
            using var handler = new FakeHandler { DeviceContext = state.Context, DirectFailureStatuses = serverError ? [HttpStatusCode.Conflict] : [] };
            using var client = new StaffAuthClient(Config(), handler);
            var window = DeviceWindow(client, state, registrationSmsRequired: false);
            var accepted = false; window.Authenticated += _ => accepted = true;
            window.BypassRequested += reason => state.Bypass(reason);
            RegistrationFields(window);
            if (!serverError) Field<TextBox>(window, "_name").Clear();
            Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
            var prefix = serverError ? "existing-profile" : "local-validation";
            var message = Field<TextBlock>(window, "_status").Text;
            Check(proof, prefix + "-has-actionable-error-and-retains-passwords", PasswordsRetained(window) &&
                message.Contains(serverError ? "профиль уже существует" : "ФИО", StringComparison.Ordinal));
            Check(proof, prefix + "-retains-identity-fields", Field<TextBox>(window, "_iin").Text == FixtureIin && Field<TextBox>(window, "_phone").Text == FixturePhone);
            Check(proof, prefix + "-never-locks-cashier", !window.Topmost && !window.ShowActivated && window.WindowStyle == WindowStyle.SingleBorderWindow);
            window.WindowState = WindowState.Minimized; await Idle();
            Check(proof, prefix + "-minimize-is-respected", window.WindowState == WindowState.Minimized);
            window.WindowState = WindowState.Normal;
            Click(Field<Button>(window, "_continueWithoutSignIn")); await Idle();
            Check(proof, prefix + "-continue-closes-without-authentication", !window.IsVisible && !accepted && state.Grant == null && !state.NeedsPrompt(DateTimeOffset.UtcNow));
            Check(proof, prefix + "-does-not-submit-or-replay-on-dismiss", handler.TotalCalls == (serverError ? 1 : 0));
        }
        {
            var state = KnownDeviceState();
            using var handler = new FakeHandler { DeviceContext = state.Context, LoginFailure = HttpStatusCode.Unauthorized, LoginFailureCode = "INVALID_CREDENTIALS" };
            using var client = new StaffAuthClient(Config(), handler);
            var window = DeviceWindow(client, state);
            window.Closed += (_, _) => state.Bypass(StaffPromptBypassReason.IdentityUnavailable);
            FillLogin(window); Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
            Check(proof, "wrong-password-keeps-input-and-specific-error", Field<PasswordBox>(window, "_password").Password == FixturePassword &&
                Field<TextBlock>(window, "_status").Text.Contains("Проверьте ИИН и пароль", StringComparison.Ordinal));
            Check(proof, "wrong-password-does-not-lock-cashier", !window.Topmost && !window.ShowActivated &&
                Field<Button>(window, "_continueWithoutSignIn").Visibility == Visibility.Visible && Field<Button>(window, "_continueWithoutSignIn").IsEnabled);
            window.Close(); await Idle();
            Check(proof, "wrong-password-close-does-not-create-grant-or-repeat", !window.IsVisible && state.Grant == null && !state.NeedsPrompt(DateTimeOffset.UtcNow) && handler.DeviceLoginCalls == 1);
        }
        foreach (var continueWork in new[] { false, true }) {
            var state = KnownDeviceState();
            using var handler = new FakeHandler { DeviceContext = state.Context, DeviceDelayMilliseconds = 300 };
            using var client = new StaffAuthClient(Config(), handler);
            var window = DeviceWindow(client, state, registrationSmsRequired: false);
            var authenticated = false; window.Authenticated += _ => authenticated = true;
            window.BypassRequested += reason => state.Bypass(reason);
            window.Closed += (_, _) => state.Bypass(StaffPromptBypassReason.IdentityUnavailable);
            RegistrationFields(window); Click(Field<Button>(window, "_submit"));
            await Until(() => handler.WithoutSmsCalls == 1);
            var prefix = continueWork ? "pending-continue" : "pending-close";
            Check(proof, prefix + "-request-is-pending-with-exit-enabled", Busy(window) && Field<Button>(window, "_continueWithoutSignIn").IsEnabled);
            if (continueWork) Click(Field<Button>(window, "_continueWithoutSignIn")); else window.Close();
            await Until(() => !Busy(window));
            Check(proof, prefix + "-cancels-without-accepting-late-grant", !window.IsVisible && window.ClosedToken.IsCancellationRequested && !authenticated && state.Grant == null);
            Check(proof, prefix + "-has-no-retry-or-auto-popup", handler.WithoutSmsCalls == 1 && handler.OtpCalls == 0 && !state.NeedsPrompt(DateTimeOffset.UtcNow));
        }
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
        Check(proof, "name-only-context-never-locks-cashier", window.WindowStyle == WindowStyle.SingleBorderWindow && !window.Topmost && !window.ShowActivated);
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
        Check(proof, "known-source-is-nonblocking-from-start", window.WindowStyle == WindowStyle.SingleBorderWindow && !window.Topmost);
        FillLogin(window); Click(Field<Button>(window, "_submit")); await Until(() => handler.DeviceLoginCalls == 1 && !Busy(window));
        Check(proof, "pending-profile-does-not-emit-authentication-or-grant", !authenticated && state.Grant == null && !state.IsAuthenticated(DateTimeOffset.UtcNow));
        Check(proof, "pending-keeps-the-form-open-and-releases-work", window.IsVisible && window.WindowStyle != WindowStyle.None &&
            Field<Button>(window, "_continueWithoutSignIn").Visibility == Visibility.Visible && Field<Button>(window, "_continueWithoutSignIn").IsEnabled);
        Check(proof, "pending-explanation-is-preserved", Field<TextBlock>(window, "_status").Text.Contains("Назначение аптеки для этого профиля пока не подтверждено", StringComparison.Ordinal));
        window.Closed += (_, _) => state.Bypass(StaffPromptBypassReason.IdentityUnavailable);
        window.Close(); await Idle();
        Check(proof, "pending-profile-can-close-without-forging-login", !window.IsVisible && !state.IsAuthenticated(DateTimeOffset.UtcNow) && !state.NeedsPrompt(DateTimeOffset.UtcNow));
    }

    private static async Task FreshLoginPresentation(Dictionary<string, bool> proof)
    {
        foreach (var device in new[] { false, true })
        foreach (var kazakh in new[] { false, true })
        {
            var state = DeviceState();
            using var handler = new FakeHandler { DeviceContext = device ? state.Context : null };
            using var client = new StaffAuthClient(Config(), handler);
            var window = new StaffAuthWindow(client, device ? null : Context(), _screen,
                deviceContext: device ? state.Context : null, registrationSmsRequired: false, kazakh: kazakh);
            Windows.Add(window);
            var prefix = $"device-{device}-kk-{kazakh}-";
            Check(proof, prefix + "constructor-selects-login-without-navigation", Field<ComboBox>(window, "_mode").SelectedIndex == 0 &&
                Field<TextBlock>(window, "_heading").Text == (kazakh ? "Фармацевттің кіруі" : "Вход для фармацевта"));
            Check(proof, prefix + "constructor-hides-sms-and-registration-only-fields",
                Field<Button>(window, "_request").Visibility == Visibility.Collapsed &&
                Field<StackPanel>(window, "_codeRow").Visibility == Visibility.Collapsed &&
                Field<StackPanel>(window, "_phoneRow").Visibility == Visibility.Collapsed &&
                Field<StackPanel>(window, "_nameRow").Visibility == Visibility.Collapsed &&
                Field<StackPanel>(window, "_passwordConfirmationRow").Visibility == Visibility.Collapsed);
            window.Show(); await Idle();
            Check(proof, prefix + "first-render-hides-sms-and-preserves-login", Field<Button>(window, "_request").Visibility == Visibility.Collapsed &&
                Field<StackPanel>(window, "_codeRow").Visibility == Visibility.Collapsed && Field<Button>(window, "_submit").IsEnabled);
            Click(Field<Button>(window, kazakh ? "_languageRu" : "_languageKk")); await Idle();
            Check(proof, prefix + "language-change-does-not-show-sms-or-send-request", Field<ComboBox>(window, "_mode").SelectedIndex == 0 &&
                Field<Button>(window, "_request").Visibility == Visibility.Collapsed && Field<StackPanel>(window, "_codeRow").Visibility == Visibility.Collapsed &&
                handler.TotalCalls == 0);
            window.Close(); await Idle();
        }
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

    private static async Task ActivationRegistrationNavigation(Dictionary<string, bool> proof)
    {
        var state = DeviceState();
        using var handler = new FakeHandler { DeviceContext = state.Context };
        using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state, registrationSmsRequired: false);
        foreach (var kazakh in new[] { false, true })
        {
            Click(Field<Button>(window, kazakh ? "_languageKk" : "_languageRu"));
            Click(Field<Button>(window, "_activate")); await Idle();
            var prefix = kazakh ? "kk-" : "ru-";
            Check(proof, prefix + "activation-heading-is-explicit", Field<ComboBox>(window, "_mode").SelectedIndex == 3 &&
                Field<TextBlock>(window, "_heading").Text == (kazakh ? "Профильді белсендіру" : "Активация профиля"));
            var intro = Field<TextBlock>(window, "_intro").Text;
            Check(proof, prefix + "activation-explains-existing-phone-proof-and-registration-route",
                intro.Contains(kazakh ? "Бұл жаңа қызметкерді тіркеу емес" : "Это не регистрация нового сотрудника", StringComparison.Ordinal) &&
                intro.Contains(kazakh ? "бұрын тіркелген телефон" : "ранее привязанному телефону", StringComparison.Ordinal) &&
                intro.Contains(kazakh ? "Кіруге оралу" : "К входу", StringComparison.Ordinal) &&
                intro.Contains(kazakh ? "SMS-сіз" : "без SMS", StringComparison.Ordinal));
            Check(proof, prefix + "activation-retains-otp-controls", Field<Button>(window, "_request").Visibility == Visibility.Visible &&
                Field<Button>(window, "_request").IsEnabled && Field<StackPanel>(window, "_codeRow").Visibility == Visibility.Visible);
            Click(Field<Button>(window, "_back"));
            Click(Field<Button>(window, "_newStaff")); await Idle();
            Check(proof, prefix + "new-staff-route-is-labelled-registration", Field<ComboBox>(window, "_mode").SelectedIndex == 1 &&
                Field<TextBlock>(window, "_heading").Text == (kazakh ? "Жаңа қызметкер" : "Новый сотрудник") &&
                Field<TextBlock>(window, "_intro").Text.Contains(kazakh ? "SMS қажет емес" : "SMS не требуется", StringComparison.Ordinal));
            Check(proof, prefix + "registration-never-inherits-activation-sms", Field<Button>(window, "_request").Visibility == Visibility.Collapsed &&
                !Field<Button>(window, "_request").IsEnabled && Field<StackPanel>(window, "_codeRow").Visibility == Visibility.Collapsed);
            Click(Field<Button>(window, "_back")); await Idle();
            Check(proof, prefix + "navigation-does-not-send-sms-or-authenticate", handler.TotalCalls == 0 && state.Grant == null);
        }
        window.Close(); await Idle();
    }

    private static async Task RegistrationPasswordRetention(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); var context = state.Context!; var generation = state.Generation;
        using var handler = new FakeHandler { DeviceContext = context,
            DirectFailureStatuses = [HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.Conflict, HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable] };
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
        Field<TextBlock>(window, "_status").BringIntoView(); await Idle();
        SaveOwnWindow(window, "07-registration-local-error-preserved");
        Field<PasswordBox>(window, "_password").Password = "12"; Field<PasswordBox>(window, "_passwordConfirmation").Password = "12";
        Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "local-length-error-retains-passwords-without-request", handler.TotalCalls == 0 && Field<PasswordBox>(window, "_password").Password == "12" && Field<PasswordBox>(window, "_passwordConfirmation").Password == "12");
        Field<PasswordBox>(window, "_password").Password = FixturePassword; Field<PasswordBox>(window, "_passwordConfirmation").Password = FixturePassword;
        for (var index = 0; index < handler.DirectFailureStatuses.Length; index++)
        {
            Click(Field<Button>(window, "_submit")); await Until(() => handler.WithoutSmsCalls == index + 1 && !Busy(window));
            Check(proof, $"http-{(int)handler.DirectFailureStatuses[index]}-retains-passwords-and-identity", window.IsVisible && !accepted && state.Grant == null && Field<PasswordBox>(window, "_password").Password == FixturePassword && Field<PasswordBox>(window, "_passwordConfirmation").Password == FixturePassword && Field<TextBox>(window, "_iin").Text == FixtureIin && Field<TextBox>(window, "_phone").Text == FixturePhone);
            Check(proof, $"http-{(int)handler.DirectFailureStatuses[index]}-no-automatic-retry-or-otp", handler.TotalCalls == index + 1 && handler.OtpCalls == 0 && handler.RegisterCalls == 0 && handler.DeviceRegisterCalls == 0);
            if (index == 0) { Field<TextBlock>(window, "_status").BringIntoView(); await Idle(); SaveOwnWindow(window, "08-registration-http-error-preserved"); }
        }
        Click(Field<Button>(window, "_submit")); await Until(() => !window.IsVisible || !Busy(window));
        Check(proof, "same-form-retry-succeeds-without-reentering-password", accepted && !window.IsVisible && state.IsAuthenticated(DateTimeOffset.UtcNow) && handler.WithoutSmsCalls == handler.DirectFailureStatuses.Length + 1 && handler.DirectBodyMatched);
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
        Check(proof, "known-source-starts-nonblocking", window.WindowStyle == WindowStyle.SingleBorderWindow && !window.Topmost);
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
        Check(proof, "rollback-of-created-account-offers-login-with-preserved-password", Field<ComboBox>(window, "_mode").SelectedIndex == 0 &&
            Field<PasswordBox>(window, "_password").Password == FixturePassword && Field<PasswordBox>(window, "_passwordConfirmation").Password.Length == 0 &&
            handler.DeviceLoginCalls == 0);
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

    private static StaffDeviceSessionState KnownDeviceState()
    {
        var state = DeviceState(); var process = state.ProcessInstanceId!;
        state.Observe(new("resolved", process), new("resolved", new StaffObservedSession(process, "DEV-USER", "DEV-SESSION", "DEV-WS", FixtureIin, "available")));
        return state;
    }
    private static async Task SourceLossPreservesForm(Dictionary<string, bool> proof)
    {
        var state = KnownDeviceState(); var context = state.Context!; var generation = state.Generation;
        using var handler = new FakeHandler { DeviceContext = context }; using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state); RecoveryFields(window); Field<TextBox>(window, "_name").Text = "DEV synthetic employee"; await RequestOtp(window, handler);
        Field<TextBox>(window, "_code").Text = "5445";
        Check(proof, "same-existing-window-starts-nonblocking", window.IsVisible && window.WindowStyle == WindowStyle.SingleBorderWindow && !window.Topmost);
        var challenge = Field<string>(window, "_challenge");
        state.Observe(new("resolved", context.ProcessInstanceId), new("database_unavailable"));
        // Exercise the real public presentation hook used by MainWindow.ApplyStaffObservation;
        // no MainWindow startup, real Standard-N process or production database is simulated.
        window.UpdateSourceContext(state.CurrentSource); await Idle();
        Check(proof, "unknown-source-releases-existing-window", window.IsVisible && window.WindowStyle == WindowStyle.SingleBorderWindow && window.ResizeMode == ResizeMode.CanResize);
        Check(proof, "source-loss-preserves-all-form-and-otp-input", Field<TextBox>(window, "_iin").Text == FixtureIin &&
            Field<TextBox>(window, "_phone").Text == FixturePhone && Field<TextBox>(window, "_name").Text == "DEV synthetic employee" &&
            Field<PasswordBox>(window, "_password").Password == FixturePassword && Field<PasswordBox>(window, "_passwordConfirmation").Password == FixturePassword &&
            Field<TextBox>(window, "_code").Text == "5445" && Field<string>(window, "_challenge") == challenge);
        Check(proof, "source-loss-does-not-authorize-or-change-generation", state.Grant == null && state.Generation == generation && state.LastKnownSource == context.StandardNContext);
        SaveOwnWindow(window, "09-source-loss-preserved-form");
        window.Close(); await Idle();
        Check(proof, "released-window-is-closable-and-clears-secrets", !window.IsVisible && Field<PasswordBox>(window, "_password").Password.Length == 0 && Field<PasswordBox>(window, "_passwordConfirmation").Password.Length == 0);
        Check(proof, "no-extra-http-from-source-loss", handler.TotalCalls == 1);
    }
    private static async Task SourceLossInFlight(Dictionary<string, bool> proof)
    {
        var state = KnownDeviceState(); var context = state.Context!; var generation = state.Generation;
        StaffAuthWindow? window = null;
        using var handler = new FakeHandler { DeviceContext = context, DeviceDelayMilliseconds = 60, BeforeDeviceReply = () => {
            Check(proof, "real-window-request-is-pending", window!.IsRequestPending);
            state.Observe(new("resolved", context.ProcessInstanceId), new("ambiguous_session"));
            window.UpdateSourceContext(state.CurrentSource);
            Check(proof, "pending-release-does-not-cancel-request-or-generation", window.IsRequestPending && window.WindowStyle != WindowStyle.None && state.Generation == generation);
        } };
        using var client = new StaffAuthClient(Config(), handler); window = DeviceWindow(client, state);
        var accepted = false;
        window.Authenticated += response => { accepted = state.Accept(generation, context, response, DateTimeOffset.UtcNow); if (accepted) window.Dismiss(); };
        FillLogin(window); Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "current-request-can-complete-after-source-loss", accepted && state.IsAuthenticated(DateTimeOffset.UtcNow) && !window.IsVisible && handler.DeviceLoginCalls == 1);
    }
    private static async Task LocalLoginPolicy(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); using var handler = new FakeHandler { DeviceContext = state.Context }; using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state); FillLogin(window);
        window.Authenticated += response => { if (state.Accept(state.Generation, state.Context!, response, DateTimeOffset.UtcNow)) window.Dismiss(); };
        foreach (var password in new[] { "123", new string('я', 37) }) {
            Field<PasswordBox>(window, "_password").Password = password;
            Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
            var status = Field<TextBlock>(window, "_status").Text;
            Check(proof, password.Length == 3 ? "short-login-is-local-and-specific" : "multibyte-overflow-is-local-and-specific",
                handler.TotalCalls == 0 && Field<PasswordBox>(window, "_password").Password == password &&
                status.Contains(password.Length == 3 ? "4 до 128" : "72 байт", StringComparison.Ordinal) && !status.Contains("SMS", StringComparison.OrdinalIgnoreCase));
        }
        Field<PasswordBox>(window, "_password").Password = FixturePassword;
        Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "four-character-login-still-reaches-server-once", handler.DeviceLoginCalls == 1 && handler.DeviceBodyMatched && Field<PasswordBox>(window, "_password").Password.Length == 0);
    }
    private static async Task LocalRegistrationValidation(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); using var handler = new FakeHandler { DeviceContext = state.Context }; using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state, registrationSmsRequired: false); RegistrationFields(window); Field<TextBox>(window, "_name").Clear();
        Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "missing-name-retains-both-passwords-without-http", handler.TotalCalls == 0 && PasswordsRetained(window) && Field<TextBlock>(window, "_status").Text.Contains("ФИО", StringComparison.Ordinal));
        Field<TextBox>(window, "_name").Text = "DEV synthetic employee";
        Field<PasswordBox>(window, "_passwordConfirmation").Password = "different";
        Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "mismatch-retains-both-original-inputs", handler.TotalCalls == 0 && Field<PasswordBox>(window, "_password").Password == FixturePassword && Field<PasswordBox>(window, "_passwordConfirmation").Password == "different");
        Field<PasswordBox>(window, "_passwordConfirmation").Password = FixturePassword;
        window.SetRegistrationSmsRequired(true);
        Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "unavailable-registration-retains-input-and-never-offers-otp", handler.TotalCalls == 0 && PasswordsRetained(window) &&
            Field<Button>(window, "_request").Visibility == Visibility.Collapsed && Field<TextBlock>(window, "_status").Text.Contains("Регистрация без SMS пока недоступна", StringComparison.Ordinal));
        RecoveryFields(window);
        Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "reset-missing-sms-retains-passwords-without-http", handler.TotalCalls == 0 && PasswordsRetained(window));
        await RequestOtp(window, handler); Field<TextBox>(window, "_code").Text = "1";
        Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "reset-malformed-code-retains-passwords-without-completion", handler.TotalCalls == 1 && PasswordsRetained(window) && Field<TextBlock>(window, "_status").Text.Contains("4–8", StringComparison.Ordinal));
        typeof(StaffAuthWindow).GetField("_challengeExpiresAt", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(window, DateTimeOffset.UtcNow.AddSeconds(-1));
        Field<TextBox>(window, "_code").Text = "5445"; Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "reset-expired-code-retains-passwords-without-completion", handler.TotalCalls == 1 && PasswordsRetained(window) && Field<TextBlock>(window, "_status").Text.Contains("запросите новый SMS", StringComparison.Ordinal));
        Field<TextBlock>(window, "_status").BringIntoView(); await Idle(); SaveOwnWindow(window, "10-local-validation-preserved-passwords");
        Click(Field<Button>(window, "_back"));
        Check(proof, "navigation-clears-passwords", Field<PasswordBox>(window, "_password").Password.Length == 0 && Field<PasswordBox>(window, "_passwordConfirmation").Password.Length == 0);
    }
    private static bool PasswordsRetained(StaffAuthWindow window) => Field<PasswordBox>(window, "_password").Password == FixturePassword && Field<PasswordBox>(window, "_passwordConfirmation").Password == FixturePassword;
    private static async Task AllStatusTranslations(Dictionary<string, bool> proof)
    {
        using var handler = new FakeHandler(); using var client = new StaffAuthClient(Config(), handler);
        var window = Window(client, Context(), false);
        var setError = typeof(StaffAuthWindow).GetMethod("SetError", BindingFlags.Instance | BindingFlags.NonPublic)!;
        var languageEvents = 0; window.LanguageChanged += _ => languageEvents++;
        foreach (var code in new[] { "IIN_REQUIRED", "PHONE_REQUIRED", "SESSION_REQUIRED", "PASSWORD_REQUIRED", "PASSWORD_LENGTH", "PASSWORD_BYTES", "PASSWORD_MISMATCH", "NAME_REQUIRED", "SMS_REQUIRED", "CODE_REQUIRED", "RATE_LIMITED", "INVALID_CREDENTIALS", "FORBIDDEN", "INVALID_DATA", "CONFLICT", "INVALID_RESPONSE", "UNAVAILABLE", "REGISTRATION_UNAVAILABLE" }) {
            Click(Field<Button>(window, "_languageRu")); setError.Invoke(window, [new StaffApiException(code)]);
            var ru = Field<TextBlock>(window, "_status").Text;
            Click(Field<Button>(window, "_languageKk")); var kk = Field<TextBlock>(window, "_status").Text;
            Click(Field<Button>(window, "_languageRu"));
            Check(proof, code + "-current-message-retranslates", !string.IsNullOrWhiteSpace(kk) && kk != ru && Field<TextBlock>(window, "_status").Text == ru);
        }
        Click(Field<Button>(window, "_languageKk"));
        window.ReleaseWork("Форма сохранена.", "Нысан сақталды.");
        Check(proof, "parent-status-uses-selected-language", window.IsKazakh && Field<TextBlock>(window, "_status").Text == "Нысан сақталды." && languageEvents == 55);
        SaveOwnWindow(window, "11-kazakh-status"); await Idle();
        Check(proof, "translations-never-send-requests", handler.TotalCalls == 0);
    }
    private static async Task ServerErrorLocalization(Dictionary<string, bool> proof)
    {
        foreach (var status in new[] { HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized, HttpStatusCode.ServiceUnavailable }) {
            var state = DeviceState(); using var handler = new FakeHandler { DeviceContext = state.Context, LoginFailure = status }; using var client = new StaffAuthClient(Config(), handler);
            var window = DeviceWindow(client, state); FillLogin(window); Click(Field<Button>(window, "_languageKk"));
            Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
            var kk = Field<TextBlock>(window, "_status").Text;
            Click(Field<Button>(window, "_languageRu")); var ru = Field<TextBlock>(window, "_status").Text;
            Check(proof, ((int)status) + "-real-http-error-localizes-without-sms-guidance", kk != ru && !kk.Contains("SMS", StringComparison.OrdinalIgnoreCase) && !ru.Contains("SMS", StringComparison.OrdinalIgnoreCase) && handler.TotalCalls == 1 && Field<PasswordBox>(window, "_password").Password == FixturePassword);
            window.Dismiss();
        }
    }
    private static async Task RetryAfterCountdown(Dictionary<string, bool> proof)
    {
        var state = KnownDeviceState(); using var handler = new FakeHandler { DeviceContext = state.Context, RateLimitedRequests = true, RetryAfterSeconds = 2 }; using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state); FillLogin(window); Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "server-delay-shown-with-released-presentation", Field<TextBlock>(window, "_status").Text.Contains("Повторите через", StringComparison.Ordinal) &&
            Field<DispatcherTimer>(window, "_statusTimer").IsEnabled && window.WindowStyle != WindowStyle.None);
        Click(Field<Button>(window, "_languageKk"));
        Check(proof, "same-countdown-retranslates-to-kazakh", Field<TextBlock>(window, "_status").Text.Contains("с кейін қайталаңыз", StringComparison.Ordinal));
        await Until(() => Field<TextBlock>(window, "_status").Text.Contains("аяқталды", StringComparison.Ordinal));
        Check(proof, "countdown-stops-without-automatic-retry", handler.TotalCalls == 1 && !Field<DispatcherTimer>(window, "_statusTimer").IsEnabled);
    }
    private static async Task RapidSubmit(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); using var handler = new FakeHandler { DeviceContext = state.Context, DeviceDelayMilliseconds = 150 }; using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state); FillLogin(window); var events = 0;
        window.Authenticated += response => { events++; if (state.Accept(state.Generation, state.Context!, response, DateTimeOffset.UtcNow)) window.Dismiss(); };
        var submit = Field<Button>(window, "_submit"); Click(submit);
        Check(proof, "pending-request-disables-submit", Busy(window) && !submit.IsEnabled);
        for (var n = 0; n < 10; n++) submit.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
        await Until(() => !Busy(window));
        Check(proof, "even-forced-rapid-clicks-send-one-mutation", handler.DeviceLoginCalls == 1 && events == 1 && Field<PasswordBox>(window, "_password").Password.Length == 0);
    }
    private static async Task ExhaustedOtp(Dictionary<string, bool> proof)
    {
        var state = KnownDeviceState(); using var handler = new FakeHandler { DeviceContext = state.Context,
            RateLimitedRequests = true, RetryAction = "REQUEST_NEW_OTP" }; using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state); RecoveryFields(window); await RequestOtp(window, handler);
        Field<TextBox>(window, "_code").Text = "5445"; Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "exhausted-code-explains-new-sms-not-waiting", Field<TextBlock>(window, "_status").Text.Contains("Запросите новый SMS-код", StringComparison.Ordinal) &&
            !Field<DispatcherTimer>(window, "_statusTimer").IsEnabled);
        Check(proof, "exhausted-challenge-cleared-without-grant-or-auto-send", Field<TextBox>(window, "_code").Text.Length == 0 &&
            typeof(StaffAuthWindow).GetField("_challenge", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(window) == null &&
            state.Grant == null && handler.TotalCalls == 2 && handler.OtpCalls == 1 && window.WindowStyle != WindowStyle.None);
        Click(Field<Button>(window, "_languageKk"));
        Check(proof, "new-code-guidance-retranslates-current-state", Field<TextBlock>(window, "_status").Text.Contains("Жаңа SMS кодын сұраңыз", StringComparison.Ordinal));
    }

    private static async Task AwaitedParentHandoff(Dictionary<string, bool> proof)
    {
        foreach (var registering in new[] { false, true })
        foreach (var dismiss in new[] { false, true })
        {
            var state = KnownDeviceState();
            using var handler = new FakeHandler { DeviceContext = state.Context };
            using var client = new StaffAuthClient(Config(), handler);
            var window = DeviceWindow(client, state, registrationSmsRequired: false);
            if (registering) RegistrationFields(window); else FillLogin(window);
            var started = false; var accepted = false;
            var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            window.AuthenticationCompletion = async response => {
                started = true;
                await release.Task.WaitAsync(window.ClosedToken);
                accepted = state.Accept(state.Generation, state.Context!, response, DateTimeOffset.UtcNow);
                if (accepted) window.Dismiss();
            };
            var prefix = $"{(registering ? "registration" : "login")}-{(dismiss ? "dismissed" : "accepted")}";
            Click(Field<Button>(window, "_submit")); await Until(() => started);
            Check(proof, prefix + "-pending-parent-keeps-form-and-password-busy", Busy(window) &&
                !Field<Button>(window, "_submit").IsEnabled && Field<PasswordBox>(window, "_password").Password == FixturePassword && state.Grant == null);
            for (var n = 0; n < 5; n++) Field<Button>(window, "_submit").RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
            Check(proof, prefix + "-no-auth-mutation-replay", handler.TotalCalls == 1);
            if (dismiss) window.Dismiss();
            release.TrySetResult(); await Until(() => !Busy(window));
            Check(proof, prefix + "-only-live-completion-authenticates", accepted == !dismiss && !window.IsVisible &&
                (state.Grant != null) == !dismiss && Field<PasswordBox>(window, "_password").Password.Length == 0);
        }
    }

    private static async Task RegistrationHandoffRecovery(Dictionary<string, bool> proof)
    {
        foreach (var retry in new[] { false, true })
        {
            var state = KnownDeviceState();
            using var handler = new FakeHandler { DeviceContext = state.Context };
            using var client = new StaffAuthClient(Config(), handler);
            var window = DeviceWindow(client, state, registrationSmsRequired: false);
            RegistrationFields(window);
            var started = false;
            var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            window.AuthenticationCompletion = async response => {
                if (handler.DeviceLoginCalls == 0)
                {
                    started = true;
                    await release.Task.WaitAsync(window.ClosedToken);
                    await client.DeviceLogoutAsync(response.SessionToken!, window.ClosedToken);
                    window.ReleaseWork("Windows не подтвердила кассовый процесс. Проверьте доступ к Standard-N.",
                        "Windows касса процесін растамады. Standard-N қолжетімділігін тексеріңіз.");
                }
                else if (state.Accept(state.Generation, state.Context!, response, DateTimeOffset.UtcNow)) window.Dismiss();
            };
            var prefix = retry ? "manual-retry-" : "close-";
            Click(Field<Button>(window, "_submit")); await Until(() => started);
            Check(proof, prefix + "waits-for-parent-without-replaying-registration", Busy(window) &&
                handler.WithoutSmsCalls == 1 && handler.TotalCalls == 1 && state.Grant == null);
            release.TrySetResult(); await Until(() => !Busy(window));
            Check(proof, prefix + "created-account-is-offered-login-not-another-registration", window.IsVisible &&
                Field<ComboBox>(window, "_mode").SelectedIndex == 0 && (string)Field<Button>(window, "_submit").Content == "Войти");
            Check(proof, prefix + "retains-login-credentials-only-in-open-form", Field<TextBox>(window, "_iin").Text == FixtureIin &&
                Field<PasswordBox>(window, "_password").Password == FixturePassword && Field<PasswordBox>(window, "_passwordConfirmation").Password.Length == 0);
            Check(proof, prefix + "rejected-grant-revoked-without-auto-login-or-sms", state.Grant == null &&
                handler.DeviceLogoutCalls == 1 && handler.TotalCalls == 2 && handler.DeviceLoginCalls == 0 && handler.OtpCalls == 0 &&
                Field<Button>(window, "_request").Visibility == Visibility.Collapsed);
            Check(proof, prefix + "ru-confirms-created-account-and-preserves-parent-diagnostic",
                Field<TextBlock>(window, "_status").Text.Contains("Профиль создан", StringComparison.Ordinal) &&
                Field<TextBlock>(window, "_status").Text.Contains("Windows не подтвердила кассовый процесс", StringComparison.Ordinal));
            Click(Field<Button>(window, "_languageKk"));
            Check(proof, prefix + "kk-retranslates-created-account-and-parent-diagnostic",
                Field<TextBlock>(window, "_status").Text.Contains("Профиль жасалды", StringComparison.Ordinal) &&
                Field<TextBlock>(window, "_status").Text.Contains("Windows касса процесін растамады", StringComparison.Ordinal) &&
                Field<PasswordBox>(window, "_password").Password == FixturePassword);
            if (retry)
            {
                Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
                Check(proof, prefix + "explicit-retry-only-logs-in-existing-profile", state.IsAuthenticated(DateTimeOffset.UtcNow) &&
                    !window.IsVisible && handler.DeviceLoginCalls == 1 && handler.WithoutSmsCalls == 1 && handler.TotalCalls == 3 && handler.OtpCalls == 0);
            }
            else window.Close();
            await Idle();
            Check(proof, prefix + "close-clears-recovered-login-secrets", !window.IsVisible &&
                Field<TextBox>(window, "_iin").Text.Length == 0 && Field<PasswordBox>(window, "_password").Password.Length == 0 &&
                Field<PasswordBox>(window, "_passwordConfirmation").Password.Length == 0);
        }
    }

    private static async Task AssignmentDenial(Dictionary<string, bool> proof)
    {
        foreach (var code in new[] { "STAFF_PHARMACY_ASSIGNMENT_REQUIRED", "STAFF_PHARMACY_MISMATCH" })
        {
            var state = KnownDeviceState();
            using var handler = new FakeHandler { DeviceContext = state.Context, LoginFailure = HttpStatusCode.Forbidden, LoginFailureCode = code };
            using var client = new StaffAuthClient(Config(), handler); var window = DeviceWindow(client, state);
            window.AssignmentPending += state.AwaitAssignment;
            FillLogin(window); Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
            var ru = Field<TextBlock>(window, "_status").Text;
            Check(proof, code + "-ru-guides-hq-without-new-registration", ru.Contains("HQ", StringComparison.Ordinal) &&
                ru.Contains("Повторная регистрация не нужна", StringComparison.Ordinal) && window.WindowStyle != WindowStyle.None);
            Click(Field<Button>(window, "_languageKk")); var kk = Field<TextBlock>(window, "_status").Text;
            Check(proof, code + "-kk-retains-fields-and-pending-state", kk != ru && kk.Contains("Қайта тіркелудің қажеті жоқ", StringComparison.Ordinal) &&
                state.BypassStatus == "pending_approval" && state.Grant == null && Field<PasswordBox>(window, "_password").Password == FixturePassword);
            Check(proof, code + "-no-register-reset-or-raw-server-text", handler.TotalCalls == 1 && handler.DeviceLoginCalls == 1 &&
                !ru.Contains("PRIVATE-RAW", StringComparison.Ordinal) && !kk.Contains("PRIVATE-RAW", StringComparison.Ordinal));
            window.Dismiss();
        }
    }

    private static async Task OversizedExistingPassword(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); using var handler = new FakeHandler { DeviceContext = state.Context };
        using var client = new StaffAuthClient(Config(), handler); var window = DeviceWindow(client, state);
        FillLogin(window); var password = new string('я', 37); Field<PasswordBox>(window, "_password").Password = password;
        Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        Check(proof, "ru-explains-reset-not-truncating-existing-password", Field<TextBlock>(window, "_status").Text.Contains("Не сокращайте существующий пароль", StringComparison.Ordinal) &&
            Field<PasswordBox>(window, "_password").Password == password && handler.TotalCalls == 0);
        Click(Field<Button>(window, "_languageKk"));
        Check(proof, "kk-explains-safe-reset", Field<TextBlock>(window, "_status").Text.Contains("Бар құпиясөзді қысқартпаңыз", StringComparison.Ordinal));
        Click(Field<Button>(window, "_forgot"));
        Check(proof, "explicit-reset-route-keeps-iin-without-auto-sms", Field<ComboBox>(window, "_mode").SelectedIndex == 2 &&
            Field<TextBox>(window, "_iin").Text == FixtureIin && Field<PasswordBox>(window, "_password").Password.Length == 0 && handler.TotalCalls == 0);
    }

    private static async Task RegistrationTransportFailures(Dictionary<string, bool> proof)
    {
        foreach (var failure in new[] { "network", "timeout", "malformed-json" })
        {
            var state = KnownDeviceState();
            using var handler = new FakeHandler { DeviceContext = state.Context, DirectTransportFailure = failure };
            using var client = new StaffAuthClient(Config(), handler);
            var window = DeviceWindow(client, state, registrationSmsRequired: false); RegistrationFields(window);
            var accepted = false; window.Authenticated += _ => accepted = true;
            var heartbeatCount = 0;
            var heartbeat = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) };
            heartbeat.Tick += (_, _) => heartbeatCount++;
            heartbeat.Start(); var elapsed = Stopwatch.StartNew();
            try
            {
                Click(Field<Button>(window, "_submit"));
                if (failure == "timeout") Check(proof, "real-fifteen-second-http-timeout-keeps-request-single-and-input-disabled",
                    Busy(window) && !Field<Button>(window, "_submit").IsEnabled && handler.TotalCalls == 1);
                await Until(() => !Busy(window), timeoutSeconds: 22);
            }
            finally { heartbeat.Stop(); elapsed.Stop(); }
            Check(proof, failure + "-registration-keeps-all-input-after-failure",
                window.IsVisible && Field<ComboBox>(window, "_mode").SelectedIndex == 1 && PasswordsRetained(window) &&
                Field<TextBox>(window, "_iin").Text == FixtureIin && Field<TextBox>(window, "_phone").Text == FixturePhone &&
                Field<TextBox>(window, "_name").Text == "DEV synthetic employee");
            Check(proof, failure + "-does-not-authorize-replay-or-send-sms",
                !accepted && state.Grant == null && handler.TotalCalls == 1 && handler.WithoutSmsCalls == 1 && handler.OtpCalls == 0 &&
                Field<Button>(window, "_request").Visibility == Visibility.Collapsed);
            Check(proof, failure + "-releases-cash-work-and-enables-manual-retry",
                window.WindowStyle != WindowStyle.None && Field<Button>(window, "_submit").IsEnabled &&
                Field<TextBlock>(window, "_status").Text.Contains("недоступ", StringComparison.Ordinal));
            if (failure == "timeout") Check(proof, "real-http-timeout-is-bounded-and-dispatcher-remains-live",
                elapsed.Elapsed >= TimeSpan.FromSeconds(14) && elapsed.Elapsed < TimeSpan.FromSeconds(22) && heartbeatCount >= 5);
            window.Close(); await Idle();
            Check(proof, failure + "-dismissal-clears-passwords",
                !window.IsVisible && Field<PasswordBox>(window, "_password").Password.Length == 0 && Field<PasswordBox>(window, "_passwordConfirmation").Password.Length == 0);
        }
    }
    private static async Task InvalidRegistrationResponse(Dictionary<string, bool> proof)
    {
        foreach (var fault in new[] { "unknown-status", "missing-status", "missing-grant", "missing-identity" })
        {
            var state = DeviceState();
            using var handler = new FakeHandler { DeviceContext = state.Context, DirectResponseFault = fault };
            using var client = new StaffAuthClient(Config(), handler);
            var window = DeviceWindow(client, state, registrationSmsRequired: false); RegistrationFields(window);
            var authenticated = false; window.Authenticated += _ => authenticated = true;
            Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
            Check(proof, fault + "-is-not-a-success-and-keeps-passwords",
                window.IsVisible && Field<ComboBox>(window, "_mode").SelectedIndex == 1 && PasswordsRetained(window) &&
                Field<TextBox>(window, "_iin").Text == FixtureIin && Field<TextBox>(window, "_phone").Text == FixturePhone &&
                !authenticated && state.Grant == null);
            Check(proof, fault + "-never-sends-sms-or-replays", handler.TotalCalls == 1 && handler.OtpCalls == 0);
            window.Dismiss();
        }
    }
    private static async Task LoginSuccessStatuses(Dictionary<string, bool> proof)
    {
        foreach (var deviceRoute in new[] { false, true })
        foreach (var responseStatus in new string?[] { null, "", "unexpected", "registered", "pending", "authenticated" })
        {
            var deviceState = KnownDeviceState(); var deviceContext = deviceState.Context!;
            var context = deviceRoute ? deviceContext.StandardNContext! : Context();
            var legacyState = State(context); var legacyPolicy = new StaffPromptPolicy();
            using var handler = new FakeHandler { DeviceContext = deviceContext, ObservedContext = context,
                OverrideLoginResponse = responseStatus != "authenticated", LoginResponseStatus = responseStatus };
            using var client = new StaffAuthClient(Config(), handler);
            var window = deviceRoute ? DeviceWindow(client, deviceState) : Window(client, context, true);
            var prefix = (deviceRoute ? "device-" : "legacy-") + (responseStatus == null ? "null" : responseStatus.Length == 0 ? "empty" : responseStatus);
            Check(proof, prefix + "-starts-with-nonblocking-presentation", window.WindowStyle == WindowStyle.SingleBorderWindow && !window.Topmost);
            var authenticatedEvent = false; var accepted = false; StaffPromptBypassReason? bypass = null;
            window.BypassRequested += reason => bypass = reason;
            window.Authenticated += response => {
                authenticatedEvent = true;
                accepted = deviceRoute
                    ? deviceState.Accept(deviceState.Generation, deviceContext, response, DateTimeOffset.UtcNow)
                    : legacyPolicy.AcceptAuthentication(legacyState, legacyState.Generation, legacyState.Observation.Session!.Key, context, response, DateTimeOffset.UtcNow);
                if (accepted) window.Dismiss();
            };
            FillLogin(window); Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
            Check(proof, prefix + "-one-correct-login-request-without-sms-or-retry", handler.TotalCalls == 1 && handler.OtpCalls == 0 &&
                (deviceRoute ? handler.DeviceLoginCalls == 1 && handler.DeviceBodyMatched : handler.LoginContextMatched));
            if (responseStatus == "authenticated")
            {
                Check(proof, prefix + "-valid-grant-still-closes-and-clears-password", authenticatedEvent && accepted && !window.IsVisible &&
                    Field<PasswordBox>(window, "_password").Password.Length == 0 && bypass == null);
                continue;
            }
            Check(proof, prefix + "-no-grant-and-input-retained", !authenticatedEvent && !accepted && deviceState.Grant == null && legacyState.Grant == null &&
                window.IsVisible && Field<TextBox>(window, "_iin").Text == FixtureIin && Field<PasswordBox>(window, "_password").Password == FixturePassword &&
                Field<ComboBox>(window, "_mode").SelectedIndex == 0 && Field<Button>(window, "_request").Visibility == Visibility.Collapsed);
            var message = Field<TextBlock>(window, "_status").Text;
            if (responseStatus == "pending")
            {
                Check(proof, prefix + "-known-pending-flow-is-unchanged", bypass == null &&
                    message.Contains(deviceRoute ? "Назначение аптеки" : "Профиль ожидает", StringComparison.Ordinal) &&
                    window.WindowStyle == WindowStyle.SingleBorderWindow && !window.Topmost);
                window.Dismiss();
            }
            else
            {
                Check(proof, prefix + "-malformed-success-is-service-failure-not-pending", bypass == StaffPromptBypassReason.ServiceUnavailable &&
                    message.Contains("Сервис временно недоступен", StringComparison.Ordinal) && !message.Contains("SMS", StringComparison.OrdinalIgnoreCase) &&
                    !message.Contains("ожидает", StringComparison.OrdinalIgnoreCase) && !message.Contains("Назначение аптеки", StringComparison.Ordinal));
                Check(proof, prefix + "-required-presentation-released-and-manual-retry-enabled", window.WindowStyle != WindowStyle.None &&
                    Field<Button>(window, "_continueWithoutSignIn").Visibility == Visibility.Visible && Field<Button>(window, "_submit").IsEnabled);
                window.Close(); await Idle();
                Check(proof, prefix + "-ordinary-close-clears-password", !window.IsVisible && Field<PasswordBox>(window, "_password").Password.Length == 0);
            }
        }
    }

    private static async Task NonOtpRetryAction(Dictionary<string, bool> proof)
    {
        foreach (var registration in new[] { false, true })
        {
            var state = DeviceState();
            using var handler = new FakeHandler { DeviceContext = state.Context, RateLimitedRequests = true, RetryAction = "REQUEST_NEW_OTP" };
            using var client = new StaffAuthClient(Config(), handler);
            var window = DeviceWindow(client, state, registrationSmsRequired: false);
            if (registration) RegistrationFields(window); else FillLogin(window);
            Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
            var message = Field<TextBlock>(window, "_status").Text;
            Check(proof, (registration ? "registration" : "login") + "-does-not-trust-otp-action-outside-otp-flow",
                !message.Contains("SMS", StringComparison.OrdinalIgnoreCase) && !Field<DispatcherTimer>(window, "_statusTimer").IsEnabled &&
                handler.TotalCalls == 1 && handler.OtpCalls == 0 && state.Grant == null &&
                Field<Button>(window, "_request").Visibility == Visibility.Collapsed);
            if (registration) Check(proof, "registration-rate-limit-keeps-both-passwords", PasswordsRetained(window));
            window.Dismiss();
        }
    }

    private static async Task ErrorRevealsWithoutFocus(Dictionary<string, bool> proof)
    {
        var state = DeviceState(); using var handler = new FakeHandler { DeviceContext = state.Context };
        using var client = new StaffAuthClient(Config(), handler);
        var window = DeviceWindow(client, state, registrationSmsRequired: false);
        window.Width = 520; window.Height = 480;
        RegistrationFields(window); Field<PasswordBox>(window, "_passwordConfirmation").Password = "different";
        Click(Field<Button>(window, "_submit")); await Until(() => !Busy(window));
        var form = Field<StackPanel>(window, "_form");
        var scroll = Visuals<ScrollViewer>(window).Single(view => view.Content is Grid canvas && canvas.Children.Contains(form));
        var viewport = Visuals<ScrollContentPresenter>(scroll).Single(view => ReferenceEquals(view.TemplatedParent, scroll));
        var submit = Field<Button>(window, "_submit"); var status = Field<TextBlock>(window, "_status");
        // Place only the button at the bottom of the viewport, reproducing the screenshot
        // finding. There is deliberately no status BringIntoView in this regression.
        var buttonBottom = submit.TransformToAncestor(viewport).Transform(new Point(0, submit.ActualHeight)).Y;
        scroll.ScrollToVerticalOffset(scroll.VerticalOffset + buttonBottom - viewport.ActualHeight + 2);
        await Idle(); window.UpdateLayout();
        var statusBefore = status.TransformToAncestor(viewport).Transform(new Point(0, 0)).Y;
        var submitBefore = submit.TransformToAncestor(viewport).Transform(new Point(0, 0)).Y;
        Check(proof, "precondition-submit-visible-and-previous-error-below-fold", submitBefore >= 0 &&
            submitBefore + submit.ActualHeight <= viewport.ActualHeight + 1 && statusBefore >= viewport.ActualHeight);
        Field<PasswordBox>(window, "_passwordConfirmation").Password = FixturePassword;
        Field<TextBox>(window, "_name").Clear();
        scroll.Focusable = true; scroll.Focus(); await Idle();
        var focusBefore = System.Windows.Input.Keyboard.FocusedElement; var activeBefore = window.IsActive;
        var offsetBefore = scroll.VerticalOffset;
        Click(submit); await Until(() => !Busy(window));
        var bounds = status.TransformToAncestor(viewport).TransformBounds(new Rect(status.RenderSize));
        Check(proof, "new-error-becomes-fully-visible-without-test-scrolling", bounds.Top >= -1 && bounds.Bottom <= viewport.ActualHeight + 1 &&
            scroll.VerticalOffset > offsetBefore && status.Text.Contains("ФИО", StringComparison.Ordinal));
        Check(proof, "error-reveal-keeps-existing-focus-and-window-activation", ReferenceEquals(focusBefore, System.Windows.Input.Keyboard.FocusedElement) &&
            window.IsActive == activeBefore && !status.IsKeyboardFocusWithin);
        Check(proof, "error-reveal-keeps-passwords-and-does-not-submit", PasswordsRetained(window) && handler.TotalCalls == 0);
        SaveOwnWindow(window, "14-registration-error-auto-revealed");
    }

    private static Recommendation Offer(string id, int price = 1234, int bonus = 45) => new() {
        EventId = "DEV-EVENT-" + id, RuleId = "DEV-RULE-" + id, Kind = "substitution",
        TriggerIpartId = id, TriggerName = "DEV synthetic trigger " + id,
        RecommendSku = "DEV-SKU-" + id, RecommendName = "DEV synthetic offer " + id,
        RecommendPrice = price, Bonus = bonus,
        Balance = new ProductBalance { Status = "confirmed", PharmacyAvailableQty = 10, ObservedAt = DateTimeOffset.UtcNow },
    };
    private static RecommendationWindow RecommendationPopup(List<Recommendation> offers, int lifetime = 0, bool show = true)
    {
        var window = new RecommendationWindow(offers, lifetime, _screen);
        RecommendationWindows.Add(window);
        if (show) window.Show();
        return window;
    }
    private static IEnumerable<T> Visuals<T>(DependencyObject root) where T : DependencyObject
    {
        if (root is T match) yield return match;
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
            foreach (var child in Visuals<T>(VisualTreeHelper.GetChild(root, index))) yield return child;
    }
    private static string RowText(RecommendationWindow window, string name) =>
        Visuals<TextBlock>(window).Single(text => text.Name == name && text.IsVisible).Text;
    private static async Task RecommendationEmptyRefresh(Dictionary<string, bool> proof)
    {
        foreach (var failed in new[] { false, true }) {
            var window = RecommendationPopup([Offer("A")]); await Idle();
            Check(proof, (failed ? "failed" : "empty") + "-starts-with-real-visible-popup", window.IsVisible && window.Recommendations.Count == 1);
            var refreshed = RecommendationVisibility.Reconcile(window.Recommendations, failed ? null : Array.Empty<Recommendation>(), _ => true);
            window.UpdateRecommendations(refreshed); await Idle();
            Check(proof, (failed ? "failed" : "empty") + "-authoritative-refresh-closes-old-offer", !window.IsVisible && window.Recommendations.Count == 0);
        }
    }
    private static async Task RecommendationPartialRemoval(Dictionary<string, bool> proof)
    {
        var a = Offer("A"); var b = Offer("B"); var window = RecommendationPopup([a, b]); await Idle();
        var handle = new System.Windows.Interop.WindowInteropHelper(window).Handle;
        var remaining = RecommendationVisibility.ForCart(window.Recommendations, offer => offer.TriggerIpartId == "B");
        window.UpdateRecommendations(remaining, freshResponse: false); await Idle();
        Check(proof, "partial-removal-keeps-same-native-window-and-same-offer", window.IsVisible &&
            new System.Windows.Interop.WindowInteropHelper(window).Handle == handle && window.Recommendations.Count == 1 && ReferenceEquals(window.Current, b));
        var reconciled = RecommendationVisibility.Reconcile(window.Recommendations, [Offer("B")], _ => true);
        window.UpdateRecommendations(reconciled); await Idle();
        Check(proof, "already-shown-but-still-open-offer-is-not-suppressed", window.IsVisible && ReferenceEquals(window.Current, b) &&
            RowText(window, "NameText") == b.RecommendName);
        SaveOwnWindow(window, "12-recommendation-partial-removal");
    }
    private static async Task RecommendationStockRefresh(Dictionary<string, bool> proof)
    {
        var original = Offer("A"); var window = RecommendationPopup([original]); await Idle();
        foreach (var status in new[] { "unknown", "estimated", "stale" }) {
            var updated = Offer("A", price: 9999, bonus: 999);
            updated.Balance = new ProductBalance { Status = status, PharmacyAvailableQty = 5,
                ObservedAt = status == "stale" ? DateTimeOffset.UtcNow.AddDays(-30) : null };
            window.UpdateRecommendations(RecommendationVisibility.Reconcile(window.Recommendations, [updated], _ => true)); await Idle();
            var prefix = status switch { "estimated" => "Расчётный остаток", "stale" => "Остаток устарел", _ => "Остаток не подтверждён" };
            Check(proof, status + "-stock-state-is-rendered-honestly", RowText(window, "StockText").StartsWith(prefix, StringComparison.Ordinal));
            Check(proof, status + "-refresh-keeps-original-financial-quote", window.Current.RecommendPrice == 1234 && window.Current.Bonus == 45 &&
                RowText(window, "PriceText") == "1 234 ₸" && RowText(window, "BonusText").Contains("45 ₸", StringComparison.Ordinal));
        }
        SaveOwnWindow(window, "13-recommendation-stale-stock");
    }
    private static async Task RecommendationLifetime(Dictionary<string, bool> proof)
    {
        var first = RecommendationPopup([Offer("A")], lifetime: 2); var firstElapsed = Stopwatch.StartNew();
        await Task.Delay(1300);
        first.UpdateRecommendations(RecommendationVisibility.ForCart(first.Recommendations, _ => true), freshResponse: false);
        await Until(() => !first.IsVisible);
        Check(proof, "cart-only-update-does-not-restart-original-two-second-ttl", firstElapsed.Elapsed < TimeSpan.FromMilliseconds(3000));
        var second = RecommendationPopup([Offer("B")], lifetime: 2);
        await Task.Delay(1300); second.UpdateRecommendations([Offer("B")], freshResponse: true);
        await Task.Delay(1100);
        Check(proof, "fresh-success-remains-visible-after-original-deadline", second.IsVisible);
        await Until(() => !second.IsVisible);
        Check(proof, "fresh-response-still-eventually-expires", !second.IsVisible);
    }
    private static async Task RecommendationDisplayUpdate(Dictionary<string, bool> proof)
    {
        var window = RecommendationPopup([Offer("A")], show: false);
        var displays = new List<string[]>();
        // Test the real native event and current rows. MainWindow's outbox wiring is not
        // instantiated here: its constructor initializes external runtime dependencies.
        window.Displayed += _ => displays.Add(window.Recommendations.Select(offer => offer.EventId).ToArray());
        window.Show(); await Until(() => displays.Count > 0);
        window.UpdateRecommendations([Offer("A"), Offer("B")]); await Until(() => displays.Count > 1);
        Check(proof, "native-update-reannounces-current-rows-not-initial-captured-list", displays[0].SequenceEqual(["DEV-EVENT-A"]) &&
            displays[1].SequenceEqual(["DEV-EVENT-A", "DEV-EVENT-B"]));
        Check(proof, "popup-does-not-request-keyboard-activation", !window.ShowActivated && !window.Focusable);
    }

    private static void WriteProof(bool passed, string? note)
    {
        using var process = Process.GetCurrentProcess();
        File.WriteAllText(Path.Combine(Output, "qa-proof.json"), JsonSerializer.Serialize(new {
            scope = "DEV synthetic actual StaffAuthWindow and RecommendationWindow; in-process UI; fake HTTP; real production policy helpers; no MainWindow startup, backend, Standard-N, device config or SMS",
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
        public int? RetryAfterSeconds { get; init; }
        public string? RetryAction { get; init; }
        public HttpStatusCode? LoginFailure { get; init; }
        public string LoginFailureCode { get; init; } = "SYNTHETIC_FAILURE";
        public bool OverrideLoginResponse { get; init; }
        public string? LoginResponseStatus { get; init; }
        public int DeviceDelayMilliseconds { get; init; }
        public string? DirectTransportFailure { get; init; }
        public string? DirectResponseFault { get; init; }
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
                    if (RateLimitedRequests) return LimitedReply();
                    if (!registering && LoginFailure is { } failure) return Reply(new { code = LoginFailureCode, message = "PRIVATE-RAW-SYNTHETIC-TEXT" }, failure);
                    if (!registering && OverrideLoginResponse) return Reply(new StaffAuthResponse(LoginResponseStatus!, null, null,
                        new StaffIdentity("DEV-PERSON", "DEV synthetic employee"), DeviceContext: device));
                    if (direct && DirectTransportFailure == "network") throw new HttpRequestException("Synthetic offline transport.");
                    if (direct && DirectTransportFailure == "timeout") await Task.Delay(Timeout.Infinite, ct);
                    if (direct && DirectTransportFailure == "malformed-json") return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("{invalid", Encoding.UTF8, "application/json") };
                    if (direct && DirectResponseFault != null) return Reply(DirectResponseFault switch {
                        "missing-status" => new { status = (string?)null },
                        "missing-grant" => new { status = (string?)"authenticated" },
                        "missing-identity" => (object)new StaffRegistrationResponse("authenticated", "DEV-SYNTHETIC-PROCESS-GRANT",
                            DateTimeOffset.UtcNow.AddHours(1), new StaffIdentity("", "DEV synthetic employee"), DeviceContext: device),
                        _ => new { status = (string?)"unexpected" },
                    });
                    if (direct && WithoutSmsCalls <= DirectFailureStatuses.Length) return Reply(new { code = "SYNTHETIC_ERROR" }, DirectFailureStatuses[WithoutSmsCalls - 1]);
                    if (direct && WithoutSmsCalls <= DirectFailuresBeforeSuccess) return Reply(new { code = "VALIDATION_FAILED" }, HttpStatusCode.BadRequest);
                    if (!registering && UnavailableLogin) return Reply(new { code = "UPSTREAM_UNAVAILABLE" }, HttpStatusCode.ServiceUnavailable);
                    if (!registering && PendingDeviceLogin) return Reply(new StaffAuthResponse("pending", null, null,
                        new StaffIdentity("DEV-PENDING-PERSON", "DEV pending employee"), DeviceContext: device));
                    if (DeviceDelayMilliseconds > 0) await Task.Delay(DeviceDelayMilliseconds, ct);
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
                    if (RateLimitedRequests) return LimitedReply();
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
                    if (OverrideLoginResponse) return Reply(new StaffAuthResponse(LoginResponseStatus!, null, null,
                        new StaffIdentity("DEV-PERSON", "DEV synthetic employee"), ObservedContext));
                    return Reply(new StaffAuthResponse("authenticated", "DEV-SYNTHETIC-GRANT-NOT-A-VALID-TOKEN", DateTimeOffset.UtcNow.AddHours(1), new StaffIdentity("DEV-PERSON", "DEV synthetic employee"), ObservedContext));
                default:
                    UnexpectedCalls++; throw new InvalidOperationException("Unexpected fake route.");
            }
        }
        private HttpResponseMessage LimitedReply()
        {
            var limited = Reply(new { code = "OTP_TOO_MANY_ATTEMPTS", retryAction = RetryAction }, HttpStatusCode.TooManyRequests);
            if (RetryAfterSeconds is { } seconds) limited.Headers.RetryAfter = new System.Net.Http.Headers.RetryConditionHeaderValue(TimeSpan.FromSeconds(seconds));
            return limited;
        }
        private static HttpResponseMessage Reply(object body, HttpStatusCode code = HttpStatusCode.OK) => new(code) {
            Content = new StringContent(JsonSerializer.Serialize(body, Json), Encoding.UTF8, "application/json"),
        };
    }
}
