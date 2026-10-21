using System;
using System.Diagnostics;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;

namespace CustomerDisplay;

public partial class MainWindow
{
    private StaffAuthClient? _staffClient;
    private StandardNDbLookup? _staffLookup;
    private readonly StaffSessionState _staffSession = new();
    private readonly CancellationTokenSource _staffStop = new();
    private System.Windows.Forms.NotifyIcon? _staffTray;
    private StaffAuthWindow? _staffWindow;
    private Window? _staffStatusWindow;
    private bool _staffPrompted;
    private volatile bool _staffEnabled;
    private DateTimeOffset _staffLastValidation;
    private volatile string _staffAuthenticationStatus = "initializing";
    public string StaffAuthenticationStatus => _staffAuthenticationStatus;

    private void StartStaffAuthentication()
    {
        if (!EpharmConfig.StaffAuthenticationReleaseEnabled || _epharm == null || _posmConfig?.StaffAuthenticationEnabled != true)
        {
            SetStaffAuthenticationStatus("local_disabled");
            return;
        }
        try
        {
            _staffClient = new StaffAuthClient(_posmConfig);
            _staffLookup = new StandardNDbLookup(_posmConfig, _ => { });
            _ = Task.Run(() => StaffObservationLoop(_staffStop.Token));
        }
        catch { SetStaffAuthenticationStatus("endpoint_unavailable"); }
    }

    private async Task StaffObservationLoop(CancellationToken ct)
    {
        var nextCapabilities = DateTimeOffset.MinValue;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (DateTimeOffset.UtcNow >= nextCapabilities)
                {
                    var capability = new StaffCapabilityCheck(false, "endpoint_unavailable");
                    try { capability = await _staffClient!.CheckCapabilitiesAsync(ct).ConfigureAwait(false); }
                    catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
                    catch { }
                    await Dispatcher.InvokeAsync(() => {
                        SetStaffCapability(capability.Enabled);
                        if (!capability.Enabled) SetStaffAuthenticationStatus(capability.Status);
                    });
                    nextCapabilities = DateTimeOffset.UtcNow.AddSeconds(60);
                }
                if (_staffEnabled)
                {
                    var process = ObserveStaffProcess();
                    var observation = process.Status == "resolved"
                        ? _staffLookup?.ObserveStaffSession(process.Identity!) ?? new StaffSessionObservation("database_unavailable")
                        : new StaffSessionObservation(process.Status);
                    // Never publish a DB observation after the cashier process changed meanwhile.
                    var checkedProcess = ObserveStaffProcess();
                    if (checkedProcess.Identity != process.Identity || checkedProcess.Status != process.Status)
                        observation = new("process_changing");
                    await Dispatcher.InvokeAsync(() => ApplyStaffObservation(observation));
                    await ValidateStaffGrant(ct).ConfigureAwait(false);
                }
                await Task.Delay(TimeSpan.FromSeconds(2), ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
            catch
            {
                // Optional sign-in cannot terminate the reader, sale queue, videos or watchdog.
                try { await Task.Delay(TimeSpan.FromSeconds(5), ct).ConfigureAwait(false); }
                catch (OperationCanceledException) { return; }
            }
        }
    }

    private void SetStaffAuthenticationStatus(string status)
    {
        var safeStatus = StaffAuthDiagnostics.Normalize(status);
        if (_staffAuthenticationStatus == safeStatus) return;
        _staffAuthenticationStatus = safeStatus;
        Log("Состояние входа сотрудника ePharm: " + safeStatus);
    }

    private static (string Status, string? Identity) ObserveStaffProcess()
    {
        try
        {
            using var current = Process.GetCurrentProcess();
            var values = new System.Collections.Generic.List<string>();
            foreach (var name in new[] { "zkassa", "kassir" })
                foreach (var process in Process.GetProcessesByName(name))
                    using (process)
                    {
                        if (process.SessionId != current.SessionId) continue;
                        values.Add(process.Id.ToString(System.Globalization.CultureInfo.InvariantCulture) + ":" +
                            process.StartTime.ToUniversalTime().Ticks.ToString(System.Globalization.CultureInfo.InvariantCulture));
                    }
            return values.Count switch { 0 => ("no_process", null), 1 => ("resolved", values[0]), _ => ("ambiguous_process", null) };
        }
        catch { return ("process_unavailable", null); }
    }

    private void SetStaffCapability(bool enabled)
    {
        if (_staffStop.IsCancellationRequested || enabled == _staffEnabled) return;
        _staffEnabled = enabled;
        if (!enabled)
        {
            _staffSession.Observe(new("service_disabled"));
            _staffWindow?.Close(); _staffStatusWindow?.Close();
            _staffTray?.Dispose(); _staffTray = null; _staffPrompted = false;
            return;
        }
        _staffTray = new System.Windows.Forms.NotifyIcon {
            Icon = System.Drawing.SystemIcons.Information, Text = "Сотрудник ePharm — вход", Visible = true };
        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("Вход / регистрация сотрудника", null, (_, _) => OpenStaffWindow(activate: true));
        menu.Items.Add("Выйти из учётной записи ePharm", null, async (_, _) => await LogoutStaff());
        _staffTray.ContextMenuStrip = menu;
        _staffTray.DoubleClick += (_, _) => OpenStaffWindow(activate: true);
        Log("Вход сотрудника ePharm включён сервером; кассовые функции продолжают работать независимо.");
    }

    private void ApplyStaffObservation(StaffSessionObservation observation)
    {
        if (_staffStop.IsCancellationRequested || !_staffEnabled) return;
        var hadGrant = _staffSession.Grant != null;
        var wasAuthenticated = _staffSession.IsAuthenticated(DateTimeOffset.UtcNow);
        if (hadGrant && !wasAuthenticated) _staffPrompted = false;
        var oldToken = _staffSession.Grant;
        if (_staffSession.Observe(observation))
        {
            _staffWindow?.Close(); _staffStatusWindow?.Close(); _staffPrompted = false;
            _staffLastValidation = DateTimeOffset.MinValue;
            if (oldToken != null) _ = RevokeOldStaffGrant(oldToken);
            // IDs are sufficient for auditing; never emit IIN, name, phone, password or token.
            Log("Сессия сотрудника ePharm обновлена: " + observation.Status);
        }
        if (_staffTray != null)
            _staffTray.Text = _staffSession.IsAuthenticated(DateTimeOffset.UtcNow)
                ? "Сотрудник ePharm — вход подтверждён" : "Сотрудник ePharm — требуется вход";
        SetStaffAuthenticationStatus(StaffAuthDiagnostics.Observation(observation.Status,
            _staffSession.IsAuthenticated(DateTimeOffset.UtcNow), _staffWindow?.IsVisible == true));
        if (observation.Status != "no_process" && !_staffPrompted && !_staffSession.IsAuthenticated(DateTimeOffset.UtcNow))
        {
            _staffPrompted = true;
            OpenStaffWindow();
        }
        if (wasAuthenticated && !_staffSession.IsAuthenticated(DateTimeOffset.UtcNow)) _staffLastValidation = DateTimeOffset.MinValue;
    }

    private static StaffAuthContext StaffContext(StaffObservedSession observed) =>
        new(observed.ExternalUserId, observed.ExternalSessionId, observed.WorkstationId, observed.ProcessIdentity);

    private void OpenStaffWindow(bool activate = false)
    {
        if (!_staffEnabled || _staffClient == null || _staffStop.IsCancellationRequested) return;
        if (_staffSession.IsAuthenticated(DateTimeOffset.UtcNow))
        {
            ShowStaffStatus("Вход сотрудника ePharm подтверждён. Для смены сотрудника используйте выход в меню этого значка.");
            return;
        }
        var observed = _staffSession.Observation.Session;
        if (_staffWindow == null)
        {
            var generation = _staffSession.Generation;
            var context = observed == null ? null : StaffContext(observed);
            var window = new StaffAuthWindow(_staffClient, context, PharmacistScreen(),
                observed == null ? ExplainStaffObservation(_staffSession.Observation.Status) : null);
            window.Authenticated += response =>
            {
                if (observed == null || context == null || response.Context != context || response.SessionToken == null || response.ExpiresAt == null ||
                    !_staffSession.Accept(generation, observed.Key, response.SessionToken, response.ExpiresAt.Value, DateTimeOffset.UtcNow))
                {
                    if (response.SessionToken != null) _ = RevokeOldStaffGrant(response.SessionToken);
                    return;
                }
                _staffLastValidation = DateTimeOffset.UtcNow;
                SetStaffAuthenticationStatus("authenticated");
                window.Close();
                if (_staffTray != null) _staffTray.Text = "Сотрудник ePharm — вход подтверждён";
                Log("Вход сотрудника ePharm подтверждён для текущей кассовой сессии.");
            };
            window.Closed += (_, _) => {
                if (_staffWindow != window) return;
                _staffWindow = null;
                if (_staffEnabled) SetStaffAuthenticationStatus(StaffAuthDiagnostics.Observation(
                    _staffSession.Observation.Status, _staffSession.IsAuthenticated(DateTimeOffset.UtcNow)));
            };
            _staffWindow = window; window.Show();
            SetStaffAuthenticationStatus(StaffAuthDiagnostics.Observation(_staffSession.Observation.Status, false, window.IsVisible));
        }
        if (activate) { _staffWindow.WindowState = WindowState.Normal; _staffWindow.Activate(); }
    }

    private static string ExplainStaffObservation(string status) => status switch {
        "no_process" => "Запустите АРМ Кассир Standard-N, чтобы войти в ePharm.",
        "no_session" => "Войдите под своим пользователем в АРМ Кассир Standard-N.",
        "ambiguous_process" or "ambiguous_session" => "Обнаружено несколько кассовых сессий. Обратитесь к администратору для проверки текущей кассы.",
        "database_disabled" => "Чтение кассовой сессии не настроено. Обратитесь к администратору.",
        "database_unavailable" => "Не удалось прочитать кассовую сессию. Проверьте подключение к базе Standard-N или обратитесь к администратору.",
        "stale_session" or "session_predates_process" => "Кассовая сессия ещё не обновилась после запуска Standard-N. Дождитесь её обновления или обратитесь к администратору.",
        "unsupported_session_time" or "future_session_time" or "invalid_session_time" or "unsupported_process_time" => "Не удалось проверить время кассовой сессии. Администратору необходимо проверить совместимость версии Standard-N и настройки времени.",
        _ => "Кассовая сессия пока не определена. Дождитесь запуска Standard-N или обратитесь к администратору.",
    };

    private void ShowStaffStatus(string message)
    {
        if (_staffStatusWindow != null) { _staffStatusWindow.Close(); _staffStatusWindow = null; }
        var window = new Window { Title = "Сотрудник ePharm", Width = 440, Height = 230, ShowActivated = false,
            Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(24), FontSize = 15 } };
        _staffStatusWindow = window;
        window.Closed += (_, _) => { if (_staffStatusWindow == window) _staffStatusWindow = null; };
        window.Show();
    }

    private async Task ValidateStaffGrant(CancellationToken ct)
    {
        string? token = null; StaffObservedSession? observation = null; long generation = 0;
        await Dispatcher.InvokeAsync(() => {
            if (_staffSession.IsAuthenticated(DateTimeOffset.UtcNow) && DateTimeOffset.UtcNow - _staffLastValidation >= TimeSpan.FromSeconds(30)) {
                token = _staffSession.Grant; observation = _staffSession.Observation.Session; generation = _staffSession.Generation;
                _staffLastValidation = DateTimeOffset.UtcNow;
            }
        });
        if (token == null || observation == null || _staffClient == null) return;
        try
        {
            var response = await _staffClient.ValidateAsync(token, StaffContext(observation), ct).ConfigureAwait(false);
            if (response.Status != "authenticated" || response.Context != StaffContext(observation)) throw new StaffApiException("INVALID_SESSION");
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
        catch
        {
            await Dispatcher.InvokeAsync(() => {
                if (_staffSession.Generation != generation || _staffSession.Grant != token) return;
                _staffSession.ClearGrant(); _staffPrompted = false;
                ApplyStaffObservation(_staffSession.Observation);
            });
        }
    }

    private async Task RevokeOldStaffGrant(string token)
    {
        try { if (_staffClient != null) await _staffClient.LogoutAsync(token, _staffStop.Token); }
        catch { } // Server grants are context-bound and expire; a failed revoke never transfers identity.
    }
    private async Task LogoutStaff()
    {
        var token = _staffSession.Grant;
        _staffSession.Invalidate(); _staffPrompted = true;
        SetStaffAuthenticationStatus(StaffAuthDiagnostics.Observation(_staffSession.Observation.Status, false));
        _staffWindow?.Close(); _staffStatusWindow?.Close();
        OpenStaffWindow(activate: true);
        if (token != null) await RevokeOldStaffGrant(token);
    }
    private void StopStaffAuthentication()
    {
        _staffStop.Cancel(); _staffSession.ClearGrant();
        _staffWindow?.Close(); _staffStatusWindow?.Close(); _staffTray?.Dispose(); _staffClient?.Dispose(); _staffLookup?.Dispose();
    }
}
