using System.Net;
using System.Text.Json;
using CustomerDisplay;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests
{
    // Compiles unchanged production MainWindow.Staff.cs. Only UI, dispatcher, the
    // OS-process observation boundary and HTTP are fakes; never starts the app/DB reader.
    public sealed class StaffOrchestrationTests
    {
        [Theory]
        [InlineData(false, 503)]
        [InlineData(false, 429)]
        [InlineData(true, 503)]
        public async Task ATransientFailureRetainsOnlyUnverifiedProofAndRecoversWithoutLogin(bool capability, int status)
        {
            using var http = new StaffHttp(); using var shell = new MainWindow();
            shell.ConfigureStaffTest(http); await shell.AcceptStaffTest();
            Assert.True(shell.StaffVerifiedTest); Assert.Null(shell.StaffWindowTest);
            var token = shell.StaffStateTest.Grant;
            if (capability) shell.StaffCapabilityOutageTest();
            else { http.Status = (HttpStatusCode)status; await shell.ValidateStaffTest(); }
            Assert.False(shell.StaffVerifiedTest); Assert.Equal(token, shell.StaffStateTest.Grant);
            Assert.Equal("session_revalidating", shell.StaffAuthenticationStatus); Assert.Equal(0, http.Logouts);
            shell.AdvanceStaffTest(TimeSpan.FromSeconds(31)); http.Status = HttpStatusCode.OK;
            shell.ObserveStaffTest(); await shell.ValidateStaffTest();
            Assert.True(shell.StaffVerifiedTest); Assert.Equal(token, shell.StaffStateTest.Grant);
            Assert.Null(shell.StaffWindowTest); Assert.Equal(0, http.Logouts);
            shell.StopStaffTest();
        }

        [Fact]
        public async Task ValidationRateLimitRespectsServerRetryAfterWithoutReplayingCredentials()
        {
            using var http = new StaffHttp { Status = HttpStatusCode.TooManyRequests, RetryAfter = 60 };
            using var shell = new MainWindow(); shell.ConfigureStaffTest(http); await shell.AcceptStaffTest();
            await shell.ValidateStaffTest(); Assert.False(shell.StaffVerifiedTest);
            shell.AdvanceStaffTest(TimeSpan.FromSeconds(31)); await shell.ValidateStaffTest();
            Assert.Equal(1, http.Validations); Assert.Equal(0, http.Logouts);
            shell.AdvanceStaffTest(TimeSpan.FromSeconds(30)); http.Status = HttpStatusCode.OK;
            await shell.ValidateStaffTest(); Assert.Equal(2, http.Validations); Assert.True(shell.StaffVerifiedTest);
            shell.StopStaffTest();
        }

        [Fact]
        public async Task InterruptedResponseStreamRetainsOnlyUnverifiedProofUntilSuccessfulValidation()
        {
            using var http = new StaffHttp(); using var shell = new MainWindow();
            shell.ConfigureStaffTest(http); await shell.AcceptStaffTest(); http.Interrupted = true;
            await shell.ValidateStaffTest();
            Assert.False(shell.StaffVerifiedTest); Assert.NotNull(shell.StaffStateTest.Grant);
            Assert.Equal("session_revalidating", shell.StaffAuthenticationStatus); Assert.Equal(0, http.Logouts);
            shell.AdvanceStaffTest(TimeSpan.FromSeconds(31)); http.Interrupted = false; await shell.ValidateStaffTest();
            Assert.True(shell.StaffVerifiedTest); Assert.Null(shell.StaffWindowTest); shell.StopStaffTest();
        }

        [Fact]
        public async Task ACheckInFlightIsNotAuthenticationAndManualLogoutRejectsItsLateReply()
        {
            using var http = new StaffHttp(); using var shell = new MainWindow();
            shell.ConfigureStaffTest(http); await shell.AcceptStaffTest();
            http.Hold = new(TaskCreationOptions.RunContinuationsAsynchronously);
            var check = shell.ValidateStaffTest(); await http.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
            Assert.False(shell.StaffVerifiedTest); Assert.Equal("session_revalidating", shell.StaffAuthenticationStatus);
            await shell.LogoutStaffTest(); http.Hold.SetResult(); await check;
            Assert.Null(shell.StaffStateTest.Grant); Assert.False(shell.StaffVerifiedTest);
            Assert.NotNull(shell.StaffWindowTest); Assert.Equal(1, http.Logouts);
            shell.StopStaffTest();
        }

        [Theory]
        [InlineData("disabled")]
        [InlineData("process")]
        [InlineData("cashier")]
        [InlineData("expired")]
        public async Task LateValidationCannotUndoAuthoritativeInvalidation(string change)
        {
            using var http = new StaffHttp(); using var shell = new MainWindow();
            shell.ConfigureStaffTest(http, knownSource: true); await shell.AcceptStaffTest();
            http.Hold = new(TaskCreationOptions.RunContinuationsAsynchronously);
            var check = shell.ValidateStaffTest(); await http.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
            shell.InvalidateStaffTest(change); http.Hold.SetResult(); await check;
            Assert.False(shell.StaffVerifiedTest); Assert.Null(shell.StaffStateTest.Grant);
            Assert.NotEqual("authenticated", shell.StaffAuthenticationStatus);
            shell.StopStaffTest();
        }

        [Fact]
        public async Task RecoveryExpiresFromFirstFailureAndReopensOnlyOneClosablePrompt()
        {
            using var http = new StaffHttp(); using var shell = new MainWindow();
            shell.ConfigureStaffTest(http); await shell.AcceptStaffTest(); shell.StaffCapabilityOutageTest();
            var deadline = shell.StaffStateTest.RecoveryDeadline;
            shell.AdvanceStaffTest(TimeSpan.FromSeconds(61)); shell.StaffCapabilityOutageTest();
            Assert.Equal(deadline, shell.StaffStateTest.RecoveryDeadline);
            // Real loop order: capability failure arrives BEFORE source observation.
            shell.AdvanceStaffTest(TimeSpan.FromSeconds(60)); shell.StaffCapabilityOutageTest(); shell.ObserveStaffTest();
            Assert.Null(shell.StaffStateTest.Grant); var prompt = shell.StaffWindowTest;
            Assert.NotNull(prompt); Assert.False(prompt.Required);
            shell.ObserveStaffTest(); Assert.Same(prompt, shell.StaffWindowTest);
            prompt.Close(); shell.ObserveStaffTest(); Assert.Null(shell.StaffWindowTest);
            shell.StopStaffTest();
        }

        [Fact]
        public void ServiceRecoveryWithNoGrantOrOpenFormOffersOneOrdinaryPrompt()
        {
            using var http = new StaffHttp(); using var shell = new MainWindow(); shell.ConfigureStaffTest(http);
            shell.StaffWindowTest!.Dismiss(); shell.StaffCapabilityOutageTest();
            shell.ObserveStaffTest(); Assert.Null(shell.StaffWindowTest);
            shell.StaffCapabilityHealthyTest(); shell.ObserveStaffTest(); var prompt = shell.StaffWindowTest;
            Assert.NotNull(prompt); Assert.False(prompt.Required); Assert.Null(shell.StaffStateTest.Grant);
            shell.StaffCapabilityHealthyTest(); shell.ObserveStaffTest(); Assert.Same(prompt, shell.StaffWindowTest);
            shell.StopStaffTest();
        }

        [Fact]
        public void ExplicitUserDismissalDuringAnOutageDoesNotCreateAnAutomaticPromptLoop()
        {
            using var http = new StaffHttp(); using var shell = new MainWindow(); shell.ConfigureStaffTest(http);
            shell.StaffCapabilityOutageTest(); shell.StaffWindowTest!.Close();
            shell.StaffCapabilityHealthyTest(); shell.ObserveStaffTest();
            Assert.Null(shell.StaffWindowTest); Assert.Null(shell.StaffStateTest.Grant); shell.StopStaffTest();
        }

        [Theory]
        [InlineData(401)]
        [InlineData(403)]
        public async Task DefinitiveDenialClearsTheGrantAndReprompts(int status)
        {
            using var http = new StaffHttp(); using var shell = new MainWindow();
            shell.ConfigureStaffTest(http); await shell.AcceptStaffTest(); http.Status = (HttpStatusCode)status;
            await shell.ValidateStaffTest();
            Assert.Null(shell.StaffStateTest.Grant); Assert.NotNull(shell.StaffWindowTest);
            shell.StopStaffTest();
        }

        [Fact]
        public async Task OneUncertainProcessReadRecoversAtTheActualParentHandoffWithoutRevocation()
        {
            using var http = new StaffHttp(); using var shell = new MainWindow();
            shell.ConfigureStaffTest(http); var reads = 0;
            shell.StaffProbeTest = () => Interlocked.Increment(ref reads) == 1 ? new("process_unavailable") : shell.StaffPresenceTest;
            await shell.AcceptStaffTest();
            Assert.True(shell.StaffVerifiedTest); Assert.Null(shell.StaffWindowTest);
            Assert.Equal(2, reads); Assert.Equal(0, http.Logouts); shell.StopStaffTest();
        }

        [Fact]
        public async Task AConfirmedDifferentProcessRejectsImmediatelyAndRevokesTheReturnedGrant()
        {
            using var http = new StaffHttp(); using var shell = new MainWindow();
            shell.ConfigureStaffTest(http); var reads = 0;
            shell.StaffProbeTest = () => { reads++; return new("resolved", "different-process"); };
            await shell.AcceptStaffTest();
            Assert.False(shell.StaffVerifiedTest); Assert.True(shell.StaffWindowTest!.IsVisible);
            Assert.Equal(1, reads); Assert.Equal(1, http.Logouts); shell.StopStaffTest();
        }

        [Fact]
        public async Task DismissalDuringFreshProcessConfirmationCannotAcceptTheReturnedGrant()
        {
            using var http = new StaffHttp(); using var shell = new MainWindow();
            shell.ConfigureStaffTest(http);
            var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            shell.StaffProbeTest = () => { entered.TrySetResult(); return new("process_unavailable"); };
            var result = shell.AcceptStaffTest(); await entered.Task.WaitAsync(TimeSpan.FromSeconds(5));
            shell.StaffWindowTest!.Close();
            await Assert.ThrowsAnyAsync<OperationCanceledException>(() => result);
            Assert.Null(shell.StaffStateTest.Grant); Assert.Equal(1, http.Logouts); shell.StopStaffTest();
        }

        [Fact]
        public void PendingAssignmentSurvivesClosingAndDoesNotBecomeIdentityUnavailable()
        {
            using var http = new StaffHttp(); using var shell = new MainWindow();
            shell.ConfigureStaffTest(http); shell.StaffWindowTest!.Pending(); shell.StaffWindowTest.Close();
            shell.StaffCapabilityOutageTest(); shell.StaffCapabilityHealthyTest(); shell.ObserveStaffTest();
            Assert.Equal("pending_approval", shell.StaffAuthenticationStatus);
            Assert.Null(shell.StaffWindowTest); Assert.Null(shell.StaffStateTest.Grant); shell.StopStaffTest();
        }
    }

    public sealed class StaffHttp : HttpMessageHandler
    {
        public HttpStatusCode Status = HttpStatusCode.OK;
        public int Logouts, Validations;
        public int? RetryAfter;
        public bool Interrupted;
        public DateTimeOffset ExpiresAt;
        public TaskCompletionSource? Hold;
        public TaskCompletionSource Started = new(TaskCreationOptions.RunContinuationsAsynchronously);
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            if (request.RequestUri!.AbsolutePath.EndsWith("/logout", StringComparison.Ordinal)) {
                Logouts++; return new(HttpStatusCode.OK) { Content = new StringContent("{}") };
            }
            Assert.EndsWith("/session/check", request.RequestUri.AbsolutePath); Validations++;
            var context = JsonSerializer.Deserialize<JsonElement>(await request.Content!.ReadAsStringAsync(ct));
            Started.TrySetResult(); if (Hold != null) await Hold.Task.WaitAsync(ct);
            if (Interrupted) return new(HttpStatusCode.OK) { Content = new InterruptedContent() };
            var response = new HttpResponseMessage(Status) { Content = new StringContent(JsonSerializer.Serialize(new {
                status = "authenticated", deviceContext = context, expiresAt = ExpiresAt,
                pharmacist = new { id = "synthetic-person", name = "Synthetic Person" },
            })) };
            if (RetryAfter.HasValue) response.Headers.RetryAfter = new(TimeSpan.FromSeconds(RetryAfter.Value));
            return response;
        }
        private sealed class InterruptedContent : HttpContent
        {
            protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context) => throw new IOException("Synthetic interrupted response.");
            protected override bool TryComputeLength(out long length) { length = 0; return false; }
        }
    }
}

namespace CustomerDisplay
{
    public partial class MainWindow
    {
        private DateTimeOffset _staffTestNow = DateTimeOffset.Parse("2026-11-10T00:00:00Z");
        private StaffSessionObservation _staffTestObservation = new("database_unavailable");
        private const string StaffTestProcess = "42:639296064000000000";
        private System.Windows.Forms.Screen PharmacistScreen() => new();
        internal StaffProcessPresence StaffPresenceTest => new("resolved", StaffTestProcess);
        internal StaffDeviceSessionState StaffStateTest => _staffSession;
        internal bool StaffVerifiedTest => _staffSession.IsAuthenticated(_staffTestNow);
        internal StaffAuthWindow? StaffWindowTest => _staffWindow;
        internal Func<StaffProcessPresence> StaffProbeTest { set => _staffProcessProbe = value; }
        internal void ConfigureStaffTest(Core.Tests.StaffHttp http, bool knownSource = false)
        {
            _staffClock = () => _staffTestNow; _staffProcessProbe = () => StaffPresenceTest;
            if (knownSource) _staffTestObservation = new("resolved", new(StaffTestProcess, "user-1", "session-1", "workstation-1", "000101500011", "available"));
            http.ExpiresAt = _staffTestNow.AddHours(8);
            _staffClient = new StaffAuthClient(new EpharmConfig {
                BackendBaseUrl = "https://staff.invalid", DeviceId = "synthetic-device", DeviceKey = "synthetic-key",
            }, http);
            SetStaffCapability(true); ObserveStaffTest();
        }
        internal Task AcceptStaffTest() => _staffWindow!.Reply(new("authenticated", "synthetic-grant-not-valid-on-any-server",
            _staffTestNow.AddHours(8), new("synthetic-person", "Synthetic Person"), DeviceContext: _staffSession.Context));
        internal void ObserveStaffTest() => ApplyStaffObservation(StaffPresenceTest, _staffTestObservation);
        internal void InvalidateStaffTest(string change)
        {
            switch (change) {
                case "disabled": ApplyStaffCapability(new(false, "server_disabled")); break;
                case "process": ApplyStaffObservation(new("resolved", "other-process"), new("database_unavailable")); break;
                case "cashier": ApplyStaffObservation(StaffPresenceTest, new("resolved", new(StaffTestProcess, "other-user", "session-1", "workstation-1"))); break;
                default: _staffTestNow += TimeSpan.FromHours(9); ObserveStaffTest(); break;
            }
        }
        internal Task ValidateStaffTest() => ValidateStaffGrant(CancellationToken.None);
        internal void StaffCapabilityOutageTest() => ApplyStaffCapability(new(false, "endpoint_unavailable"));
        internal void StaffCapabilityHealthyTest() => ApplyStaffCapability(new(true, "enabled", false));
        internal Task LogoutStaffTest() => LogoutStaff();
        internal void AdvanceStaffTest(TimeSpan elapsed) => _staffTestNow += elapsed;
        internal void StopStaffTest() => StopStaffAuthentication();
    }

    // The real window is independently exercised by App.Ui.Tests. These boundaries
    // permit testing the actual parent orchestration on Linux and Windows without WPF.
    public sealed class StaffAuthWindow : System.Windows.Window
    {
        public StaffAuthWindow(StaffAuthClient client, StaffAuthContext? context, System.Windows.Forms.Screen screen,
            string? sessionReason = null, bool requireSignIn = false, StaffDeviceContext? deviceContext = null,
            bool registrationSmsRequired = true, bool kazakh = false) { Required = requireSignIn; Closed += (_, _) => _stop.Cancel(); }
        private readonly CancellationTokenSource _stop = new();
        public event Action<bool>? LanguageChanged { add { } remove { } }
        public event Action? AuthenticationStarted;
        public event Action? AssignmentPending;
        public event Action<StaffPromptBypassReason>? BypassRequested { add { } remove { } }
        public Func<StaffAuthResponse, Task>? AuthenticationCompletion { get; set; }
        public CancellationToken ClosedToken => _stop.Token;
        public bool Required { get; private set; }
        public bool IsRequestPending { get; private set; }
        public bool WasDismissed { get; private set; }
        public async Task Reply(StaffAuthResponse response)
        {
            IsRequestPending = true; AuthenticationStarted?.Invoke();
            try { await AuthenticationCompletion!(response); } finally { IsRequestPending = false; }
        }
        public void Pending() { AssignmentPending?.Invoke(); ReleaseWork(); }
        public void SetRegistrationSmsRequired(bool required) { }
        public void UpdateSourceContext(StaffAuthContext? context) { }
        public void ReleaseWork(string? message = null, string? kazakhMessage = null) => Required = false;
        public void Dismiss() { WasDismissed = true; Close(); }
    }
}
namespace System.Windows
{
    public enum WindowState { Normal, Minimized }
    public enum TextWrapping { Wrap }
    public readonly struct Thickness { public Thickness(double value) { } }
    public class Window
    {
        public string? Title { get; set; }
        public double Width { get; set; }
        public double Height { get; set; }
        public bool ShowActivated { get; set; }
        public object? Content { get; set; }
        public WindowState WindowState { get; set; }
        public bool IsVisible { get; private set; }
        public event EventHandler? Closed;
        public void Show() => IsVisible = true;
        public void Activate() { }
        public void Close() { IsVisible = false; Closed?.Invoke(this, EventArgs.Empty); }
    }
}
namespace System.Windows.Controls
{
    public sealed class TextBlock
    {
        public string? Text { get; set; }
        public System.Windows.TextWrapping TextWrapping { get; set; }
        public System.Windows.Thickness Margin { get; set; }
        public double FontSize { get; set; }
    }
}
namespace System.Drawing { public static class SystemIcons { public static object Information { get; } = new(); } }
namespace System.Windows.Forms
{
    public sealed class NotifyIcon : IDisposable
    {
        public object? Icon { get; set; }
        public string? Text { get; set; }
        public bool Visible { get; set; }
        public ContextMenuStrip? ContextMenuStrip { get; set; }
        public event EventHandler? DoubleClick { add { } remove { } }
        public void Dispose() { }
    }
    public sealed class ContextMenuStrip { public MenuItems Items { get; } = new(); }
    public sealed class MenuItems : List<MenuItem>
    {
        public void Add(string text, object? image, EventHandler click) => Add(new MenuItem { Text = text });
    }
    public sealed class MenuItem { public string? Text { get; set; } }
}
