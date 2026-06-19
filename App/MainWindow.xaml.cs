using System;
using System.Collections.ObjectModel;
using System.Linq;
using System.Windows;
using System.Windows.Threading;
using LibVLCSharp.Shared;
using System.Windows.Forms;
using System.Diagnostics;
using System.IO;
using System.Windows;
using LibVLCSharp.Shared;
using System.Windows.Input;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Collections.ObjectModel;
using System.Linq;
using CustomerDisplay.Models;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;




namespace CustomerDisplay
{
    public partial class MainWindow : Window
    {
private CancellationTokenSource? _logCts;
// Пути к логу кассы Стандарт-Н. На разных кассах путь отличается (обычная установка
// vs demo), поэтому слушаем СРАЗУ НЕСКОЛЬКО кандидатов — какой файл реально пишется,
// тот и читается; остальные просто ждут появления. Доп. путь можно задать через env
// EPHARM_LOG_PATH (добавляется первым). Дубли отсеиваются.
private readonly List<string> _logPaths = BuildLogPaths();

private static List<string> BuildLogPaths()
{
    var paths = new List<string>();
    var env = Environment.GetEnvironmentVariable("EPHARM_LOG_PATH");
    if (!string.IsNullOrWhiteSpace(env)) paths.Add(env);
    paths.Add(@"C:\Standart-N\Kassir\zkassa.log");
    paths.Add(@"C:\Standart-N_DEMO\Apteka_KZ DEMO\Kassir\zkassa.log");
    return paths.Distinct(StringComparer.OrdinalIgnoreCase).ToList();
}


public ObservableCollection<ReceiptItem> ReceiptItems { get; } = new();
private void RecalcTotal()
{
       var total = ReceiptItems.Sum(x => x.Total); // уже округлено на уровне строки
    TbTotal.Text = total.ToString("#,0.##") + " тг";

}
private readonly List<string> _playlist = new()
{
    @"C:\Users\Alx\Desktop\CustomerDisplay\promo.mp4",
    @"C:\Users\Alx\Desktop\CustomerDisplay\promo.mp4",
    @"C:\Users\Alx\Desktop\CustomerDisplay\promo.mp4",
};

private int _index = -1;
private Media? _currentMedia;
private volatile int _switching = 0; // защита от двойного EndReached
        private LibVLC _libVLC;
        private MediaPlayer _mediaPlayer;

     
private static readonly string LogPath =
    Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Desktop), "customerdisplay.log");

private static void Log(string msg)
{
    File.AppendAllText(LogPath, $"{DateTime.Now:HH:mm:ss} {msg}\r\n");
}
private CustomerDisplay.Services.Heartbeat? _heartbeat;

private void MainWindow_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
{
    // Выход ТОЛЬКО по защищённой комбинации Ctrl+Shift+Q — чтобы кассир случайным нажатием Q
    // не закрыл кассу и не остановил прослушку логов. Обычная Q больше не завершает приложение.
    if (e.Key == Key.Q &&
        Keyboard.Modifiers.HasFlag(ModifierKeys.Control) &&
        Keyboard.Modifiers.HasFlag(ModifierKeys.Shift))
    {
        System.Windows.Application.Current.Shutdown();
    }
    else if (e.Key == Key.D)
    {
        // Демо: показать popup рекомендации с примером (без кассы/бэкенда).
        ShowDemoRecommendation();
    }
    else if (e.Key == Key.C)
    {
        // Карта клиента (CDP §5.6) — форма ввода телефона для программы лояльности.
        ShowCdpForm();
    }
}
        public MainWindow()
        {
            InitializeComponent();

    DataContext = this;                 // <-- ВАЖНО
    ItemsList.ItemsSource = ReceiptItems; // <-- ВАЖНО (жёстко привязали)
this.KeyDown += MainWindow_KeyDown;
this.Focusable = true;
this.Focus();
            InitPosm(); // POSM-рекомендации (ТЗ §4) — fail-safe, no-op если интеграция выключена
            Loaded += OnLoaded;
            Closed += OnClosed;

            // часы
         
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            // Уровень 2 живучести: heartbeat живости UI-потока для внешнего watchdog
            // (scripts/watchdog.ps1). Пишется ВСЕГДА, даже если POSM-интеграция выключена —
            // watchdog перезапустит кассу при падении ИЛИ зависании (устаревший heartbeat).
            try
            {
                var hbPath = _posmConfig?.HeartbeatPath ?? @"C:\Epharm\heartbeat.txt";
                var hbSec = _posmConfig?.HeartbeatSec ?? 15;
                _heartbeat = new CustomerDisplay.Services.Heartbeat(hbPath, hbSec, Log);
                _heartbeat.Start();
            }
            catch (Exception ex) { Log($"heartbeat start error: {ex.Message}"); }

//PositionWindowToTopRightQuarter();

            // Режим «только экран фармацевта» (EPHARM_PHARMACIST_PREVIEW=true) — для скринов поверх
            // Стандарт-Н. Не запускаем киоск/видео/лог: прячем окно и показываем одну карточку.
            if (_posmConfig?.PharmacistPreview == true)
            {
                EnterPharmacistPreview();
                return;
            }

           MoveToSecondScreenFullscreen();

            // Видео можно отключить (env EPHARM_NO_VIDEO=true). Нужно для VM без GPU, где VLC
            // не рендерит видео и подвешивает окно (Q перестаёт работать, т.к. видео-контрол
            // перехватывает клавиатуру). С отключённым видео левый экран чёрный, но чек +
            // рекомендации (D) + CDP (C) работают, и клавиши не перехватываются.
            if (_posmConfig?.VideoEnabled != false)
            {
                Core.Initialize();
                // Аргументы VLC настраиваются (EPHARM_VLC_ARGS) — для перебора режимов вывода в VM.
                var vlcArgs = (_posmConfig?.VlcArgs ?? "--avcodec-hw=none")
                    .Split(' ', StringSplitOptions.RemoveEmptyEntries);
                _libVLC = new LibVLC(vlcArgs);
                Log($"VLC args: {string.Join(' ', vlcArgs)}");
                _mediaPlayer = new MediaPlayer(_libVLC);
                VideoView.MediaPlayer = _mediaPlayer;

                var videoPath = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.Desktop), "promo.mp4");
                _videoSources = new System.Collections.Generic.List<string> { videoPath };
                _videoIndex = -1;
                _mediaPlayer.EndReached += (_, __) =>
                    System.Windows.Application.Current.Dispatcher.BeginInvoke(new Action(PlayNextVideo));
                PlayNextVideo();
                StartVideoWatchdog(); // авто-перезапуск при зависании (софт-декод в VM)

                // подменяем локальное видео плейлистом из админ-панели (если backend доступен)
                _ = LoadBackendPlaylistAsync();
                // и далее периодически опрашиваем — смена плейлиста в админке подхватывается
                // без перезапуска кассы (HTTP-поллинг).
                StartPlaylistPolling();
            }
            else
            {
                Log("Видео отключено (EPHARM_NO_VIDEO) — левый экран без видео.");
            }

StartLogReader();

            // Авто-обновление клиента из админки (fail-safe, no-op без POSM/при выкл.).
            StartUpdateChecks();

            // Heartbeat кассы (T4): отмечаемся «подключены» на backend сразу и каждые ~60с.
            // Независим от видео (работает и при EPHARM_NO_VIDEO); no-op без POSM.
            StartHeartbeatPolling();

        }

private void NextVideo()
{
    if (System.Threading.Interlocked.Exchange(ref _switching, 1) == 1)
        return;

    Dispatcher.BeginInvoke(new Action(() =>
    {
        try
        {
            if (_playlist.Count == 0) return;

            // максимум N попыток найти существующий файл, чтобы не уйти в бесконечную рекурсию
            int tries = _playlist.Count;

            while (tries-- > 0)
            {
                _index = (_index + 1) % _playlist.Count;
                var path = _playlist[_index];

                if (!File.Exists(path))
                    continue; // файла нет — берем следующий

                var newMedia = new Media(_libVLC!, new Uri(path));

_mediaPlayer!.Play(newMedia);

                _currentMedia?.Dispose();
                _currentMedia = newMedia;
                return;
            }

            // Если ни одного файла не нашли
            _mediaPlayer?.Stop();
        }
        finally
        {
            System.Threading.Interlocked.Exchange(ref _switching, 0);
        }
    }));
}

private void PositionWindowToTopRightQuarter()
{
    // Получаем рабочую область экрана (без панели задач)
    var screenWidth = SystemParameters.WorkArea.Width;
    var screenHeight = SystemParameters.WorkArea.Height;

    // Устанавливаем размер окна = 1/4 экрана
    this.Width = screenWidth / 2;
    this.Height = screenHeight / 2;

    // Позиционируем справа сверху
    this.Left = screenWidth - this.Width;
    this.Top = 0;
}
        private void MoveToSecondScreenFullscreen()
{
    var screens = Screen.AllScreens;

    // если есть второй монитор — используем его (это КЛИЕНТСКИЙ экран: промо + чек)
    var target = screens.Length > 1 ? screens[1] : screens[0];
    CustomerScreen = target; // запоминаем клиентский монитор — popup рекомендаций пойдёт на ДРУГОЙ

    Left = target.Bounds.Left;
    Top = target.Bounds.Top;
    Width = target.Bounds.Width;
    Height = target.Bounds.Height;

    WindowState = WindowState.Maximized;
}


        private void UpdateTotal()
        {
            var total = ReceiptItems.Sum(i => i.Price);
            TbTotal.Text = $"{total:n0} ₸";
        }

private void StartLogReader()
{
    _logCts?.Cancel();
    _logCts = new CancellationTokenSource();

    // Запускаем tail на КАЖДЫЙ путь-кандидат: оба кормят один парсер
    // (ProcessLogLine). Отсутствующий файл — его цикл просто ждёт появления.
    var token = _logCts.Token;
    foreach (var p in _logPaths)
    {
        var path = p; // захват в замыкание
        Log($"Слушаю лог: {path}");
        _ = Task.Run(() => TailLogLoop(path, token));
    }
}

private async Task TailLogLoop(string path, CancellationToken token)
{
    var warnedMissing = false; // чтобы не флудить лог каждые 0.5с по отсутствующему пути
    while (!token.IsCancellationRequested)
    {
        try
        {
            // ждём пока файл появится
            if (!File.Exists(path))
            {
                if (!warnedMissing) { Log($"Лог-файл не найден, жду появления: {path}"); warnedMissing = true; }
                await Task.Delay(500, token);
                continue;
            }
            warnedMissing = false;

            using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
using var reader = new StreamReader(fs, Encoding.GetEncoding(1251));
           // using var reader = new StreamReader(fs, Encoding.UTF8);
Log($"Лог-файл открыт: {path}");
            // читаем только новое (с конца)
            fs.Seek(0, SeekOrigin.End);

            while (!token.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync();

                if (line == null)
                {
                    // если файл "перезаписали" (обнулили) — откроем заново
                    if (fs.Length < fs.Position)
                        break;

                    await Task.Delay(200, token);
                    continue;
                }
if (line != null)
{
    Log($"Считана строка: {line}");
}

                ProcessLogLine(line);
            }
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch
        {
            // если касса временно блокнула файл / ротация / ошибка — пробуем снова
            await Task.Delay(500, token);
        }
    }
}

private void ProcessLogLine(string line)
{
    // Тут будет твоя реальная логика триггеров.
    // Пока даю универсальные заглушки:

if (line.Contains("ChequeList.OnChange"))
{
    HandleChequeDiscount(line);
    return;
}

    if (line.Contains("Add2Cheque") && !line.Contains("(delete)"))
{
    var item = TryParseAdd2Cheque(line);
    if (item == null) return;

    Dispatcher.Invoke(() =>
    {
        UpsertItemSetQty(item);
        RecalcTotal();
        OnCartChanged(); // → запрос рекомендаций по обновлённой корзине
    });
    return;
}
// Очистка чека после печати
if (line.Contains("RunScriptByIndex") &&
    line.Contains("После печати очереди чеков"))
{
    Log("Обнаружено завершение чека. Очищаем чек.");

    Dispatcher.Invoke(() =>
    {
        OnReceiptFinalized(); // сначала фиксируем продажу (источник №1), пока позиции ещё в чеке
        ReceiptItems.Clear();
        RecalcTotal();
    });

    return;
}
// Удаление позиции
if (line.Contains("Add2Cheque") && line.Contains("(delete)"))
{
    var partId = TryParsePartIdFromDelete(line);
    if (partId == null) return;

    Dispatcher.Invoke(() =>
    {
        RemoveItemByPartId(partId.Value);
        RecalcTotal();
    });

    return;
}

}

private void UpsertItemSetQty(ReceiptItem incoming)
{
    var existing = ReceiptItems.FirstOrDefault(x => x.PartId == incoming.PartId);

    if (existing == null)
    {
        ReceiptItems.Insert(0, incoming);
        Log($"Добавили новую позицию (PartId={incoming.PartId}): {incoming.Name}, qty={incoming.Qty}");
        return;
    }
if (incoming.Qty <= 0)
{
    RemoveItemByPartId(incoming.PartId);
    return;
}

    // обновляем поля + ставим новое количество
    var idx = ReceiptItems.IndexOf(existing);

    existing.Name = incoming.Name;
    existing.Price = incoming.Price;
    existing.DiscountPercent = incoming.DiscountPercent;
    existing.Qty = incoming.Qty;

    // чтобы UI точно обновился без INotifyPropertyChanged — пере-вставляем
    ReceiptItems[idx] = existing;

    Log($"Обновили позицию (PartId={incoming.PartId}): qty={existing.Qty}");
}


private ReceiptItem? TryParseAdd2Cheque(string line)
{
    try
    {
        // iPartID=80309(80309)
        var partIdStr = ExtractBetween(line, "iPartID=", "(")?.Trim();
        var name = ExtractBetween(line, "sname=", ";")?.Trim();
        var priceStr = ExtractBetween(line, "price=", ";")?.Trim();
        var qtyStr = ExtractBetween(line, "quant=", null)?.Trim();

        if (string.IsNullOrWhiteSpace(partIdStr) ||
            string.IsNullOrWhiteSpace(name) ||
            string.IsNullOrWhiteSpace(priceStr) ||
            string.IsNullOrWhiteSpace(qtyStr))
            return null;

        var partId = int.Parse(partIdStr);
        var price = ParseDecimalSmart(priceStr);
        var qty = ParseDecimalSmart(qtyStr);

        return new ReceiptItem
        {
            PartId = partId,
            Name = name,
            Price = price,
            Qty = qty,
            DiscountPercent = 0m
        };
    }
    catch
    {
        return null;
    }
}

private static string? ExtractBetween(string s, string start, string? end)
{
    var i = s.IndexOf(start, StringComparison.OrdinalIgnoreCase);
    if (i < 0) return null;
    i += start.Length;

    if (end == null)
        return s.Substring(i);

    var j = s.IndexOf(end, i, StringComparison.OrdinalIgnoreCase);
    if (j < 0) return s.Substring(i);

    return s.Substring(i, j - i);
}

private int? TryParsePartIdFromDelete(string line)
{
    try
    {
        // iPartID=80306,
        var partIdStr = ExtractBetween(line, "iPartID=", ",")?.Trim();
        if (string.IsNullOrWhiteSpace(partIdStr)) return null;
        return int.Parse(partIdStr);
    }
    catch
    {
        return null;
    }
}

private void RemoveItemByPartId(int partId)
{
    var existing = ReceiptItems.FirstOrDefault(x => x.PartId == partId);
    if (existing == null)
    {
        Log($"(delete) Позиция не найдена в UI. PartId={partId}");
        return;
    }

    ReceiptItems.Remove(existing);
    Log($"(delete) Удалили позицию из UI. PartId={partId}, Name={existing.Name}");
}


private static decimal ParseDecimalSmart(string s)
{
    // приводим к единому виду: убираем пробелы, меняем запятую на точку
    // работает для "350,00" и для "1,000"
    s = s.Trim().Replace(" ", "").Replace(",", ".");
    return decimal.Parse(s, System.Globalization.CultureInfo.InvariantCulture);
}
private void HandleChequeDiscount(string line)
{
    try
    {
        // 6435,00 (0,00) / 6435,00 (-645,00)

        var rightPart = line.Split('/')[1].Trim();

        var totalStr = ExtractBetween(rightPart, "", "(")?.Trim();
        var discountStr = ExtractBetween(rightPart, "(", ")")?.Trim();

        if (string.IsNullOrWhiteSpace(totalStr) ||
            string.IsNullOrWhiteSpace(discountStr))
            return;

        var total = ParseDecimalSmart(totalStr);
        var discountAmount = ParseDecimalSmart(discountStr);

        discountAmount = Math.Abs(discountAmount);

        if (total == 0) return;

        var percent = (discountAmount / total) * 100m;
percent = Math.Round(percent, 2, MidpointRounding.AwayFromZero); // например 10.00

        Log($"Скидка по чеку: {percent:0.##}%");

        Dispatcher.Invoke(() =>
        {
            ApplyDiscountToAllItems(percent);
            RecalcTotal();
        });
    }
    catch (Exception ex)
    {
        Log($"Ошибка обработки скидки: {ex.Message}");
    }
}

private void ApplyDiscountToAllItems(decimal percent)
{
    for (int i = 0; i < ReceiptItems.Count; i++)
    {
        var item = ReceiptItems[i];

percent = Math.Round(percent, 2, MidpointRounding.AwayFromZero); // например 10.00
        item.DiscountPercent = percent;

        // пере-вставляем чтобы UI обновился
        ReceiptItems[i] = item;
    }
ItemsList.Items.Refresh();
}

        private void OnClosed(object? sender, EventArgs e)
        {
            try
            {
                _mediaPlayer?.Stop();
                _mediaPlayer?.Dispose();
                _libVLC?.Dispose();
            }
            catch { /* ignore */ }
        }
    }
}
