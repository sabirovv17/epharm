using System;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;

namespace CustomerDisplay
{
    /// <summary>
    /// Форма ввода телефона клиента для программы лояльности (CDP, §5.6). Инлайн-поиск после
    /// нескольких цифр, регистрация нового клиента. Это инструмент ФАРМАЦЕВТА (открывается на его
    /// экране), клиенту не показывается. Требует связь с backend; оффлайн → «нет связи».
    /// </summary>
    public partial class CdpForm : Window
    {
        private readonly EpharmApiClient? _api;
        private readonly EpharmConfig? _cfg;
        private readonly DispatcherTimer _debounce;

        public CdpForm(EpharmApiClient? api, EpharmConfig? cfg)
        {
            InitializeComponent();
            _api = api;
            _cfg = cfg;

            _debounce = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(450) };
            _debounce.Tick += async (_, _) => { _debounce.Stop(); await LookupAsync(); };

            KeyDown += (_, e) => { if (e.Key == Key.Escape) Close(); };
            Loaded += (_, _) => { TbPhone.Focus(); };
        }

        private void OnPhoneChanged(object sender, RoutedEventArgs e)
        {
            // инлайн-поиск после 4+ цифр, с debounce
            _debounce.Stop();
            if (DigitsCount(TbPhone.Text) >= 4) _debounce.Start();
            else TbStatus.Text = "Введите номер телефона";
        }

        private async System.Threading.Tasks.Task LookupAsync()
        {
            if (_api == null) { TbStatus.Text = "Нет связи с сервером (POSM выключен)"; return; }
            TbStatus.Text = "Поиск…";
            var res = await _api.CdpLookupAsync(TbPhone.Text);
            if (res == null) { TbStatus.Text = "Нет связи с сервером"; return; }

            if (res.Found)
            {
                TbStatus.Text = $"✓ Клиент найден: {res.Name ?? "(без имени)"} · уровень {res.Tier}";
                BtnRegister.IsEnabled = false;
            }
            else
            {
                TbStatus.Text = "Клиент не найден — можно зарегистрировать нового";
                BtnRegister.IsEnabled = true;
            }
        }

        private async void OnRegisterClick(object sender, RoutedEventArgs e)
        {
            if (_api == null) { TbStatus.Text = "Нет связи с сервером (POSM выключен)"; return; }
            if (DigitsCount(TbPhone.Text) < 5) { TbStatus.Text = "Введите корректный номер"; return; }

            BtnRegister.IsEnabled = false;
            TbStatus.Text = "Регистрация…";
            var profile = await _api.CdpRegisterAsync(new CdpRegisterRequestDto
            {
                Phone = TbPhone.Text,
                Name = string.IsNullOrWhiteSpace(TbName.Text) ? null : TbName.Text.Trim(),
                PharmacistId = _cfg?.PharmacistId,
                PharmacyId = _cfg?.PharmacyId,
            });

            TbStatus.Text = profile == null
                ? "Не удалось зарегистрировать (нет связи)"
                : profile.IsNew
                    ? $"✓ Зарегистрирован: {profile.Phone} · уровень {profile.Tier}"
                    : $"Клиент уже был в базе: {profile.Name ?? profile.Phone}";
        }

        private void OnCloseClick(object sender, RoutedEventArgs e) => Close();

        private static int DigitsCount(string s)
        {
            var n = 0;
            foreach (var c in s) if (char.IsDigit(c)) n++;
            return n;
        }
    }
}
