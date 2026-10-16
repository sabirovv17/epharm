using System;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;

namespace CustomerDisplay;

/// <summary>Closable, non-modal staff sign-in. Does not capture Standard-N input or customer screen.</summary>
public sealed class StaffAuthWindow : Window
{
    private readonly StaffAuthClient _client;
    private readonly StaffAuthContext _context;
    private readonly CancellationTokenSource _stop = new();
    private readonly ComboBox _mode = new();
    private readonly TextBox _iin = new() { MaxLength = 12 };
    private readonly TextBox _phone = new() { MaxLength = 32 };
    private readonly TextBox _name = new() { MaxLength = 255 };
    private readonly PasswordBox _password = new() { MaxLength = 128 };
    private readonly PasswordBox _passwordConfirmation = new() { MaxLength = 128 };
    private readonly Button _forgot = new() { Content = "Забыли пароль?", Margin = new Thickness(0, 8, 8, 0), Padding = new Thickness(8) };
    private readonly Button _newStaff = new() { Content = "Новый сотрудник", Margin = new Thickness(0, 8, 0, 0), Padding = new Thickness(8) };
    private readonly TextBlock _passwordHint = new() { Text = "Новый пароль: 8–128 символов и не более 72 байт UTF-8. Кириллица и некоторые символы занимают несколько байт.", TextWrapping = TextWrapping.Wrap, FontSize = 12, Foreground = Brushes.DimGray, Margin = new Thickness(0, 6, 0, 0) };
    private readonly TextBox _code = new() { MaxLength = 8 };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 0) };
    private readonly Button _request = new() { Content = "Получить SMS-код", Margin = new Thickness(0, 12, 0, 0) };
    private readonly Button _submit = new() { Margin = new Thickness(0, 16, 0, 0) };
    private readonly FrameworkElement _phoneRow;
    private readonly FrameworkElement _nameRow;
    private readonly FrameworkElement _codeRow;
    private readonly FrameworkElement _passwordConfirmationRow;
    private string? _challenge;
    private string? _challengeIin;
    private string? _challengePhone;
    private DateTimeOffset _challengeExpiresAt;
    private bool _busy;
    public event Action<StaffAuthResponse>? Authenticated;

    public StaffAuthWindow(StaffAuthClient client, StaffAuthContext context, System.Windows.Forms.Screen screen)
    {
        _client = client;
        _context = context;
        Title = "Вход сотрудника ePharm";
        Width = 460; Height = 700; MinWidth = 360; MinHeight = 420;
        ShowActivated = false;
        Topmost = true;
        Background = Brushes.WhiteSmoke;
        FontFamily = new FontFamily("Segoe UI"); FontSize = 14;
        var panel = new StackPanel { Margin = new Thickness(24) };
        panel.Children.Add(new TextBlock { Text = "Вход сотрудника ePharm", FontSize = 23, FontWeight = FontWeights.SemiBold });
        panel.Children.Add(new TextBlock { Text = "Подтвердите сотрудника для текущей кассовой сессии. Окно можно закрыть и открыть через значок ePharm в трее.", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 10, 0, 12) });
        foreach (var label in new[] { "Войти по ИИН и паролю", "Зарегистрировать нового сотрудника", "Восстановить пароль", "Создать пароль для существующего профиля" }) _mode.Items.Add(label);
        AutomationProperties.SetName(_mode, "Действие: вход, регистрация или восстановление пароля");
        panel.Children.Add(_mode);
        var links = new WrapPanel(); links.Children.Add(_forgot); links.Children.Add(_newStaff); panel.Children.Add(links);
        _forgot.Click += (_, _) => _mode.SelectedIndex = 2;
        _newStaff.Click += (_, _) => _mode.SelectedIndex = 1;
        panel.Children.Add(Field("ИИН *", _iin));
        _nameRow = Field("ФИО *", _name); panel.Children.Add(_nameRow);
        _phoneRow = Field("Телефон * (для восстановления — ранее привязанный)", _phone); panel.Children.Add(_phoneRow);
        panel.Children.Add(Field("Пароль *", _password));
        panel.Children.Add(_passwordHint);
        _passwordConfirmationRow = Field("Повторите новый пароль *", _passwordConfirmation); panel.Children.Add(_passwordConfirmationRow);
        panel.Children.Add(_request);
        _codeRow = Field("Код из SMS *", _code); panel.Children.Add(_codeRow);
        panel.Children.Add(_submit); panel.Children.Add(_status);
        panel.Children.Add(new TextBlock { Text = "Пароли и SMS-коды не сохраняются на компьютере. Регистрация требует подтверждения назначения сотрудника.", FontSize = 12, Foreground = Brushes.DimGray, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 18, 0, 0) });
        Content = new ScrollViewer { Content = panel, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        _mode.SelectionChanged += (_, _) => ApplyMode();
        _mode.SelectedIndex = 0;
        _iin.TextChanged += (_, _) => ClearChallenge();
        _phone.TextChanged += (_, _) => ClearChallenge();
        _request.Click += async (_, _) => await RunAsync(RequestOtp);
        _submit.Click += async (_, _) => await RunAsync(Submit);
        SourceInitialized += (_, _) => {
            var dpi = VisualTreeHelper.GetDpi(this);
            MaxHeight = Math.Max(380, screen.WorkingArea.Height / dpi.DpiScaleY - 24);
            Left = screen.WorkingArea.Left / dpi.DpiScaleX + Math.Max(12, (screen.WorkingArea.Width / dpi.DpiScaleX - Width) / 2);
            Top = screen.WorkingArea.Top / dpi.DpiScaleY + 12;
        };
        Closed += (_, _) => { _stop.Cancel(); _password.Clear(); _passwordConfirmation.Clear(); _iin.Clear(); _phone.Clear(); _code.Clear(); _name.Clear(); ClearChallenge(); };
    }

    private string Purpose => _mode.SelectedIndex switch { 1 => "register", 2 => "reset", _ => "activate" };
    private static FrameworkElement Field(string label, Control input)
    {
        var row = new StackPanel { Margin = new Thickness(0, 12, 0, 0) };
        AutomationProperties.SetName(input, label);
        row.Children.Add(new TextBlock { Text = label, Margin = new Thickness(0, 0, 0, 5) });
        input.Padding = new Thickness(10); row.Children.Add(input); return row;
    }
    private void ClearChallenge() { _challenge = null; _challengeIin = null; _challengePhone = null; }
    private void ApplyMode()
    {
        ClearChallenge(); _password.Clear(); _passwordConfirmation.Clear(); _code.Clear(); _status.Text = "";
        var login = _mode.SelectedIndex == 0;
        _phoneRow.Visibility = _codeRow.Visibility = _request.Visibility = login ? Visibility.Collapsed : Visibility.Visible;
        _nameRow.Visibility = _mode.SelectedIndex == 1 ? Visibility.Visible : Visibility.Collapsed;
        _passwordConfirmationRow.Visibility = _passwordHint.Visibility = login ? Visibility.Collapsed : Visibility.Visible;
        _submit.Content = login ? "Войти" : _mode.SelectedIndex == 1 ? "Отправить регистрацию" : "Сохранить новый пароль";
    }
    private void ValidateIdentity()
    {
        if (!Regex.IsMatch(_iin.Text.Trim(), "^[0-9]{12}$")) throw new StaffApiException("IIN_REQUIRED");
        if (_mode.SelectedIndex != 0 && _phone.Text.Trim().Length < 6) throw new StaffApiException("PHONE_REQUIRED");
    }
    private async Task RequestOtp()
    {
        ValidateIdentity();
        var iin = _iin.Text.Trim(); var phone = _phone.Text.Trim();
        var result = await _client.RequestOtpAsync(Purpose, iin, phone, _stop.Token);
        _stop.Token.ThrowIfCancellationRequested();
        _challenge = result.ChallengeId; _challengeIin = iin; _challengePhone = phone;
        _challengeExpiresAt = DateTimeOffset.UtcNow.AddSeconds(Math.Clamp(result.TtlSeconds, 1, 300));
        _status.Text = "Если данные подходят, код отправлен. Введите SMS-код. Повторная отправка отменит предыдущий код.";
    }
    private async Task Submit()
    {
        ValidateIdentity();
        var password = _password.Password;
        var confirmation = _passwordConfirmation.Password;
        _password.Clear(); _passwordConfirmation.Clear();
        if (_mode.SelectedIndex == 0 && password.Length == 0) throw new StaffApiException("PASSWORD_REQUIRED");
        if (_mode.SelectedIndex != 0 && StaffPasswordValidation.Error(password, confirmation) is { } passwordError) throw new StaffApiException(passwordError);
        if (_mode.SelectedIndex == 0)
        {
            var result = await _client.LoginAsync(_iin.Text.Trim(), password, _context, _stop.Token);
            _stop.Token.ThrowIfCancellationRequested();
            if (result.Status == "authenticated") {
                _status.Text = "Проверяем актуальность кассовой сессии…";
                Authenticated?.Invoke(result);
                return;
            }
            _status.Text = "Профиль ожидает подтверждения назначения администратором. Вход пока не разрешён.";
            return;
        }
        if (_challenge == null || _challengeExpiresAt <= DateTimeOffset.UtcNow || _challengeIin != _iin.Text.Trim() || _challengePhone != _phone.Text.Trim()) throw new StaffApiException("SMS_REQUIRED");
        if (!Regex.IsMatch(_code.Text.Trim(), "^[0-9]{4,8}$")) throw new StaffApiException("CODE_REQUIRED");
        if (_mode.SelectedIndex == 1)
        {
            if (_name.Text.Trim().Length < 3) throw new StaffApiException("NAME_REQUIRED");
            await _client.RegisterAsync(_challenge, _code.Text.Trim(), _iin.Text.Trim(), _phone.Text.Trim(), _name.Text.Trim(), password, _stop.Token);
            _stop.Token.ThrowIfCancellationRequested(); ClearChallenge(); _code.Clear();
            _status.Text = "Регистрация отправлена. Дождитесь подтверждения назначения администратором, затем войдите по ИИН и паролю.";
        }
        else
        {
            await _client.ChangePasswordAsync(Purpose, _challenge, _code.Text.Trim(), _iin.Text.Trim(), _phone.Text.Trim(), password, _stop.Token);
            _stop.Token.ThrowIfCancellationRequested(); _mode.SelectedIndex = 0;
            _status.Text = "Пароль сохранён. Теперь войдите с новым паролем.";
        }
    }
    private async Task RunAsync(Func<Task> operation)
    {
        if (_busy || _stop.IsCancellationRequested) return;
        _busy = true; _submit.IsEnabled = _request.IsEnabled = _mode.IsEnabled = _forgot.IsEnabled = _newStaff.IsEnabled = false;
        _iin.IsEnabled = _phone.IsEnabled = _name.IsEnabled = _code.IsEnabled = _password.IsEnabled = _passwordConfirmation.IsEnabled = false;
        _status.Text = "Подождите…";
        try { await operation(); }
        catch (OperationCanceledException) when (_stop.IsCancellationRequested) { }
        catch (StaffApiException ex) { if (!_stop.IsCancellationRequested) _status.Text = Explain(ex.Code); }
        catch { if (!_stop.IsCancellationRequested) _status.Text = "Сервер временно недоступен. Повторите позже. Касса продолжает работать."; }
        finally {
            _busy = false;
            if (!_stop.IsCancellationRequested) {
                _submit.IsEnabled = _request.IsEnabled = _mode.IsEnabled = _forgot.IsEnabled = _newStaff.IsEnabled = true;
                _iin.IsEnabled = _phone.IsEnabled = _name.IsEnabled = _code.IsEnabled = _password.IsEnabled = _passwordConfirmation.IsEnabled = true;
            }
        }
    }
    private static string Explain(string code) => code switch {
        "IIN_REQUIRED" => "Введите ИИН: 12 цифр.", "PHONE_REQUIRED" => "Введите номер телефона.",
        "PASSWORD_REQUIRED" => "Введите пароль. Для нового пароля нужно не менее 8 символов.",
        "PASSWORD_LENGTH" => "Новый пароль должен содержать от 8 до 128 символов.",
        "PASSWORD_BYTES" => "Пароль слишком длинный: допускается не более 72 байт UTF-8. Сократите пароль, особенно если он содержит кириллицу или эмодзи.",
        "PASSWORD_MISMATCH" => "Пароли не совпадают. Введите новый пароль и его подтверждение ещё раз.",
        "NAME_REQUIRED" => "Введите полное ФИО.", "SMS_REQUIRED" => "Сначала запросите новый SMS-код для указанных данных.",
        "CODE_REQUIRED" => "Введите код из SMS: 4–8 цифр.", "RATE_LIMITED" => "Слишком много попыток. Подождите перед повтором.",
        "INVALID_CREDENTIALS" or "FORBIDDEN" => "Не удалось войти. Проверьте ИИН и пароль или обратитесь к администратору.",
        "INVALID_DATA" => "Проверьте данные и SMS-код. Если код истёк или уже использован, запросите новый.",
        "CONFLICT" => "Не удалось выполнить действие. Возможно, профиль уже существует — попробуйте вход или восстановление пароля.",
        _ => "Сервис временно недоступен. Повторите позже.",
    };
}
