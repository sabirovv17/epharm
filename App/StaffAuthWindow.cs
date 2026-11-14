using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Threading;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;

namespace CustomerDisplay;

/// <summary>Cashier-monitor attention screen; never a Windows/input lock.</summary>
public sealed class StaffAuthWindow : Window
{
    private readonly StaffAuthClient _client;
    private readonly StaffAuthContext? _context;
    private readonly StaffDeviceContext? _deviceContext;
    private readonly StaffRegistrationPolicy _registrationPolicy = new();
    private readonly string _sessionRequiredMessage;
    private readonly string _sessionRequiredMessageKk;
    private bool _sourceContextReleased;
    private bool _dismissAllowed;
    private readonly CancellationTokenSource _stop = new();
    private readonly List<Action> _localizers = [];
    // Named navigation changes this internal mode; there is no technical dropdown in the form.
    private readonly ComboBox _mode = new();
    private readonly TextBox _iin = new() { MaxLength = 12, FontFamily = new FontFamily("Consolas") };
    private readonly TextBox _phone = new() { MaxLength = 32 };
    private readonly TextBox _name = new() { MaxLength = 255 };
    private readonly PasswordBox _password = new() { MaxLength = 128 };
    private readonly PasswordBox _passwordConfirmation = new() { MaxLength = 128 };
    private readonly TextBox _code = new() { MaxLength = 8, FontFamily = new FontFamily("Consolas") };
    private readonly Button _forgot = new();
    private readonly Button _newStaff = new();
    private readonly Button _activate = new();
    private readonly Button _back = new();
    private readonly Button _languageRu = new() { Content = "Рус" };
    private readonly Button _languageKk = new() { Content = "Қаз" };
    private readonly Button _request = new();
    private readonly Button _submit = new() { IsDefault = true };
    private readonly TextBlock _heading = new() { FontSize = 30, FontWeight = FontWeights.Bold, TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _intro = new() { FontSize = 14, LineHeight = 22, TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _passwordHint = new() { FontSize = 11, LineHeight = 17, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 6, 0, 0) };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, FontSize = 12, LineHeight = 19 };
    private readonly Border _statusSurface = new() { CornerRadius = new CornerRadius(9), Padding = new Thickness(12), Margin = new Thickness(0, 12, 0, 0) };
    private readonly StackPanel _form = new() { MaxWidth = 448, VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Stretch };
    private readonly StackPanel _recoveryGuidance = new() { Visibility = Visibility.Collapsed, Margin = new Thickness(0, 12, 0, 0) };
    private readonly Button _continueWithoutSignIn = new() { Margin = new Thickness(0, 12, 0, 0), Visibility = Visibility.Collapsed };
    private readonly StackPanel _phoneRow = new();
    private readonly StackPanel _nameRow = new();
    private readonly StackPanel _passwordRow = new();
    private readonly StackPanel _codeRow = new();
    private readonly StackPanel _passwordConfirmationRow = new();
    private readonly Border _brandPanel = new();
    private readonly ColumnDefinition _brandColumn = new() { Width = new GridLength(45, GridUnitType.Star) };
    private string? _challenge;
    private string? _challengeIin;
    private string? _challengePhone;
    private DateTimeOffset _challengeExpiresAt;
    private bool _busy;
    private bool _kazakh;
    private Func<string>? _statusMessage;
    private readonly DispatcherTimer _statusTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    public event Action? AuthenticationStarted;
    public event Action<StaffAuthResponse>? Authenticated;
    // The parent may need bounded fresh process confirmation. Await it while the
    // original form is busy; never fire-and-forget an async authentication handler.
    public Func<StaffAuthResponse, Task>? AuthenticationCompletion { get; set; }
    public event Action? AssignmentPending;
    public event Action<StaffPromptBypassReason>? BypassRequested;
    public event Action<bool>? LanguageChanged;
    public bool IsKazakh => _kazakh;

    public StaffAuthWindow(StaffAuthClient client, StaffAuthContext? context, System.Windows.Forms.Screen screen,
        string? sessionReason = null, bool requireSignIn = false, StaffDeviceContext? deviceContext = null, bool registrationSmsRequired = true, bool kazakh = false)
    {
        _client = client;
        _context = context;
        _deviceContext = deviceContext;
        _registrationPolicy.SetSmsRequired(registrationSmsRequired);
        // Keep the legacy constructor argument for callers, but source identity is
        // never permission to lock the cashier's desktop. Authentication is still
        // decided by the server and the parent's process-bound grant checks.
        _sessionRequiredMessage = deviceContext != null
            ? "Вход ePharm доступен. Если данные Standard-N недоступны, соответствие кассира пока не подтверждено; касса продолжает работать."
            : (sessionReason ?? "Кассовая сессия ещё не подтверждена.") +
            " Вход будет доступен после определения текущей сессии Standard-N. Для регистрации без SMS нужна актуальная версия ePharm; восстановление пароля доступно сейчас. Касса продолжает работать.";
        _sessionRequiredMessageKk = deviceContext != null
            ? "ePharm жүйесіне кіруге болады. Standard-N деректері қолжетімсіз болса, кассирмен сәйкестік әлі расталмаған; касса жұмысын жалғастырады."
            : "Касса сессиясы әлі расталмаған. Standard-N жүйесіндегі ағымдағы сессия анықталған соң кіруге болады. SMS-сіз тіркелу үшін ePharm-ның өзекті нұсқасы қажет; құпиясөзді қазір қалпына келтіруге болады. Касса жұмысын жалғастырады.";
        Title = "Вход сотрудника ePharm";
        Width = 1100; Height = 700; MinWidth = 360; MinHeight = 300;
        ShowActivated = false;
        WindowStyle = WindowStyle.SingleBorderWindow;
        ResizeMode = ResizeMode.CanResize;
        Topmost = false;
        Background = Brushes.White;
        FontFamily = new FontFamily("/CustomerDisplay;component/Assets/Brand/#Manrope"); FontSize = 14;
        Foreground = Color("#171A18"); UseLayoutRounding = true; SnapsToDevicePixels = true;
        Resources.MergedDictionaries.Add(new ResourceDictionary {
            Source = new Uri("/CustomerDisplay;component/Assets/Fluent/Icons.xaml", UriKind.Relative),
        });
        Resources.MergedDictionaries.Add(new ResourceDictionary {
            Source = new Uri("/CustomerDisplay;component/Assets/Brand/EpharmLogo.xaml", UriKind.Relative),
        });
        Resources.MergedDictionaries.Add((ResourceDictionary)XamlReader.Parse(Styles));
        Content = BuildLayout();
        AutomationProperties.SetAutomationId(this, "staff-auth-window");
        foreach (var label in new[] { "login", "register", "reset", "activate" }) _mode.Items.Add(label);
        _mode.SelectionChanged += (_, _) => ApplyMode();
        _mode.SelectedIndex = 0;
        _forgot.Click += (_, _) => _mode.SelectedIndex = 2;
        _newStaff.Click += (_, _) => _mode.SelectedIndex = 1;
        _activate.Click += (_, _) => _mode.SelectedIndex = 3;
        _back.Click += (_, _) => _mode.SelectedIndex = 0;
        _languageRu.Click += (_, _) => SwitchLanguage(false);
        _languageKk.Click += (_, _) => SwitchLanguage(true);
        _iin.TextChanged += (_, _) => ClearChallenge();
        _phone.TextChanged += (_, _) => ClearChallenge();
        _request.Click += async (_, _) => await RunAsync(RequestOtp, smsOperation: true);
        _submit.Click += async (_, _) => await RunAsync(Submit, smsOperation: _mode.SelectedIndex != 0 && !RegistrationWithoutSms);
        _continueWithoutSignIn.Click += (_, _) => {
            BypassRequested?.Invoke(_deviceContext != null
                ? StaffPromptBypassReason.IdentityUnavailable : StaffPromptBypassReason.SmsWait);
            if (!_stop.IsCancellationRequested) Close();
        };
        SourceInitialized += (_, _) => FitToScreen(screen);
        SizeChanged += (_, _) => ApplyResponsiveLayout(ActualWidth, ActualHeight);
        Closed += (_, _) => { _stop.Cancel(); _statusTimer.Stop(); _password.Clear(); _passwordConfirmation.Clear(); _iin.Clear(); _phone.Clear(); _code.Clear(); _name.Clear(); ClearChallenge(); };
        _statusTimer.Tick += (_, _) => RefreshStatus();
        SwitchLanguage(kazakh);
    }

    public bool WasDismissed => _dismissAllowed;
    public bool IsRequestPending => _busy;
    public CancellationToken ClosedToken => _stop.Token;
    public void Dismiss() { _dismissAllowed = true; Close(); }

    /// <summary>Fail-open releases presentation, not the filled form or an outstanding SMS challenge.</summary>
    public void ReleaseWork(string? message = null, string? kazakhMessage = null)
    {
        _continueWithoutSignIn.Visibility = Visibility.Visible;
        UpdateModePresentation();
        if (message != null) SetStatus(message, kazakhMessage ?? message);
    }

    /// <summary>Source loss releases only presentation: keep fields, OTP and an independently pending login.</summary>
    public void UpdateSourceContext(StaffAuthContext? currentContext)
    {
        if (_sourceContextReleased || !StaffPromptPolicy.HasComparableIdentity(_context) || StaffPromptPolicy.HasComparableIdentity(currentContext)) return;
        _sourceContextReleased = true;
        ReleaseWork(
            "Данные кассовой сессии временно не подтверждены. Форма сохранена; можно войти в ePharm или продолжить работу без входа.",
            "Касса сессиясының деректері уақытша расталмаған. Нысан сақталды; ePharm жүйесіне кіруге немесе кірмей жұмысты жалғастыруға болады.");
    }

    private bool RegistrationWithoutSms => _mode.SelectedIndex == 1;
    private bool CanRegisterWithoutSms => RegistrationWithoutSms && _deviceContext != null && !_registrationPolicy.SmsRequired;
    private string RegistrationUnavailable => T(
        "Регистрация без SMS пока недоступна на этой кассе. Обновите ePharm или повторите позже. Данные сохранены; можно продолжить работу.",
        "Бұл кассада SMS-сіз тіркелу әзірге қолжетімсіз. ePharm-ды жаңартыңыз немесе кейін қайталаңыз. Деректер сақталды; жұмысты жалғастыруға болады.");

    public void SetRegistrationSmsRequired(bool required)
    {
        if (!_registrationPolicy.SetSmsRequired(required) || _mode.SelectedIndex != 1) return;
        // Recovery/activation challenges are unrelated; only a registration flow is invalidated here.
        ClearChallenge(); _code.Clear();
        _request.Visibility = _codeRow.Visibility = RegistrationWithoutSms ? Visibility.Collapsed : Visibility.Visible;
        _request.IsEnabled = !_busy && !RegistrationWithoutSms;
        UpdateModePresentation();
        if (CanRegisterWithoutSms)
            SetStatus("Регистрация доступна без SMS. Номер телефона сохраняется без подтверждения.", "Тіркелу SMS-сіз қолжетімді. Телефон нөмірі расталмай сақталады.");
        else ReleaseForRegistrationUnavailable();
    }

    private void ReleaseForRegistrationUnavailable()
    {
        ReleaseWork();
        SetError(new StaffApiException("REGISTRATION_UNAVAILABLE"));
    }

    private FrameworkElement BuildLayout()
    {
        var columns = new Grid(); columns.ColumnDefinitions.Add(_brandColumn);
        columns.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(55, GridUnitType.Star) });
        BuildBrand(); columns.Children.Add(_brandPanel);
        var right = new Grid(); right.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        right.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        Grid.SetColumn(right, 1); columns.Children.Add(right);
        right.Children.Add(BuildChrome());
        BuildForm();
        var formCanvas = new Grid(); formCanvas.Children.Add(_form);
        var scroll = new ScrollViewer { Content = formCanvas, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, Margin = new Thickness(38, 0, 38, 22), Padding = new Thickness(0, 12, 0, 12) };
        scroll.SizeChanged += (_, e) => formCanvas.MinHeight = Math.Max(0, e.NewSize.Height - 26);
        Grid.SetRow(scroll, 1); right.Children.Add(scroll);
        return new Border { Background = Brushes.White, BorderBrush = Color("#DDE8E7"), BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(24), Margin = new Thickness(6), Child = columns,
            Effect = new DropShadowEffect { Color = System.Windows.Media.Color.FromRgb(9, 55, 60), BlurRadius = 16, ShadowDepth = 2, Opacity = 0.12 } };
    }

    private void BuildBrand()
    {
        _brandPanel.Background = Color("#073F46"); _brandPanel.CornerRadius = new CornerRadius(24, 0, 0, 24);
        _brandPanel.Padding = new Thickness(40);
        var layout = new Grid(); layout.RowDefinitions.Add(new RowDefinition());
        layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var top = new StackPanel();
        var identity = new StackPanel { Orientation = Orientation.Horizontal };
        identity.Children.Add(new Border { Width = 44, Height = 44, CornerRadius = new CornerRadius(12), Background = Color("#24565C"),
            Child = new Image { Source = (ImageSource)FindResource("EpharmLogo"), Width = 38, Height = 38 } });
        var wordmark = new StackPanel { Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
        wordmark.Children.Add(new TextBlock { Text = "ePharm", FontSize = 25, FontWeight = FontWeights.Bold, Foreground = Brushes.White });
        wordmark.Children.Add(Copy("Рабочее место фармацевта", "Фармацевттің жұмыс орны", 11, "#E9D5CA"));
        identity.Children.Add(wordmark); top.Children.Add(identity);
        var eyebrow = Copy("ЕДИНАЯ ПЛАТФОРМА", "БІРЫҢҒАЙ ПЛАТФОРМА", 11, "#E5AB93", FontWeights.Bold);
        eyebrow.Margin = new Thickness(0, 46, 0, 17); top.Children.Add(eyebrow);
        var title = Copy("Рекомендации\nи задачи —\nв одном окне", "Ұсынымдар\nмен тапсырмалар —\nбір терезеде", 34, "#FFFFFF", FontWeights.Bold);
        title.LineHeight = 43; top.Children.Add(title);
        var body = Copy("Вход сотрудника, рекомендации и задания аптеки — в одном защищённом приложении.",
            "Қызметкердің кіруі, ұсынымдар мен дәріхана тапсырмалары — бір қорғалған қолданбада.", 14, "#CDDCDB");
        body.LineHeight = 23; body.Margin = new Thickness(0, 20, 0, 0); top.Children.Add(body);
        layout.Children.Add(top);
        var bottom = new StackPanel(); Grid.SetRow(bottom, 1);
        var cards = new Grid(); for (var i = 0; i < 3; i++) cards.ColumnDefinitions.Add(new ColumnDefinition());
        var labels = new[] { ("Войдите\nпо ИИН", "ЖСН арқылы\nкіріңіз"), ("Смотрите\nрекомендации", "Ұсынымдарды\nкөріңіз"), ("Выполняйте\nзадания", "Тапсырмаларды\nорындаңыз") };
        for (var i = 0; i < labels.Length; i++)
        {
            var text = new StackPanel();
            text.Children.Add(new TextBlock { Text = (i + 1).ToString("00"), FontSize = 11, FontWeight = FontWeights.Bold, Foreground = Color("#E8AE95") });
            var label = Copy(labels[i].Item1, labels[i].Item2, 11, "#DCE7E6", FontWeights.SemiBold);
            label.LineHeight = 18; label.Margin = new Thickness(0, 5, 0, 0); text.Children.Add(label);
            var card = new Border { Child = text, CornerRadius = new CornerRadius(11), BorderBrush = Color("#345F64"),
                BorderThickness = new Thickness(1), Background = Color("#184C53"), Padding = new Thickness(12), Margin = new Thickness(0, 0, i == 2 ? 0 : 10, 0) };
            Grid.SetColumn(card, i); cards.Children.Add(card);
        }
        bottom.Children.Add(cards); layout.Children.Add(bottom); _brandPanel.Child = layout;
    }

    private FrameworkElement BuildChrome()
    {
        var top = new Grid { Height = 56, Background = Brushes.Transparent, Margin = new Thickness(16, 10, 14, 0) };
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top };
        var languages = new StackPanel { Orientation = Orientation.Horizontal };
        foreach (var language in new[] { _languageRu, _languageKk })
        {
            language.Style = (Style)FindResource("LanguageButton"); languages.Children.Add(language);
        }
        AutomationProperties.SetName(_languageRu, "Русский язык"); AutomationProperties.SetName(_languageKk, "Қазақ тілі");
        actions.Children.Add(new Border { Child = languages, BorderBrush = Color("#E8E6E2"), BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(12), Padding = new Thickness(3), Margin = new Thickness(0, 0, 10, 0) });
        top.Children.Add(actions); return top;
    }

    private void BuildForm()
    {
        Link(_back, "К входу", "Кіруге оралу"); _back.HorizontalAlignment = HorizontalAlignment.Left;
        _back.Margin = new Thickness(0, 0, 0, 16); _form.Children.Add(_back);
        var eyebrow = Copy("ЛИЧНЫЙ КАБИНЕТ", "ЖЕКЕ КАБИНЕТ", 11, "#9C4029", FontWeights.Bold);
        eyebrow.Margin = new Thickness(0, 0, 0, 9); _form.Children.Add(eyebrow);
        _form.Children.Add(_heading); _intro.Foreground = Color("#6B6862"); _intro.Margin = new Thickness(0, 12, 0, 22); _form.Children.Add(_intro);
        var iinRow = new StackPanel(); Field(iinRow, "ИИН *", "ЖСН *", _iin, "000000 000000", "000000 000000"); _form.Children.Add(iinRow);
        Field(_nameRow, "ФИО *", "Аты-жөні *", _name, "Полное имя сотрудника", "Қызметкердің толық аты-жөні"); _form.Children.Add(_nameRow);
        Field(_phoneRow, "Телефон *", "Телефон *", _phone, "+7 7xx xxx xx xx", "+7 7xx xxx xx xx"); _form.Children.Add(_phoneRow);
        Field(_passwordRow, "Пароль *", "Құпиясөз *", _password, "Введите пароль", "Құпиясөзді енгізіңіз"); _form.Children.Add(_passwordRow);
        _passwordHint.Foreground = Color("#6B6862"); _form.Children.Add(_passwordHint);
        Field(_passwordConfirmationRow, "Повторите пароль *", "Құпиясөзді қайталаңыз *", _passwordConfirmation, "Повторите новый пароль", "Жаңа құпиясөзді қайталаңыз"); _form.Children.Add(_passwordConfirmationRow);
        _request.Style = (Style)FindResource("SecondaryButton"); _request.Margin = new Thickness(0, 15, 0, 0); _form.Children.Add(_request);
        Caption(_request, "Получить SMS-код", "SMS кодын алу");
        _continueWithoutSignIn.Style = (Style)FindResource("SecondaryButton");
        Caption(_continueWithoutSignIn, "Продолжить работу без входа", "Кірмей жұмысты жалғастыру");
        Field(_codeRow, "Код из SMS *", "SMS коды *", _code, "0000", "0000"); _form.Children.Add(_codeRow);
        _forgot.HorizontalAlignment = HorizontalAlignment.Right; _forgot.Margin = new Thickness(0, 10, 0, 0);
        Link(_forgot, "Забыли пароль?", "Құпиясөзді ұмыттыңыз ба?"); _form.Children.Add(_forgot);
        _submit.Style = (Style)FindResource("PrimaryButton"); _submit.Margin = new Thickness(0, 20, 0, 0); _form.Children.Add(_submit); _form.Children.Add(_continueWithoutSignIn);
        _statusSurface.Background = Color("#FCF3EE"); _status.Foreground = Color("#88503A"); _statusSurface.Child = _status;
        var statusStyle = new Style(typeof(Border));
        statusStyle.Triggers.Add(new System.Windows.DataTrigger {
            Binding = new System.Windows.Data.Binding(nameof(TextBlock.Text)) { Source = _status }, Value = "",
            Setters = { new Setter(VisibilityProperty, Visibility.Collapsed) },
        });
        _statusSurface.Style = statusStyle; _form.Children.Add(_statusSurface);
        _recoveryGuidance.Children.Add(Copy("Впервые в ePharm? Зарегистрируйтесь с этим ИИН. Если профиль уже есть — восстановите пароль по привязанному телефону.",
            "ePharm жүйесіне алғаш кірдіңіз бе? Осы ЖСН арқылы тіркеліңіз. Профиль бар болса, тіркелген телефон арқылы құпиясөзді қалпына келтіріңіз.", 12, "#6B6862"));
        var recoveryChoices = new WrapPanel { Margin = new Thickness(0, 8, 0, 0) };
        var register = new Button(); Link(register, "Зарегистрироваться", "Тіркелу"); register.Margin = new Thickness(0, 0, 16, 0);
        var reset = new Button(); Link(reset, "Восстановить пароль", "Құпиясөзді қалпына келтіру");
        register.Click += (_, _) => _mode.SelectedIndex = 1; reset.Click += (_, _) => _mode.SelectedIndex = 2;
        recoveryChoices.Children.Add(register); recoveryChoices.Children.Add(reset); _recoveryGuidance.Children.Add(recoveryChoices); _form.Children.Add(_recoveryGuidance);
        var links = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 16, 0, 0) };
        Link(_newStaff, "Новый сотрудник? Регистрация", "Жаңа қызметкерсіз бе? Тіркелу"); links.Children.Add(_newStaff); _form.Children.Add(links);
        Link(_activate, "Профиль уже есть, но пароль ещё не создан", "Профиль бар, бірақ құпиясөз әлі жасалмаған");
        _activate.HorizontalAlignment = HorizontalAlignment.Center; _activate.Margin = new Thickness(0, 10, 0, 0); _form.Children.Add(_activate);
        var privacy = Copy(_deviceContext != null
            ? "Пароли и SMS-коды не сохраняются на кассе. Регистрация создаёт профиль ePharm для этой аптеки; соответствие пользователю Standard-N проверяется отдельно."
            : "Пароли и SMS-коды не сохраняются на кассе. При подтверждённой кассовой сессии новый профиль привязывается к этой аптеке.", _deviceContext != null
            ? "Құпиясөздер мен SMS кодтары кассада сақталмайды. Тіркелгенде осы дәріханаға ePharm профилі жасалады; Standard-N пайдаланушысымен сәйкестік бөлек тексеріледі."
            : "Құпиясөздер мен SMS кодтары кассада сақталмайды. Касса сессиясы расталса, жаңа профиль осы дәріханаға тіркеледі.", 11, "#76736D");
        privacy.LineHeight = 18;
        _form.Children.Add(new Border { BorderBrush = Color("#EFEEEB"), BorderThickness = new Thickness(0, 1, 0, 0),
            Padding = new Thickness(0, 18, 0, 0), Margin = new Thickness(0, 24, 0, 0), Child = privacy });
        var independence = Copy("Окно можно закрыть и открыть через значок ePharm в трее. Standard-N продолжает работать.",
            "Терезені жауып, ePharm белгішесі арқылы қайта ашуға болады. Standard-N жұмысын жалғастырады.", 10, "#76736D");
        independence.Margin = new Thickness(0, 10, 0, 0); _form.Children.Add(independence);
    }

    private static Brush Color(string hex) => (Brush)new BrushConverter().ConvertFromInvariantString(hex)!;
    private string T(string ru, string kk) => _kazakh ? kk : ru;
    private TextBlock Copy(string ru, string kk, double size, string color, FontWeight? weight = null)
    {
        var text = new TextBlock { FontSize = size, Foreground = Color(color), TextWrapping = TextWrapping.Wrap, FontWeight = weight ?? FontWeights.Normal };
        _localizers.Add(() => text.Text = T(ru, kk)); return text;
    }
    private void Caption(Button button, string ru, string kk) => _localizers.Add(() => {
        button.Content = T(ru, kk); AutomationProperties.SetName(button, T(ru, kk));
    });
    private void Link(Button button, string ru, string kk) { button.Style = (Style)FindResource("LinkButton"); Caption(button, ru, kk); }
    private void Field(StackPanel row, string ru, string kk, Control input, string placeholderRu, string placeholderKk)
    {
        row.Margin = new Thickness(0, 12, 0, 0);
        var label = Copy(ru, kk, 12, "#373531", FontWeights.SemiBold); label.Margin = new Thickness(0, 0, 0, 8); row.Children.Add(label);
        _localizers.Add(() => AutomationProperties.SetName(input, T(ru, kk)));
        input.Style = (Style)FindResource("StaffInput");
        var host = new Grid(); host.Children.Add(input);
        var placeholder = Copy(placeholderRu, placeholderKk, 14, "#A09C95");
        placeholder.FontFamily = input.FontFamily; placeholder.IsHitTestVisible = false;
        placeholder.VerticalAlignment = VerticalAlignment.Center; placeholder.Margin = new Thickness(15, 0, 15, 0);
        host.Children.Add(placeholder); row.Children.Add(host);
        if (input is TextBox text) text.TextChanged += (_, _) => placeholder.Visibility = text.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        if (input is PasswordBox password) password.PasswordChanged += (_, _) => placeholder.Visibility = password.Password.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
    }
    private void FitToScreen(System.Windows.Forms.Screen screen)
    {
        var dpi = VisualTreeHelper.GetDpi(this);
        var availableWidth = Math.Max(240, screen.WorkingArea.Width / dpi.DpiScaleX - 24);
        var availableHeight = Math.Max(240, screen.WorkingArea.Height / dpi.DpiScaleY - 24);
        MinWidth = Math.Min(360, availableWidth); MinHeight = Math.Min(300, availableHeight);
        MaxWidth = availableWidth; MaxHeight = availableHeight;
        Width = Math.Min(1100, availableWidth); Height = Math.Min(660, availableHeight);
        Left = screen.WorkingArea.Left / dpi.DpiScaleX + (screen.WorkingArea.Width / dpi.DpiScaleX - Width) / 2;
        Top = screen.WorkingArea.Top / dpi.DpiScaleY + (screen.WorkingArea.Height / dpi.DpiScaleY - Height) / 2;
    }
    private void ApplyResponsiveLayout(double width, double height)
    {
        var compact = width < 860 || height < 550;
        _brandPanel.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
        _brandColumn.Width = compact ? new GridLength(0) : new GridLength(45, GridUnitType.Star);
    }
    private void SwitchLanguage(bool kazakh)
    {
        _kazakh = kazakh;
        foreach (var localize in _localizers) localize();
        _languageRu.Background = Color(kazakh ? "#FFFFFF" : "#9C4029"); _languageRu.Foreground = kazakh ? Color("#6B6862") : Brushes.White;
        _languageKk.Background = Color(kazakh ? "#9C4029" : "#FFFFFF"); _languageKk.Foreground = kazakh ? Brushes.White : Color("#6B6862");
        Title = T("Вход сотрудника ePharm", "ePharm қызметкерінің кіруі");
        UpdateModePresentation();
        RefreshStatus();
        LanguageChanged?.Invoke(kazakh);
    }

    private void SetStatus(string ru, string kk)
    {
        _statusTimer.Stop();
        _statusMessage = () => T(ru, kk);
        RefreshStatus();
    }
    private void RefreshStatus() => _status.Text = _statusMessage?.Invoke() ?? "";
    private void UpdateModePresentation()
    {
        var login = _mode.SelectedIndex == 0;
        _back.Visibility = login ? Visibility.Collapsed : Visibility.Visible;
        _forgot.Visibility = _newStaff.Visibility = _activate.Visibility = login ? Visibility.Visible : Visibility.Collapsed;
        _heading.Text = _mode.SelectedIndex switch {
            1 => T("Новый сотрудник", "Жаңа қызметкер"), 2 => T("Восстановление пароля", "Құпиясөзді қалпына келтіру"),
            3 => T("Активация профиля", "Профильді белсендіру"), _ => T("Вход для фармацевта", "Фармацевттің кіруі"),
        };
        _intro.Text = _mode.SelectedIndex switch {
            1 => T("Заполните ИИН, ФИО, телефон и пароль. SMS не требуется; номер телефона пока не подтверждён.", "ЖСН, аты-жөніңізді, телефон мен құпиясөзді енгізіңіз. SMS қажет емес; телефон нөмірі әлі расталмаған."),
            2 => T("Укажите ИИН и ранее привязанный телефон. Подтвердите SMS-код и задайте новый пароль.", "ЖСН мен тіркелген телефонды көрсетіңіз. SMS кодын растап, жаңа құпиясөз орнатыңыз."),
            3 => T("Это не регистрация нового сотрудника. SMS-код подтверждает доступ к ранее привязанному телефону, чтобы создать пароль для существующего профиля. Новому сотруднику: «К входу» → «Новый сотрудник? Регистрация» — без SMS.", "Бұл жаңа қызметкерді тіркеу емес. SMS коды бұрын тіркелген телефонға қолжетімділікті растайды, содан кейін бар профиль үшін құпиясөз жасайсыз. Жаңа қызметкерге: «Кіруге оралу» → «Жаңа қызметкерсіз бе? Тіркелу» — SMS-сіз."),
            _ => T("Введите ИИН и пароль. Если вы впервые в ePharm, выберите регистрацию нового сотрудника.", "ЖСН мен құпиясөзді енгізіңіз. ePharm жүйесіне алғаш кірсеңіз, жаңа қызметкер ретінде тіркеліңіз."),
        };
        _passwordHint.Text = T("Новый пароль: 4–128 символов и не более 72 байт UTF-8.", "Жаңа құпиясөз: 4–128 таңба, UTF-8 форматында ең көбі 72 байт.");
        _submit.Content = login ? T("Войти", "Кіру") : _mode.SelectedIndex == 1 ? T("Зарегистрироваться", "Тіркелу") : T("Сохранить новый пароль", "Жаңа құпиясөзді сақтау");
        AutomationProperties.SetName(_submit, _submit.Content.ToString());
    }
    private string Purpose => _mode.SelectedIndex switch { 1 => "register", 2 => "reset", _ => "activate" };
    private void ClearChallenge() { _challenge = null; _challengeIin = null; _challengePhone = null; _continueWithoutSignIn.Visibility = Visibility.Visible; }
    private void ApplyMode()
    {
        ClearChallenge(); _password.Clear(); _passwordConfirmation.Clear(); _code.Clear(); SetStatus("", "");
        _recoveryGuidance.Visibility = Visibility.Collapsed;
        var login = _mode.SelectedIndex == 0;
        _phoneRow.Visibility = login ? Visibility.Collapsed : Visibility.Visible;
        _codeRow.Visibility = _request.Visibility = login || RegistrationWithoutSms ? Visibility.Collapsed : Visibility.Visible;
        _request.IsEnabled = !_busy && !RegistrationWithoutSms;
        _nameRow.Visibility = _mode.SelectedIndex == 1 ? Visibility.Visible : Visibility.Collapsed;
        _passwordConfirmationRow.Visibility = _passwordHint.Visibility = login ? Visibility.Collapsed : Visibility.Visible;
        UpdateModePresentation();
        _submit.IsEnabled = !_busy && (!login || _context != null || _deviceContext != null);
        if (login && (_context == null || _deviceContext != null && !StaffPromptPolicy.HasComparableIdentity(_context)))
            SetStatus(_sessionRequiredMessage, _sessionRequiredMessageKk);
        _continueWithoutSignIn.Visibility = Visibility.Visible;
        if (RegistrationWithoutSms && !CanRegisterWithoutSms) ReleaseForRegistrationUnavailable();
    }
    private void ValidateIdentity()
    {
        if (!Regex.IsMatch(_iin.Text.Trim(), "^[0-9]{12}$")) throw new StaffApiException("IIN_REQUIRED");
        if (_mode.SelectedIndex != 0 && _phone.Text.Trim().Length < 6) throw new StaffApiException("PHONE_REQUIRED");
    }
    private async Task RequestOtp()
    {
        ValidateIdentity();
        if (RegistrationWithoutSms) throw new StaffApiException("REGISTRATION_UNAVAILABLE");
        var iin = _iin.Text.Trim(); var phone = _phone.Text.Trim(); var purpose = Purpose;
        var policyGeneration = _registrationPolicy.Generation;
        var result = await _client.RequestOtpAsync(purpose, iin, phone, _stop.Token);
        _stop.Token.ThrowIfCancellationRequested();
        if (purpose == "register" && !_registrationPolicy.IsCurrent(policyGeneration)) {
            SetStatus("Способ регистрации изменился. Проверьте обновлённую форму.", "Тіркелу тәсілі өзгерді. Жаңартылған нысанды тексеріңіз."); return;
        }
        _challenge = result.ChallengeId; _challengeIin = iin; _challengePhone = phone;
        _challengeExpiresAt = DateTimeOffset.UtcNow.AddSeconds(Math.Clamp(result.TtlSeconds, 1, 300));
        SetStatus("Если данные подходят, код отправлен. Введите SMS-код. Повторная отправка отменит предыдущий код.",
            "Деректер сәйкес болса, код жіберілді. SMS кодын енгізіңіз. Қайта жібергенде алдыңғы код жарамсыз болады.");
        _continueWithoutSignIn.Visibility = Visibility.Visible;
    }
    private async Task Submit()
    {
        ValidateIdentity();
        var password = _password.Password;
        var confirmation = _passwordConfirmation.Password;
        if (_mode.SelectedIndex == 0 && StaffPasswordValidation.LoginError(password) is { } loginError) throw new StaffApiException(loginError);
        if (_mode.SelectedIndex != 0 && StaffPasswordValidation.Error(password, confirmation) is { } passwordError) throw new StaffApiException(passwordError);
        // Keep entered values through local/server failures. They are cleared only by
        // successful completion or intentional navigation/dismissal, never by an HTTP retry.
        if (_mode.SelectedIndex == 0)
        {
            AuthenticationStarted?.Invoke();
            var result = _deviceContext != null
                ? await _client.DeviceLoginAsync(_iin.Text.Trim(), password, _deviceContext, _stop.Token)
                : await _client.LoginAsync(_iin.Text.Trim(), password, _context, _stop.Token);
            _stop.Token.ThrowIfCancellationRequested();
            // Both login endpoints return only authenticated or pending. A malformed
            // success response is a service failure, never proof of pending approval.
            if (result.Status is not ("authenticated" or "pending")) throw new StaffApiException("INVALID_RESPONSE");
            if (result.Status == "authenticated") {
                if (_deviceContext != null && result.DeviceContext != _deviceContext) throw new StaffApiException("INVALID_RESPONSE");
                SetStatus("Подтверждаем вход ePharm…", "ePharm жүйесіне кіруді растап жатырмыз…");
                await CompleteAuthentication(result);
                return;
            }
            if (_deviceContext != null)
                SetStatus("Назначение аптеки для этого профиля пока не подтверждено. Обратитесь к администратору; Standard-N продолжает работать.",
                    "Бұл профильдің дәріханаға тағайындалуы әлі расталмаған. Әкімшіге хабарласыңыз; Standard-N жұмысын жалғастырады.");
            else SetStatus("Профиль ожидает подтверждения назначения администратором. Вход пока не разрешён.",
                "Профиль тағайындауды әкімшінің растауын күтуде. Кіруге әзірше рұқсат жоқ.");
            AssignmentPending?.Invoke();
            if (_deviceContext != null) ReleaseWork();
            return;
        }
        if (RegistrationWithoutSms)
        {
            if (!CanRegisterWithoutSms) throw new StaffApiException("REGISTRATION_UNAVAILABLE");
            if (_name.Text.Trim().Length < 3) throw new StaffApiException("NAME_REQUIRED");
            var policyGeneration = _registrationPolicy.Generation;
            AuthenticationStarted?.Invoke();
            var registered = await _client.DeviceRegisterWithoutSmsAsync(_iin.Text.Trim(), _phone.Text.Trim(), _name.Text.Trim(), password, _deviceContext!, _stop.Token);
            _stop.Token.ThrowIfCancellationRequested();
            if (!await DiscardChangedRegistration(policyGeneration, registered)) await CompleteRegistration(registered);
            return;
        }
        if (_challenge == null || _challengeExpiresAt <= DateTimeOffset.UtcNow || _challengeIin != _iin.Text.Trim() || _challengePhone != _phone.Text.Trim()) throw new StaffApiException("SMS_REQUIRED");
        if (!Regex.IsMatch(_code.Text.Trim(), "^[0-9]{4,8}$")) throw new StaffApiException("CODE_REQUIRED");
        await _client.ChangePasswordAsync(Purpose, _challenge, _code.Text.Trim(), _iin.Text.Trim(), _phone.Text.Trim(), password, _stop.Token);
        _stop.Token.ThrowIfCancellationRequested(); _mode.SelectedIndex = 0;
        if (_context == null) SetStatus("Пароль сохранён. " + _sessionRequiredMessage, "Құпиясөз сақталды. " + _sessionRequiredMessageKk);
        else SetStatus("Пароль сохранён. Теперь войдите с новым паролем.", "Құпиясөз сақталды. Енді жаңа құпиясөзбен кіріңіз.");
    }
    private async Task CompleteRegistration(StaffRegistrationResponse registered)
    {
        if (registered.Status is not ("authenticated" or "registered" or "pending")) throw new StaffApiException("INVALID_RESPONSE");
        ClearChallenge(); _code.Clear();
        if (IsValidAuthenticatedRegistration(registered))
        {
            var awaitedParent = AuthenticationCompletion != null;
            var statusBeforeHandoff = _statusMessage;
            await CompleteAuthentication(new StaffAuthResponse(registered.Status, registered.SessionToken,
                registered.ExpiresAt, registered.Pharmacist!, _context, registered.DeviceContext));
            // The server has created the account, even if fresh process confirmation
            // rejects its grant. The awaited parent dismisses accepted windows; a live
            // form must offer login, not a second account-creation request. Preserve
            // its exact process diagnostic and never replay credentials automatically.
            if (awaitedParent)
                PrepareLoginAfterCreatedAccount(ReferenceEquals(statusBeforeHandoff, _statusMessage) ? null : _statusMessage);
            return;
        }
        if (registered.Status == "authenticated") throw new StaffApiException("INVALID_RESPONSE");
        _mode.SelectedIndex = 0;
        if (_deviceContext != null)
            SetStatus("Профиль создан. Войдите по ИИН и паролю на этой кассе. Если аптека ещё не назначена, обратитесь к администратору.",
                "Профиль жасалды. Осы кассада ЖСН және құпиясөз арқылы кіріңіз. Дәріхана әлі тағайындалмаса, әкімшіге хабарласыңыз.");
        else if (registered.Status == "pending")
            SetStatus("Профиль создан без кассовой сессии. Для назначения аптеки обратитесь к администратору.",
                "Профиль касса сессиясынсыз жасалды. Дәріхана тағайындау үшін әкімшіге хабарласыңыз.");
        else SetStatus("Профиль создан. Войдите по ИИН и паролю после определения кассовой сессии.",
            "Профиль жасалды. Касса сессиясы анықталған соң ЖСН және құпиясөз арқылы кіріңіз.");
        if (_deviceContext != null && registered.Status is ("pending" or "registered")) ReleaseWork();
        if (registered.Status == "pending") AssignmentPending?.Invoke();
    }

    private async Task CompleteAuthentication(StaffAuthResponse response)
    {
        if (AuthenticationCompletion is { } complete) await complete(response);
        else Authenticated?.Invoke(response); // Legacy embedding/tests retain their synchronous contract.
    }

    private bool IsValidAuthenticatedRegistration(StaffRegistrationResponse response) =>
        response.Status == "authenticated" &&
        (_deviceContext != null ? response.DeviceContext == _deviceContext : response.Context == _context && _context != null) &&
        !string.IsNullOrWhiteSpace(response.Pharmacist?.Id) && !string.IsNullOrWhiteSpace(response.SessionToken) &&
        response.ExpiresAt > DateTimeOffset.UtcNow;

    private void PrepareLoginAfterCreatedAccount(Func<string>? parentStatus)
    {
        if (_stop.IsCancellationRequested || !IsVisible || !RegistrationWithoutSms) return;
        var password = _password.Password;
        _mode.SelectedIndex = 0;
        _password.Password = password;
        _statusMessage = () => T("Профиль создан. Вход ещё не подтверждён. Повторная регистрация не нужна.",
            "Профиль жасалды. Кіру әлі расталған жоқ. Қайта тіркелудің қажеті жоқ.") +
            (parentStatus == null ? "" : " " + parentStatus());
        RefreshStatus();
        RevealError();
    }

    private async Task<bool> DiscardChangedRegistration(long generation, StaffRegistrationResponse response)
    {
        if (_registrationPolicy.IsCurrent(generation)) return false;
        ReleaseWork("Способ регистрации изменился. Вход не подтверждён. Если профиль уже создан, войдите по ИИН и паролю; не регистрируйтесь повторно.",
            "Тіркелу тәсілі өзгерді. Кіру расталмады. Профиль жасалған болса, қайта тіркелмей ЖСН және құпиясөз арқылы кіріңіз.");
        if (_deviceContext != null && !string.IsNullOrWhiteSpace(response.SessionToken)) {
            try { await _client.DeviceLogoutAsync(response.SessionToken, _stop.Token); } catch { }
        }
        if (IsValidAuthenticatedRegistration(response)) PrepareLoginAfterCreatedAccount(_statusMessage);
        return true;
    }

    private async Task RunAsync(Func<Task> operation, bool smsOperation)
    {
        if (_busy || _stop.IsCancellationRequested) return;
        _busy = true; _submit.IsEnabled = _request.IsEnabled = _mode.IsEnabled = _forgot.IsEnabled = _newStaff.IsEnabled = false;
        _recoveryGuidance.IsEnabled = _activate.IsEnabled = _back.IsEnabled = _languageRu.IsEnabled = _languageKk.IsEnabled = false;
        // Leaving the form is always available, including while a request is pending.
        // Closed cancels that request; a late response cannot authenticate this window.
        _continueWithoutSignIn.IsEnabled = true;
        _iin.IsEnabled = _phone.IsEnabled = _name.IsEnabled = _code.IsEnabled = _password.IsEnabled = _passwordConfirmation.IsEnabled = false;
        SetStatus("Подождите…", "Күте тұрыңыз…");
        try { await operation(); }
        catch (OperationCanceledException) when (_stop.IsCancellationRequested) { }
        catch (StaffApiException ex) {
            if (!_stop.IsCancellationRequested) {
                SetError(ex);
                if (ex.Code is "UNAVAILABLE" or "INVALID_RESPONSE")
                    ReleaseForServiceFailure(smsOperation);
                else if (ex.Code is "RATE_LIMITED" or "REGISTRATION_UNAVAILABLE") ReleaseWork();
                else if (ex.Code is "STAFF_PHARMACY_ASSIGNMENT_REQUIRED" or "STAFF_PHARMACY_MISMATCH") {
                    AssignmentPending?.Invoke(); ReleaseWork();
                }
                if (_mode.SelectedIndex == 0 && StaffAuthDiagnostics.OfferRecovery(ex.Code)) {
                    ClearChallenge(); _code.Clear();
                    _recoveryGuidance.Visibility = Visibility.Visible;
                }
                RevealError();
            }
        }
        catch {
            if (!_stop.IsCancellationRequested) {
                SetStatus("Сервер временно недоступен. Повторите позже. Касса продолжает работать.",
                    "Сервер уақытша қолжетімсіз. Кейінірек қайталаңыз. Касса жұмысын жалғастырады.");
                ReleaseForServiceFailure(smsOperation);
                RevealError();
            }
        }
        finally {
            _busy = false;
            if (!_stop.IsCancellationRequested) {
                _mode.IsEnabled = _forgot.IsEnabled = _newStaff.IsEnabled = true;
                _request.IsEnabled = !RegistrationWithoutSms;
                _submit.IsEnabled = _mode.SelectedIndex != 0 || _context != null || _deviceContext != null;
                _recoveryGuidance.IsEnabled = _activate.IsEnabled = _back.IsEnabled = _languageRu.IsEnabled = _languageKk.IsEnabled = true;
                _continueWithoutSignIn.IsEnabled = true;
                _iin.IsEnabled = _phone.IsEnabled = _name.IsEnabled = _code.IsEnabled = _password.IsEnabled = _passwordConfirmation.IsEnabled = true;
            }
        }
    }
    private void RevealError()
    {
        // Wait for the new status and recovery controls to finish layout. Scroll only
        // on an explicit failed action, never on each countdown tick or language change.
        // BringIntoView does not focus the message or activate the native window.
        Dispatcher.BeginInvoke(DispatcherPriority.Loaded, new Action(() => {
            if (!_stop.IsCancellationRequested && IsVisible && _status.IsVisible)
                _status.BringIntoView();
        }));
    }

    private void ReleaseForServiceFailure(bool smsOperation)
    {
        ReleaseWork();
        BypassRequested?.Invoke(smsOperation ? StaffPromptBypassReason.SmsUnavailable : StaffPromptBypassReason.ServiceUnavailable);
    }
    private void SetError(StaffApiException error)
    {
        _statusTimer.Stop();
        _statusMessage = () => Explain(error.Code);
        if (error.Code == "RATE_LIMITED" && error.RetryAction == "REQUEST_NEW_OTP" && _mode.SelectedIndex != 0 && !RegistrationWithoutSms) {
            ClearChallenge(); _code.Clear();
            _statusMessage = () => T("Лимит попыток этого SMS-кода исчерпан. Запросите новый SMS-код; ожидание не восстановит старый.",
                "Осы SMS кодын енгізу әрекеттерінің шегі таусылды. Жаңа SMS кодын сұраңыз; күту ескі кодты қалпына келтірмейді.");
        }
        else if (error.Code == "RATE_LIMITED" && error.RetryAfterSeconds is > 0) {
            var retryAt = DateTimeOffset.UtcNow.AddSeconds(Math.Clamp(error.RetryAfterSeconds.Value, 1, 86400));
            _statusMessage = () => {
                var seconds = Math.Max(0, (int)Math.Ceiling((retryAt - DateTimeOffset.UtcNow).TotalSeconds));
                if (seconds == 0) {
                    _statusTimer.Stop();
                    return T("Пауза завершена. Можно повторить попытку.", "Күту уақыты аяқталды. Қайта әрекет жасауға болады.");
                }
                return T($"Слишком много попыток. Повторите через {seconds} с.", $"Әрекет тым көп. {seconds} с кейін қайталаңыз.");
            };
            _statusTimer.Start();
        }
        RefreshStatus();
    }
    private string Explain(string code) => code switch {
        "REGISTRATION_UNAVAILABLE" => RegistrationUnavailable,
        "IIN_REQUIRED" => T("Введите ИИН: 12 цифр.", "ЖСН енгізіңіз: 12 цифр."),
        "PHONE_REQUIRED" => T("Введите номер телефона.", "Телефон нөмірін енгізіңіз."),
        "SESSION_REQUIRED" => T("Сначала войдите в Standard-N и дождитесь определения текущей кассовой сессии. Регистрация без SMS доступна на обновлённой кассе; восстановление пароля доступно независимо.", "Алдымен Standard-N жүйесіне кіріп, ағымдағы касса сессиясы анықталғанша күтіңіз. SMS-сіз тіркелу жаңартылған кассада қолжетімді; құпиясөзді бөлек қалпына келтіруге болады."),
        "PASSWORD_REQUIRED" => T("Введите пароль. Нужно не менее 4 символов.", "Құпиясөз енгізіңіз. Кемінде 4 таңба болуы керек."),
        "PASSWORD_LENGTH" => T("Пароль должен содержать от 4 до 128 символов.", "Құпиясөз 4–128 таңбадан тұруы керек."),
        "PASSWORD_BYTES" when _mode.SelectedIndex == 0 => T("Этот пароль превышает ограничение 72 байта UTF-8. Не сокращайте существующий пароль: выберите «Забыли пароль?» и восстановите пароль по привязанному телефону.", "Бұл құпиясөз UTF-8 форматындағы 72 байт шегінен асады. Бар құпиясөзді қысқартпаңыз: «Құпиясөзді ұмыттыңыз ба?» арқылы тіркелген телефонмен қалпына келтіріңіз."),
        "PASSWORD_BYTES" => T("Пароль слишком длинный: допускается не более 72 байт UTF-8. Сократите пароль, особенно если он содержит кириллицу или эмодзи.", "Құпиясөз тым ұзын: UTF-8 форматында 72 байттан аспауы керек. Әсіресе кириллица немесе эмодзи болса, құпиясөзді қысқартыңыз."),
        "PASSWORD_MISMATCH" => T("Пароли не совпадают. Проверьте новый пароль и его подтверждение.", "Құпиясөздер сәйкес емес. Жаңа құпиясөз бен растауын тексеріңіз."),
        "NAME_REQUIRED" => T("Введите полное ФИО.", "Толық аты-жөніңізді енгізіңіз."),
        "SMS_REQUIRED" => T("Сначала запросите новый SMS-код для указанных данных.", "Алдымен көрсетілген деректерге жаңа SMS кодын сұраңыз."),
        "CODE_REQUIRED" => T("Введите код из SMS: 4–8 цифр.", "SMS кодын енгізіңіз: 4–8 цифр."),
        "RATE_LIMITED" => T("Слишком много попыток. Подождите перед повтором.", "Әрекет тым көп. Қайталау алдында күтіңіз."),
        "STAFF_PHARMACY_ASSIGNMENT_REQUIRED" => T("Учётная запись ещё не привязана к аптеке. Обратитесь в HQ для проверки и назначения аптеки. Повторная регистрация не нужна.", "Есептік жазба дәріханаға әлі бекітілмеген. Дәріхананы тексеріп, тағайындау үшін HQ-ға хабарласыңыз. Қайта тіркелудің қажеті жоқ."),
        "STAFF_PHARMACY_MISMATCH" => T("Учётная запись закреплена за другой аптекой. Обратитесь в HQ для проверки привязки. Повторная регистрация не нужна.", "Есептік жазба басқа дәріханаға бекітілген. Байланысты тексеру үшін HQ-ға хабарласыңыз. Қайта тіркелудің қажеті жоқ."),
        "INVALID_CREDENTIALS" or "FORBIDDEN" => T("Не удалось войти. Проверьте ИИН и пароль или обратитесь к администратору.", "Кіру мүмкін болмады. ЖСН мен құпиясөзді тексеріңіз немесе әкімшіге хабарласыңыз."),
        "INVALID_DATA" when _mode.SelectedIndex == 0 => T("Проверьте ИИН и пароль.", "ЖСН мен құпиясөзді тексеріңіз."),
        "INVALID_DATA" when RegistrationWithoutSms => T("Проверьте ИИН и данные регистрации. Если профиль уже существует, перейдите ко входу.", "ЖСН мен тіркелу деректерін тексеріңіз. Профиль бар болса, кіруге өтіңіз."),
        "INVALID_DATA" => T("Проверьте данные и SMS-код. Если код истёк или уже использован, запросите новый.", "Деректер мен SMS кодын тексеріңіз. Кодтың мерзімі өтсе немесе қолданылған болса, жаңасын сұраңыз."),
        "CONFLICT" => T("Не удалось выполнить действие. Возможно, профиль уже существует — попробуйте вход или восстановление пароля.", "Әрекетті орындау мүмкін болмады. Профиль бар болуы мүмкін — кіріп көріңіз немесе құпиясөзді қалпына келтіріңіз."),
        _ => T("Сервис временно недоступен. Повторите позже.", "Сервис уақытша қолжетімсіз. Кейінірек қайталаңыз."),
    };

    private const string Styles = """
        <ResourceDictionary xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
          <Style x:Key="StaffInput" TargetType="Control">
            <Setter Property="Height" Value="48"/><Setter Property="Padding" Value="14,10"/>
            <Setter Property="Background" Value="White"/><Setter Property="Foreground" Value="#373531"/>
            <Setter Property="BorderBrush" Value="#D7D5D0"/><Setter Property="BorderThickness" Value="1"/>
            <Setter Property="FontSize" Value="15"/><Setter Property="VerticalContentAlignment" Value="Center"/>
            <Setter Property="FocusVisualStyle" Value="{x:Null}"/>
            <Setter Property="Template"><Setter.Value><ControlTemplate TargetType="Control">
              <Border x:Name="Frame" CornerRadius="10" Background="{TemplateBinding Background}" BorderBrush="{TemplateBinding BorderBrush}" BorderThickness="{TemplateBinding BorderThickness}">
                <!-- Native text controls already forward their Padding to this content host. -->
                <ScrollViewer x:Name="PART_ContentHost" Margin="0" VerticalAlignment="Stretch"/>
              </Border>
              <ControlTemplate.Triggers>
                <Trigger Property="IsKeyboardFocusWithin" Value="True"><Setter TargetName="Frame" Property="BorderBrush" Value="#A64A31"/><Setter TargetName="Frame" Property="Effect"><Setter.Value><DropShadowEffect Color="#82CBBF" BlurRadius="5" ShadowDepth="0" Opacity="0.65"/></Setter.Value></Setter></Trigger>
                <Trigger Property="IsEnabled" Value="False"><Setter Property="Opacity" Value="0.6"/></Trigger>
              </ControlTemplate.Triggers>
            </ControlTemplate></Setter.Value></Setter>
          </Style>
          <Style x:Key="PrimaryButton" TargetType="Button">
            <Setter Property="Height" Value="48"/><Setter Property="Padding" Value="16,8"/>
            <Setter Property="Background" Value="#9C4029"/><Setter Property="Foreground" Value="White"/>
            <Setter Property="BorderBrush" Value="Transparent"/><Setter Property="BorderThickness" Value="1"/>
            <Setter Property="FontSize" Value="15"/><Setter Property="FontWeight" Value="SemiBold"/><Setter Property="Cursor" Value="Hand"/>
            <Setter Property="Template"><Setter.Value><ControlTemplate TargetType="Button">
              <Border x:Name="Frame" CornerRadius="10" Background="{TemplateBinding Background}" BorderBrush="{TemplateBinding BorderBrush}" BorderThickness="{TemplateBinding BorderThickness}" Padding="{TemplateBinding Padding}"><ContentPresenter HorizontalAlignment="Center" VerticalAlignment="Center"/></Border>
              <ControlTemplate.Triggers>
                <Trigger Property="IsMouseOver" Value="True"><Setter TargetName="Frame" Property="Opacity" Value="0.9"/></Trigger>
                <Trigger Property="IsPressed" Value="True"><Setter TargetName="Frame" Property="Opacity" Value="0.78"/></Trigger>
                <Trigger Property="IsKeyboardFocused" Value="True"><Setter TargetName="Frame" Property="BorderBrush" Value="#183F42"/></Trigger>
                <Trigger Property="IsEnabled" Value="False"><Setter Property="Opacity" Value="0.43"/></Trigger>
              </ControlTemplate.Triggers>
            </ControlTemplate></Setter.Value></Setter>
          </Style>
          <Style x:Key="SecondaryButton" TargetType="Button" BasedOn="{StaticResource PrimaryButton}"><Setter Property="Background" Value="#F8EFE9"/><Setter Property="Foreground" Value="#9C4029"/><Setter Property="Height" Value="42"/><Setter Property="FontSize" Value="13"/></Style>
          <Style x:Key="LinkButton" TargetType="Button">
            <Setter Property="Background" Value="Transparent"/><Setter Property="Foreground" Value="#9C4029"/><Setter Property="BorderBrush" Value="Transparent"/><Setter Property="BorderThickness" Value="1"/>
            <Setter Property="Padding" Value="1,2"/><Setter Property="FontSize" Value="12"/><Setter Property="FontWeight" Value="SemiBold"/><Setter Property="Cursor" Value="Hand"/>
            <Setter Property="Template"><Setter.Value><ControlTemplate TargetType="Button">
              <Border x:Name="Frame" CornerRadius="5" Background="{TemplateBinding Background}" BorderBrush="{TemplateBinding BorderBrush}" BorderThickness="{TemplateBinding BorderThickness}" Padding="{TemplateBinding Padding}"><ContentPresenter HorizontalAlignment="Center" VerticalAlignment="Center"/></Border>
              <ControlTemplate.Triggers><Trigger Property="IsMouseOver" Value="True"><Setter Property="Opacity" Value="0.72"/></Trigger><Trigger Property="IsKeyboardFocused" Value="True"><Setter TargetName="Frame" Property="BorderBrush" Value="#9C4029"/></Trigger><Trigger Property="IsEnabled" Value="False"><Setter Property="Opacity" Value="0.4"/></Trigger></ControlTemplate.Triggers>
            </ControlTemplate></Setter.Value></Setter>
          </Style>
          <Style x:Key="LanguageButton" TargetType="Button" BasedOn="{StaticResource PrimaryButton}"><Setter Property="Height" Value="28"/><Setter Property="Padding" Value="9,2"/><Setter Property="FontSize" Value="11"/></Style>
          <Style x:Key="ChromeButton" TargetType="Button" BasedOn="{StaticResource LinkButton}"><Setter Property="Width" Value="28"/><Setter Property="Height" Value="32"/><Setter Property="Padding" Value="5"/></Style>
        </ResourceDictionary>
        """;
}
