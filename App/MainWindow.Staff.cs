using System;
using System.Diagnostics;
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
    private readonly StaffDeviceSessionState _staffSession = new();
    private readonly StaffProcessStability _staffProcessStability = new();
    private readonly CancellationTokenSource _staffStop = new();
    private System.Windows.Forms.NotifyIcon? _staffTray;
    private StaffAuthWindow? _staffWindow;
    private Window? _staffStatusWindow;
    private volatile bool _staffEnabled;
    private bool _staffRegistrationSmsRequired = true;
    private bool _staffKazakh;
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
                        if (StaffAuthDiagnostics.RegistrationPolicy(capability) is { } required)
                        {
                            _staffRegistrationSmsRequired = required;
                            _staffWindow?.SetRegistrationSmsRequired(required);
                        }
                        if (_staffEnabled && (capability.Status is "network_unavailable" or "endpoint_unavailable"))
                        {
                            BypassStaffPrompt(StaffPromptBypassReason.ServiceUnavailable, backgroundCheck: true);
                            return;
                        }
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
                    if (checkedProcess.Identity != process.Identity)
                    { process = new("process_changing"); observation = new("process_changing"); }
                    else if (checkedProcess.Status != process.Status)
                    { process = checkedProcess; observation = new(checkedProcess.Status); }
                    if (_staffProcessStability.Confirm(process, DateTimeOffset.UtcNow) != null)
                    {
                        await Dispatcher.InvokeAsync(() => ApplyStaffObservation(process, observation));
                        await ValidateStaffGrant(ct).ConfigureAwait(false);
                    }
                    else await Dispatcher.InvokeAsync(() => SetStaffAuthenticationStatus("process_unavailable"));
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

    private static StaffProcessPresence ObserveStaffProcess()
    {
        try
        {
            using var current = Process.GetCurrentProcess();
            var values = new System.Collections.Generic.List<string>();
            var unreadable = false;
            foreach (var name in new[] { "zkassa", "kassir" })
                foreach (var process in Process.GetProcessesByName(name))
                    using (process)
                    {
                        try {
                            if (process.SessionId != current.SessionId) continue;
                            values.Add(process.Id.ToString(System.Globalization.CultureInfo.InvariantCulture) + ":" +
                                process.StartTime.ToUniversalTime().Ticks.ToString(System.Globalization.CultureInfo.InvariantCulture));
                        }
                        catch { unreadable = true; }
                    }
            var presence = StaffProcessPresence.FromInstances(values);
            // A readable real process still permits own login; an unreadable peer prevents source attribution.
            return unreadable ? presence with { Status = "process_unavailable" } : presence;
        }
        catch { return new("process_unavailable"); }
    }

    private void SetStaffCapability(bool enabled)
    {
        if (_staffStop.IsCancellationRequested || enabled == _staffEnabled) return;
        _staffEnabled = enabled;
        if (!enabled)
        {
            var token = _staffSession.Grant;
            _staffSession.Observe(new("no_process"), new("service_disabled"));
            _staffSession.Invalidate();
            if (token != null) _ = RevokeOldStaffGrant(token);
            _staffWindow?.Dismiss(); _staffStatusWindow?.Close();
            _staffTray?.Dispose(); _staffTray = null;
            return;
        }
        _staffTray = new System.Windows.Forms.NotifyIcon {
            Icon = System.Drawing.SystemIcons.Information, Text = StaffText("Сотрудник ePharm — вход", "ePharm қызметкері — кіру"), Visible = true };
        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add(StaffText("Вход / регистрация сотрудника", "Қызметкердің кіруі / тіркелуі"), null, (_, _) => OpenStaffWindow(activate: true));
        menu.Items.Add(StaffText("Выйти из учётной записи ePharm", "ePharm есептік жазбасынан шығу"), null, async (_, _) => await LogoutStaff());
        _staffTray.ContextMenuStrip = menu;
        _staffTray.DoubleClick += (_, _) => OpenStaffWindow(activate: true);
        Log("Вход сотрудника ePharm включён сервером; кассовые функции продолжают работать независимо.");
    }

    private void ApplyStaffObservation(StaffProcessPresence process, StaffSessionObservation observation)
    {
        if (_staffStop.IsCancellationRequested || !_staffEnabled) return;
        _staffSession.IsAuthenticated(DateTimeOffset.UtcNow);
        var oldToken = _staffSession.Grant;
        var oldSource = _staffSession.LastKnownSource;
        if (_staffSession.Observe(process, observation))
        {
            _staffWindow?.Dismiss(); _staffStatusWindow?.Close();
            _staffLastValidation = DateTimeOffset.MinValue;
            if (oldToken != null) _ = RevokeOldStaffGrant(oldToken);
            Log("Кассовый процесс или подтверждённая сессия сотрудника ePharm изменились.");
        }
        // Bind the first reliable source promptly, without treating temporary DB loss as an account switch.
        if (_staffSession.LastKnownSource != oldSource) _staffLastValidation = DateTimeOffset.MinValue;
        // Presentation follows the CURRENT source, not the last reliable binding retained
        // for grant security. Losing DB identity must not trap the existing filled form.
        _staffWindow?.UpdateSourceContext(_staffSession.CurrentSource);
        RefreshStaffStatus();
        if (_staffSession.NeedsPrompt(DateTimeOffset.UtcNow))
        {
            _staffSession.MarkPrompted();
            OpenStaffWindow();
        }
    }

    private void RefreshStaffStatus()
    {
        var authenticated = _staffSession.IsAuthenticated(DateTimeOffset.UtcNow);
        if (_staffTray != null) {
            _staffTray.Text = authenticated ? StaffText("Сотрудник ePharm — вход подтверждён", "ePharm қызметкері — кіру расталды")
                : _staffSession.BypassStatus != null ? StaffText("ePharm — вход не подтверждён, касса доступна", "ePharm — кіру расталмады, касса қолжетімді")
                : StaffText("Сотрудник ePharm — требуется вход", "ePharm қызметкері — кіру қажет");
            if (_staffTray.ContextMenuStrip is { Items.Count: >= 2 } menu) {
                menu.Items[0].Text = StaffText("Вход / регистрация сотрудника", "Қызметкердің кіруі / тіркелуі");
                menu.Items[1].Text = StaffText("Выйти из учётной записи ePharm", "ePharm есептік жазбасынан шығу");
            }
        }
        SetStaffAuthenticationStatus(_staffSession.BypassStatus ?? (authenticated ? "authenticated" :
            _staffWindow?.IsVisible == true ? "login_visible" : _staffSession.ProcessInstanceId != null ? "login_required" :
            StaffAuthDiagnostics.Normalize(_staffSession.Observation.Status)));
    }

    private void OpenStaffWindow(bool activate = false)
    {
        if (!_staffEnabled || _staffClient == null || _staffStop.IsCancellationRequested) return;
        if (_staffSession.IsAuthenticated(DateTimeOffset.UtcNow))
        {
            ShowStaffStatus(StaffText("Вы уже вошли в ePharm. Для смены сотрудника завершите или отмените текущий чек, полностью закройте АРМ Кассир Стандарт-Н крестиком и запустите его снова. После запуска появится окно входа ePharm — войдите под своим ИИН.",
                "Сіз ePharm жүйесіне кірдіңіз. Қызметкерді ауыстыру үшін ағымдағы чекті аяқтаңыз немесе жойыңыз, Standard-N кассир бағдарламасын айқышпен толық жауып, қайта іске қосыңыз. ePharm кіру терезесі ашылғанда өз ЖСН-іңізбен кіріңіз."));
            return;
        }
        var context = _staffSession.Context;
        if (context == null) { if (activate) ShowStaffStatus(ExplainStaffObservation(_staffSession.Observation.Status)); return; }
        if (_staffWindow == null)
        {
            var generation = _staffSession.Generation;
            _staffSession.MarkPrompted();
            var window = new StaffAuthWindow(_staffClient, context.StandardNContext, PharmacistScreen(),
                "Вход ePharm доступен. Сверка с пользователем Standard-N выполняется отдельно, когда кассовая сессия определена.",
                requireSignIn: _staffSession.BypassStatus == null && StaffPromptPolicy.HasComparableIdentity(context.StandardNContext),
                deviceContext: context, registrationSmsRequired: _staffRegistrationSmsRequired, kazakh: _staffKazakh);
            window.LanguageChanged += kazakh => { _staffKazakh = kazakh; RefreshStaffStatus(); };
            window.AuthenticationStarted += () => generation = _staffWindow == window && _staffSession.ProcessInstanceId == context.ProcessInstanceId
                ? _staffSession.Generation : -1;
            window.Authenticated += response =>
            {
                var actualProcess = ObserveStaffProcess();
                if (_staffWindow != window || !_staffEnabled || actualProcess.Identity != context.ProcessInstanceId ||
                    !_staffSession.Accept(generation, context, response, DateTimeOffset.UtcNow))
                {
                    if (response.SessionToken != null) _ = RevokeOldStaffGrant(response.SessionToken);
                    if (_staffWindow == window) {
                        window.ReleaseWork("Вход не подтверждён для текущего процесса. Форма сохранена; повторите вход после проверки Standard-N.",
                            "Ағымдағы процесс үшін кіру расталмады. Нысан сақталды; Standard-N тексерілген соң қайта кіріңіз.");
                        SetStaffAuthenticationStatus("login_visible");
                    }
                    return;
                }
                _staffLastValidation = DateTimeOffset.MinValue;
                window.Dismiss();
                RefreshStaffStatus();
                Log("Вход сотрудника ePharm подтверждён для текущего кассового процесса.");
            };
            window.BypassRequested += reason => {
                if (_staffWindow == window && _staffSession.ProcessInstanceId == context.ProcessInstanceId) BypassStaffPrompt(reason);
            };
            window.Closed += (_, _) => {
                if (_staffWindow != window) return;
                _staffWindow = null;
                // The ordinary unknown-source window is closable; closing it never creates a grant or auto-popup loop.
                if (_staffEnabled && !window.WasDismissed && !_staffSession.IsAuthenticated(DateTimeOffset.UtcNow))
                    _staffSession.Bypass(StaffPromptBypassReason.IdentityUnavailable);
                if (_staffEnabled) RefreshStaffStatus();
            };
            _staffWindow = window; window.Show();
            RefreshStaffStatus();
        }
        if (activate) { _staffWindow.WindowState = WindowState.Normal; _staffWindow.Activate(); }
    }

    private void BypassStaffPrompt(StaffPromptBypassReason reason, bool backgroundCheck = false)
    {
        if (!_staffEnabled || _staffStop.IsCancellationRequested) return;
        var token = _staffSession.Grant;
        var pendingRequest = backgroundCheck && _staffWindow?.IsRequestPending == true;
        var status = _staffSession.Bypass(reason, preservePendingRequest: pendingRequest);
        if (reason is StaffPromptBypassReason.ServiceUnavailable or StaffPromptBypassReason.SmsUnavailable)
            _staffWindow?.ReleaseWork(pendingRequest ? null : backgroundCheck
                ? "Не удалось обновить настройки входа. Форма сохранена; можно продолжить работу и повторить запрос позже."
                : "Сервис временно недоступен. Форма сохранена. Можно продолжить работу или повторить запрос позже.",
                pendingRequest ? null : backgroundCheck
                    ? "Кіру параметрлерін жаңарту мүмкін болмады. Нысан сақталды; жұмысты жалғастырып, сұрауды кейін қайталауға болады."
                    : "Сервис уақытша қолжетімсіз. Нысан сақталды. Жұмысты жалғастыруға немесе сұрауды кейін қайталауға болады.");
        else _staffWindow?.Dismiss();
        _staffStatusWindow?.Close();
        SetStaffAuthenticationStatus(status);
        if (_staffTray != null) _staffTray.Text = StaffText("ePharm — вход не подтверждён, касса доступна", "ePharm — кіру расталмады, касса қолжетімді");
        if (token != null) _ = RevokeOldStaffGrant(token);
    }

    private string StaffText(string ru, string kk) => _staffKazakh ? kk : ru;
    private string ExplainStaffObservation(string status) => status switch {
        "no_process" => StaffText("Запустите АРМ Кассир Standard-N, чтобы войти в ePharm.", "ePharm жүйесіне кіру үшін Standard-N кассир бағдарламасын іске қосыңыз."),
        "no_session" => StaffText("Войдите под своим пользователем в АРМ Кассир Standard-N.", "Standard-N кассир бағдарламасына өз есептік жазбаңызбен кіріңіз."),
        "ambiguous_process" or "ambiguous_session" => StaffText("Обнаружено несколько кассовых сессий. Обратитесь к администратору для проверки текущей кассы.", "Бірнеше касса сессиясы анықталды. Ағымдағы кассаны тексеру үшін әкімшіге хабарласыңыз."),
        "database_disabled" => StaffText("Чтение кассовой сессии не настроено. Обратитесь к администратору.", "Касса сессиясын оқу бапталмаған. Әкімшіге хабарласыңыз."),
        "database_unavailable" => StaffText("Не удалось прочитать кассовую сессию. Проверьте подключение к базе Standard-N или обратитесь к администратору.", "Касса сессиясын оқу мүмкін болмады. Standard-N базасына қосылымды тексеріңіз немесе әкімшіге хабарласыңыз."),
        "stale_session" or "session_predates_process" => StaffText("Кассовая сессия ещё не обновилась после запуска Standard-N. Дождитесь её обновления или обратитесь к администратору.", "Standard-N іске қосылғаннан кейін касса сессиясы әлі жаңартылмаған. Жаңартылуын күтіңіз немесе әкімшіге хабарласыңыз."),
        "unsupported_session_time" or "future_session_time" or "invalid_session_time" or "unsupported_process_time" => StaffText("Не удалось проверить время кассовой сессии. Администратору необходимо проверить совместимость версии Standard-N и настройки времени.", "Касса сессиясының уақытын тексеру мүмкін болмады. Әкімші Standard-N нұсқасының үйлесімділігі мен уақыт баптауын тексеруі керек."),
        _ => StaffText("Кассовая сессия пока не определена. Дождитесь запуска Standard-N или обратитесь к администратору.", "Касса сессиясы әлі анықталмаған. Standard-N іске қосылғанша күтіңіз немесе әкімшіге хабарласыңыз."),
    };

    private void ShowStaffStatus(string message)
    {
        if (_staffStatusWindow != null) { _staffStatusWindow.Close(); _staffStatusWindow = null; }
        var window = new Window { Title = StaffText("Сотрудник ePharm", "ePharm қызметкері"), Width = 440, Height = 230, ShowActivated = false,
            Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(24), FontSize = 15 } };
        _staffStatusWindow = window;
        window.Closed += (_, _) => { if (_staffStatusWindow == window) _staffStatusWindow = null; };
        window.Show();
    }

    private async Task ValidateStaffGrant(CancellationToken ct)
    {
        string? token = null; StaffDeviceContext? context = null; long generation = 0;
        await Dispatcher.InvokeAsync(() => {
            if (_staffSession.IsAuthenticated(DateTimeOffset.UtcNow) && DateTimeOffset.UtcNow - _staffLastValidation >= TimeSpan.FromSeconds(30)) {
                token = _staffSession.Grant; context = _staffSession.Context; generation = _staffSession.Generation;
                _staffLastValidation = DateTimeOffset.UtcNow;
            }
        });
        if (token == null || context == null || _staffClient == null) return;
        try
        {
            var response = await _staffClient.ValidateDeviceAsync(token, context, ct).ConfigureAwait(false);
            if (response.Status != "authenticated" || response.DeviceContext != context) throw new StaffApiException("INVALID_SESSION");
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
        catch (Exception error) when (error is System.Net.Http.HttpRequestException or OperationCanceledException or System.Text.Json.JsonException ||
            error is StaffApiException { Code: "UNAVAILABLE" or "INVALID_RESPONSE" })
        {
            await Dispatcher.InvokeAsync(() => {
                if (_staffSession.Generation == generation && _staffSession.Grant == token)
                    BypassStaffPrompt(StaffPromptBypassReason.ServiceUnavailable);
            });
        }
        catch
        {
            await Dispatcher.InvokeAsync(() => {
                if (_staffSession.Generation != generation || _staffSession.Grant != token) return;
                _staffSession.Invalidate();
                ApplyStaffObservation(new(_staffSession.Observation.Status, _staffSession.ProcessInstanceId), _staffSession.Observation);
            });
        }
    }

    private async Task RevokeOldStaffGrant(string token)
    {
        try { if (_staffClient != null) await _staffClient.DeviceLogoutAsync(token, _staffStop.Token); }
        catch { } // Server grants are context-bound and expire; a failed revoke never transfers identity.
    }
    private async Task LogoutStaff()
    {
        var token = _staffSession.Grant;
        _staffSession.Invalidate(); _staffSession.MarkPrompted();
        RefreshStaffStatus();
        _staffWindow?.Dismiss(); _staffStatusWindow?.Close();
        OpenStaffWindow(activate: true);
        if (token != null) await RevokeOldStaffGrant(token);
    }
    private void StopStaffAuthentication()
    {
        _staffStop.Cancel(); _staffSession.ClearGrant();
        _staffWindow?.Dismiss(); _staffStatusWindow?.Close(); _staffTray?.Dispose(); _staffClient?.Dispose(); _staffLookup?.Dispose();
    }
}
