using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Forms;
using System.Windows.Input;
using CustomerDisplay.Models;
using CustomerDisplay.Services;
using LibVLCSharp.Shared;




namespace CustomerDisplay
{
    public partial class MainWindow : Window
    {
private CancellationTokenSource? _logCts;
// A reader is started once per physical path. Standard-N installations differ between
// pharmacies, so legacy/configured paths start immediately and bounded discovery adds real paths.
private readonly ConcurrentDictionary<string, byte> _activeLogReaders =
    new(StringComparer.OrdinalIgnoreCase);


public ObservableCollection<ReceiptItem> ReceiptItems { get; } = new();
private void RecalcTotal()
{
       var total = ReceiptItems.Sum(x => x.Total); // уже округлено на уровне строки
    TbTotal.Text = total.ToString("#,0.##") + " тг";

}
private Media? _currentMedia;
        private LibVLC? _libVLC;
        private MediaPlayer? _mediaPlayer;

     
// Куда писать лог: env EPHARM_APP_LOG, иначе единый production/dev путь C:\Epharm.
// Путь печатается в баннере старта (LogStartupBanner), чтобы его было легко найти.
private static string LogPath = ResolveLogPath();
private static string ResolveLogPath()
{
    var env = Environment.GetEnvironmentVariable("EPHARM_APP_LOG");
    if (!string.IsNullOrWhiteSpace(env)) return env!;
    return @"C:\Epharm\customerdisplay.log";
}

private static void ConfigureLogPath(string? configuredPath)
{
    if (!string.IsNullOrWhiteSpace(configuredPath))
        LogPath = configuredPath.Trim();
}

// Лог пишем в UTF-8 с BOM, чтобы кириллица корректно открывалась в любом редакторе
// (Блокнот без BOM иногда читает как ANSI → «Лог-файлa net…»). BOM пишется один раз
// при создании файла; на дозапись существующего не дублируется.
private static readonly Encoding LogEncoding = new UTF8Encoding(encoderShouldEmitUTF8Identifier: true);

private static void Log(string msg)
{
    var line = $"{DateTime.Now:HH:mm:ss} {msg}";
    try { File.AppendAllText(LogPath, line + "\r\n", LogEncoding); } catch { }
    // В debug-режиме консоль подключена к терминалу dotnet run (EnsureDebugConsole) —
    // лог виден прямо там, без отдельного окна tail. Без консоли — тихий no-op.
    if (!ConsoleLogDisabled())
    {
        try { Console.WriteLine(line); } catch { }
    }
}

private const int ATTACH_PARENT_PROCESS = -1;

[DllImport("kernel32.dll", SetLastError = true)]
private static extern bool AttachConsole(int dwProcessId);

// Подхватываем консоль РОДИТЕЛЬСКОГО процесса (терминал, из которого запущен dotnet run
// или сам .exe), чтобы Console.WriteLine из Log() печатался прямо туда. Из проводника
// (родителя-консоли нет) — тихий no-op, лог идёт только в файл. Безопасно вызывать всегда.
private static void EnsureDebugConsole()
{
    if (ConsoleLogDisabled()) return;
    try { AttachConsole(ATTACH_PARENT_PROCESS); } catch { }
}

private static bool ConsoleLogDisabled()
{
    var v = (Environment.GetEnvironmentVariable("EPHARM_DISABLE_CONSOLE_LOG") ?? "")
        .Trim()
        .ToLowerInvariant();
    return v is "1" or "true" or "yes" or "on";
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
            EnsureDebugConsole(); // EPHARM_DEBUG=1 → лог в терминал dotnet run
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

            LogStartupBanner();
            ApplyScreenMode();

            // Видео можно отключить (env EPHARM_NO_VIDEO=true). Нужно для VM без GPU, где VLC
            // не рендерит видео и подвешивает окно (Q перестаёт работать, т.к. видео-контрол
            // перехватывает клавиатуру). С отключённым видео левый экран чёрный, но чек +
            // рекомендации (D) + CDP (C) работают, и клавиши не перехватываются.
            // При _customerHidden (prod + 1 монитор) видео не запускаем — окна нет.
            if (_posmConfig?.VideoEnabled != false && !_customerHidden)
            {
                Core.Initialize();
                // Аргументы VLC настраиваются (EPHARM_VLC_ARGS) — для перебора режимов вывода в VM.
                // Принудительно дотягиваем --quiet/--verbose=0, даже если в posm.json/env их нет,
                // чтобы нативный VLC не спамил h264-ошибками в консоль кассы (софт-декод в VM).
                var argList = (_posmConfig?.VlcArgs ?? "--avcodec-hw=none")
                    .Split(' ', StringSplitOptions.RemoveEmptyEntries).ToList();
                if (!argList.Any(a => a.StartsWith("--quiet"))) argList.Add("--quiet");
                if (!argList.Any(a => a.StartsWith("--verbose"))) argList.Add("--verbose=0");
                var vlcArgs = argList.ToArray();
                _libVLC = new LibVLC(vlcArgs);
                _mediaPlayer = new MediaPlayer(_libVLC);
                VideoView.MediaPlayer = _mediaPlayer;

                // Видео берём ТОЛЬКО из админки (активный плейлист с backend). Локального
                // promo.mp4 с Рабочего стола больше нет: пусто/оффлайн → экран без видео,
                // пока админка не отдаст плейлист (поллинг подхватит позже).
                _videoSources = new System.Collections.Generic.List<string>();
                _videoIndex = -1;
                _mediaPlayer.EndReached += (_, __) =>
                    System.Windows.Application.Current.Dispatcher.BeginInvoke(new Action(PlayNextVideo));
                StartVideoWatchdog(); // авто-перезапуск при зависании (софт-декод в VM)
                TryStartCachedPlaylist(); // offline-start: последний успешно скачанный плейлист

                // Тянем активный плейлист из админ-панели (и далее периодически опрашиваем).
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
            // Production Standard-N builds may omit Add2Cheque payloads from zkassa.log. The
            // authoritative active receipt monitor keeps recommendations and the customer display
            // working from DOCS/DOC_DETAIL_ACTIVE; log tailing above remains a compatibility path.
            StartStandardNReceiptPolling();

            // Авто-обновление клиента из админки (fail-safe, no-op без POSM/при выкл.).
            StartUpdateChecks();

            // Heartbeat кассы (T4): отмечаемся «подключены» на backend сразу и каждые ~60с.
            // Независим от видео (работает и при EPHARM_NO_VIDEO); no-op без POSM.
            StartHeartbeatPolling();
            StartFulfillment();

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
        // Клиентский экран скрыт (prod + один монитор) — видео не запускаем, окно не показываем.
        private bool _customerHidden;

        // Итоговый режим экрана: EPHARM_DEBUG=1 принудительно → "dev"; иначе из конфига ScreenMode.
        private string ResolveScreenMode()
        {
            var dbg = (Environment.GetEnvironmentVariable("EPHARM_DEBUG") ?? "").Trim().ToLowerInvariant();
            if (dbg is "1" or "true" or "yes" or "on") return "dev";
            var mode = (_posmConfig?.ScreenMode ?? "dev").Trim().ToLowerInvariant();
            return mode == "prod" ? "prod" : "dev";
        }

        // Размещение клиентского экрана:
        //   dev  — оконце 460×820 слева-сверху на основном мониторе (рядом виден терминал/лог);
        //   prod — есть 2-й монитор → fullscreen-киоск на нём; один монитор → НЕ показываем вообще.
        private void ApplyScreenMode()
        {
            var screens = Screen.AllScreens;
            var mode = ResolveScreenMode();

            if (mode == "prod")
            {
                if (screens.Length >= 2)
                {
                    // The customer display is the non-primary screen. Screen.AllScreens ordering
                    // is not a contract and differs between video drivers/docking stations.
                    var target = screens.FirstOrDefault(s => !s.Primary) ?? screens[1];
                    CustomerScreen = target; // popup рекомендаций пойдёт на ДРУГОЙ (фармацевта)
                    Topmost = true;
                    WindowStyle = WindowStyle.None;
                    ResizeMode = ResizeMode.NoResize;
                    Left = target.Bounds.Left;
                    Top = target.Bounds.Top;
                    Width = target.Bounds.Width;
                    Height = target.Bounds.Height;
                    WindowState = WindowState.Maximized;
                    Log($"PROD: клиентский экран — монитор #2 ({target.Bounds.Width}x{target.Bounds.Height}).");
                }
                else
                {
                    // Один монитор: клиентский экран не показываем (только попап рекомендаций фармацевту).
                    CustomerScreen = screens.Length > 0 ? screens[0] : Screen.PrimaryScreen;
                    _customerHidden = true;
                    Hide();
                    Log("PROD: один монитор → клиентский экран СКРЫТ; работают только рекомендации фармацевту.");
                }
                return;
            }

            // dev: маленькое оконце слева-сверху, с рамкой, не поверх всех.
            Topmost = false;
            WindowStyle = WindowStyle.SingleBorderWindow;
            ResizeMode = ResizeMode.CanResize;
            WindowState = WindowState.Normal;
            var wa = (Screen.PrimaryScreen ?? Screen.AllScreens[0]).WorkingArea;
            Left = wa.Left + 8;
            Top = wa.Top + 8;
            Width = 460;
            Height = 820;
            // In DEV the small customer preview does not reserve a physical display. Keeping this
            // null makes pharmacist recommendations open on the primary monitor even when Windows
            // has a second customer-facing screen attached.
            CustomerScreen = null;
            Title = "Epharm POSM — DEV (окно слева)";
            Log("DEV: оконце слева-сверху (рядом терминал/лог).");
        }

        // Баннер старта: одним блоком в логе — куда пишется лог, какой backend/аптека, включён ли
        // POSM, видео, режим экрана, период опроса плейлиста и какие логи кассы слушаем. Чтобы
        // второй разработчик сразу видел всю картину «что и куда подключается».
        private void LogStartupBanner()
        {
            Log("==================== Epharm POSM — старт ====================");
            Log($"Лог приложения: {LogPath}");
            Log($"Мониторов: {Screen.AllScreens.Length}; режим экрана: {ResolveScreenMode()}");
            var c = _posmConfig;
            if (c == null)
            {
                Log("Конфиг posm.json не загружен — POSM выключен.");
            }
            else
            {
                Log($"Backend: {string.Join(" -> ", c.GetBackendBaseUris())}");
                Log($"Аптека: {(string.IsNullOrWhiteSpace(c.PharmacyId) ? "—" : c.PharmacyId)}; " +
                    $"фармацевт: {(string.IsNullOrWhiteSpace(c.PharmacistId) ? "—" : "задан")}; " +
                    $"POSM включён: {c.Enabled}; видео: {c.VideoEnabled}; " +
                    $"опрос плейлиста: {c.PlaylistPollSec}с; рекомендации: только при скане, debounce {c.DebounceMs}мс");
                Log("Начальные пути логов кассы Стандарт-Н: " +
                    string.Join(" | ", StandardNLogLocator.BootstrapCandidates(c)));
                Log("Фоновое обнаружение zkassa.log включено только около Standard-N/кассовых процессов и каталогов.");
                Log($"БД Стандарт-Н: {(_standardNDb == null ? "не инициализирована" : _standardNDb.Describe())}");
            }
            Log("============================================================");
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
    _activeLogReaders.Clear();
    var token = _logCts.Token;

    // Explicit/cached/v1.0.23 paths are watched immediately even if Standard-N creates them later.
    foreach (var path in StandardNLogLocator.BootstrapCandidates(_posmConfig))
        StartLogTail(path, token, "начальный путь");

    // Real pharmacies use different drive letters and install folders. Discovery is deliberately
    // bounded and runs off the UI thread; it never scans all files on the workstation.
    _ = Task.Run(() => DiscoverStandardNLogsLoop(token), token);
}

private async Task DiscoverStandardNLogsLoop(CancellationToken token)
{
    var firstPass = true;
    while (!token.IsCancellationRequested)
    {
        try
        {
            var before = _activeLogReaders.Count;
            foreach (var path in StandardNLogLocator.DiscoverExisting(_posmConfig))
                StartLogTail(path, token, "обнаружен автоматически");

            if (firstPass)
            {
                var added = _activeLogReaders.Count - before;
                Log(added > 0
                    ? $"Обнаружение Standard-N добавило логов: {added}"
                    : "Обнаружение Standard-N: дополнительных zkassa.log пока не найдено; повторяю в фоне");
                firstPass = false;
            }
        }
        catch (Exception ex)
        {
            Log($"Обнаружение Standard-N временно недоступно: {ex.GetBaseException().Message}");
        }

        try { await Task.Delay(TimeSpan.FromSeconds(30), token); }
        catch (OperationCanceledException) { return; }
    }
}

private void StartLogTail(string path, CancellationToken token, string source)
{
    string fullPath;
    try { fullPath = Path.GetFullPath(path); }
    catch { return; }

    if (!_activeLogReaders.TryAdd(fullPath, 0)) return;
    Log($"Слушаю лог ({source}): {fullPath}");
    _ = Task.Run(() => TailLogLoop(fullPath, token), token);
}

private async Task TailLogLoop(string path, CancellationToken token)
{
    DateTime lastErrorAt = DateTime.MinValue;
    string? lastError = null;
    var cashLogConfirmed = false;
    while (!token.IsCancellationRequested)
    {
        try
        {
            // Ждём появления файла тихо. Список прослушиваемых путей уже напечатан в
            // стартовом баннере; повторять "не найден" в рабочем логе не нужно.
            if (!File.Exists(path))
            {
                await Task.Delay(1000, token);
                continue;
            }

            using var fs = new FileStream(
                path,
                FileMode.Open,
                FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete,
                bufferSize: 4096,
                FileOptions.SequentialScan);
            // читаем только новое (с конца)
            fs.Seek(0, SeekOrigin.End);
            var openedCreationTimeUtc = File.GetCreationTimeUtc(path);
            Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
            using var reader = new StreamReader(fs, Encoding.GetEncoding(1251));
            cashLogConfirmed = StandardNLogLocator.IsConfirmedCashLog(path);
            if (cashLogConfirmed)
            {
                Log($"POSM готов принимать сканы: открыт {path}");
                StandardNLogLocator.RememberConfirmed(path);
            }
            else
            {
                Log($"Слушаю кандидат кассового лога: {path}; подтвержу его на первой строке Standard-N");
            }

            while (!token.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync();

                if (line == null)
                {
                    // Reopen after truncate or rotation/replacement. FileShare.Delete allows
                    // Standard-N to rotate the file without blocking either process.
                    var current = new FileInfo(path);
                    current.Refresh();
                    if (!current.Exists || current.Length < fs.Position ||
                        current.CreationTimeUtc != openedCreationTimeUtc) break;

                    await Task.Delay(200, token);
                    continue;
                }
                Log($"Считана строка: {line}");
                if (!cashLogConfirmed && StandardNLogLocator.IsCashEventLine(line))
                {
                    cashLogConfirmed = true;
                    Log($"POSM готов принимать сканы: открыт {path}");
                    StandardNLogLocator.RememberConfirmed(path);
                }
                ProcessLogLine(line!);
            }
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception ex)
        {
            // если касса временно блокнула файл / ротация / ошибка — пробуем снова
            var key = ex.GetBaseException().Message;
            if (!string.Equals(lastError, key, StringComparison.Ordinal) ||
                DateTime.Now - lastErrorAt >= TimeSpan.FromSeconds(60))
            {
                lastError = key;
                lastErrorAt = DateTime.Now;
                Log($"Чтение zkassa.log временно недоступно ({path}): {key}; повторяю автоматически");
            }
            await Task.Delay(500, token);
        }
    }
}

private void ProcessLogLine(string line)
{
    // Тут будет твоя реальная логика триггеров.
    // Пока даю универсальные заглушки:

    // kassir=/cashier= из лога — fallback-источник фармацевта: работает ВСЕГДА, но не перебивает
    // значение из БД Стандарт-Н (ACTIVEUSERS/открытая сессия — приоритетный источник). Там, где
    // Firebird недоступен POSM-клиенту, лог — единственный шанс узнать кассира.
    {
        var kassir = ExtractCashier(line);
        if (!string.IsNullOrWhiteSpace(kassir) && !_pharmacistFromDb &&
            !string.Equals(_currentPharmacistId, kassir, StringComparison.OrdinalIgnoreCase))
        {
            _currentPharmacistId = kassir!;
            _currentPharmacistName = kassir!;
            _currentStandardNSessionId = null;
            Log($"Фармацевт из лога кассы (kassir=): {kassir}");
        }
    }

if (line.Contains("ChequeList.OnChange", StringComparison.OrdinalIgnoreCase))
{
    HandleChequeDiscount(line);
    return;
}

    if (line.Contains("Add2Cheque", StringComparison.OrdinalIgnoreCase) &&
        !line.Contains("(delete)", StringComparison.OrdinalIgnoreCase))
{
    RefreshCurrentPharmacistFromStandardNDb();
    var item = TryParseAdd2Cheque(line);
    if (item == null)
    {
        Log("POSM scan parse failed: строка Add2Cheque не содержит корректные iPartID/quant; см. предыдущую строку лога");
        return;
    }
    var priceSource = EnrichItemFromStandardNDb(item);
    LogScannedItemContext(item, priceSource);

    Dispatcher.Invoke(() =>
    {
        var shouldAskBackend = UpsertItemSetQty(item);
        RecalcTotal();
        if (shouldAskBackend)
            OnProductScanned(item); // → запрос рекомендаций только после скана/добавления товара
        else
            OnCartChangedLocalOnly();
    });
    return;
}
// Очистка чека после печати
if (line.Contains("RunScriptByIndex", StringComparison.OrdinalIgnoreCase) &&
    line.Contains("После печати очереди чеков", StringComparison.OrdinalIgnoreCase))
{
    Log("Обнаружено подтверждённое завершение чека в логе печати Standard-N.");

    Dispatcher.Invoke(() =>
    {
        var documentId = _standardNDocumentId;
        var finalized = OnReceiptFinalized("standardn-print-log", documentId);
        if (finalized && documentId.HasValue)
            _lastFinalizedStandardNDocumentId = documentId.Value;
        ReceiptItems.Clear();
        RecalcTotal();
    });

    return;
}
// Удаление позиции
if (line.Contains("Add2Cheque", StringComparison.OrdinalIgnoreCase) &&
    line.Contains("(delete)", StringComparison.OrdinalIgnoreCase))
{
    var partId = TryParsePartIdFromDelete(line);
    if (partId == null) return;

    Dispatcher.Invoke(() =>
    {
        RemoveItemByPartId(partId.Value);
        RecalcTotal();
        OnCartChangedLocalOnly();
    });

    return;
}

}

private bool UpsertItemSetQty(ReceiptItem incoming)
{
    var existing = ReceiptItems.FirstOrDefault(x => x.PartId == incoming.PartId);

    if (incoming.Qty <= 0)
    {
        RemoveItemByPartId(incoming.PartId);
        return false;
    }

    if (existing == null)
    {
        ReceiptItems.Insert(0, incoming);
        Log($"Добавили новую позицию (PartId={incoming.PartId}): {incoming.Name}, qty={incoming.Qty}");
        return true;
    }

    // обновляем поля + ставим новое количество
    var idx = ReceiptItems.IndexOf(existing);
    var previousQty = existing.Qty;
    existing.Name = incoming.Name;
    // Не затираем уже пойманный штрих-код, если повторная строка лога (qty-bump/скидка) его не несёт.
    if (!string.IsNullOrWhiteSpace(incoming.Barcode)) existing.Barcode = incoming.Barcode;
    existing.Price = incoming.Price;
    existing.DiscountPercent = incoming.DiscountPercent;
    existing.Qty = incoming.Qty;

    // чтобы UI точно обновился без INotifyPropertyChanged — пере-вставляем
    ReceiptItems[idx] = existing;

    Log($"Обновили позицию (PartId={incoming.PartId}): qty={existing.Qty}");
    return incoming.Qty > previousQty;
}


private ReceiptItem? TryParseAdd2Cheque(string line)
{
    try
    {
        // iPartID кассы — ведущие цифры после "iPartID=" (разделитель может быть "(", ";" или ",").
        var partIdStr = ExtractPartId(line);
        var name = ExtractBetween(line, "sname=", ";")?.Trim();
        var priceStr = ExtractBetween(line, "price=", ";")?.Trim();
        var qtyStr = ExtractBetween(line, "quant=", ";")?.Trim();

        if (string.IsNullOrWhiteSpace(partIdStr) ||
            string.IsNullOrWhiteSpace(qtyStr))
            return null;

        var partId = int.Parse(partIdStr);
        var price = string.IsNullOrWhiteSpace(priceStr) ? 0m : ParseDecimalSmart(priceStr);
        var qty = ParseDecimalSmart(qtyStr);

        // EAN-13 — ключ матчинга на backend. Извлекаем робастно (реальный формат zkassa.log неизвестен).
        var barcode = ExtractBarcode(line, partIdStr);

        return new ReceiptItem
        {
            PartId = partId,
            Barcode = barcode,
            Name = name ?? "",
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

private void RefreshCurrentPharmacistFromStandardNDb()
{
    if (_posmConfig?.StandardNDbEnabled != true || _standardNDb == null) return;

    // Различаем исходы: ошибка БД (ok=false) — прежнее значение НЕ трогаем (временный сбой
    // Firebird не значит «кассир вышел»; ошибка уже залогирована rate-limited внутри lookup).
    var ok = _standardNDb.TryGetActivePharmacist(out var active);
    if (!ok) return;

    if (active == null)
    {
        // БД доступна, но активного пользователя нет. Очищаем ТОЛЬКО значение, которое сами же
        // брали из БД (кассир реально вышел). Значение из лога (kassir=) — не наша юрисдикция:
        // на кассах без доступной активной сессии лог — единственный источник, его не затираем.
        if (_pharmacistFromDb)
        {
            Log("Активный фармацевт из БД Стандарт-Н не найден — pharmacistId очищен");
            _currentPharmacistId = "";
            _currentPharmacistName = "";
            _currentStandardNSessionId = null;
            _pharmacistFromDb = false;
        }
        return;
    }

    var previous = _currentPharmacistId;
    _currentPharmacistId = active.Id;
    _currentPharmacistName = active.Name ?? "";
    _currentStandardNSessionId = active.SessionId;
    _pharmacistFromDb = true;

    if (!string.Equals(previous, _currentPharmacistId, StringComparison.OrdinalIgnoreCase))
    {
        Log($"Активный фармацевт из БД Стандарт-Н: id={active.Id}, " +
            $"name={active.Name ?? "—"}, session={active.SessionId?.ToString() ?? "—"}");
    }
}

private string EnrichItemFromStandardNDb(ReceiptItem item)
{
    // Базовая цена — из лога кассы (price=… в строке Add2Cheque). Стандарт-Н сам пишет её в свой
    // лог, значит это РЕАЛЬНАЯ цена позиции в чеке — достоверна для зеркала покупателя.
    // БД Firebird здесь = ОБОГАЩЕНИЕ (штрихкод/имя) и запасной источник цены, а НЕ замена лога.
    var source = item.Price > 0m ? "лог кассы" : "нет в логе";
    if (_posmConfig?.StandardNDbEnabled != true) return source;

    StandardNProductInfo? dbProduct = null;
    try { dbProduct = _standardNDb?.GetProduct(item.PartId, item.Barcode); } catch { /* БД опциональна */ }

    if (dbProduct == null)
    {
        // Firebird недоступен/товара нет в ztrade. НЕ обнуляем цену — остаёмся на цене из лога.
        // (Раньше здесь было item.Price=0 → на зеркале «—» на любой кассе без доступной БД.)
        return item.Price > 0m ? "лог кассы (БД недоступна)" : "нет в логе, БД недоступна";
    }

    if (string.IsNullOrWhiteSpace(item.Barcode) && !string.IsNullOrWhiteSpace(dbProduct.Barcode))
        item.Barcode = dbProduct.Barcode;

    if (string.IsNullOrWhiteSpace(item.Name) && !string.IsNullOrWhiteSpace(dbProduct.Name))
        item.Name = dbProduct.Name;

    if (dbProduct.Price > 0m)
    {
        if (item.Price <= 0m)
        {
            // В логе цены не было — подставляем из БД.
            item.Price = dbProduct.Price;
            source = $"БД Стандарт-Н/{dbProduct.Source}";
        }
        else if (item.Price != dbProduct.Price)
        {
            // Ненулевую цену из лога (= цену чека) не перезаписываем: на зеркале должна быть
            // ровно она. Расхождение только логируем для диагностики.
            Log($"Цена: лог={FormatMoneyForLog(item.Price)} vs БД={FormatMoneyForLog(dbProduct.Price)} " +
                $"(PartId={item.PartId}) — оставляю цену из лога (цена чека)");
        }
    }

    return source;
}

private void LogScannedItemContext(ReceiptItem item, string priceSource)
{
    Log($"Скан товара: pharmacistId={(string.IsNullOrWhiteSpace(_currentPharmacistId) ? "—" : _currentPharmacistId)}, " +
        $"PartId={item.PartId}, EAN={item.Barcode ?? "—"}, цена={FormatMoneyForLog(item.Price)} " +
        $"({priceSource}), qty={item.Qty:0.###}, name={item.Name}");
}

private static string FormatMoneyForLog(decimal value) =>
    value > 0m ? $"{value:0.##} тг" : "—";

/// <summary>
/// Робастное извлечение EAN/GTIN из строки лога кассы:
///   (1) явное поле barcode/barcode1/ean/ean13/bcode/штрихкод (без учёта регистра);
///   (2) значение в скобках  iPartID=&lt;id&gt;(&lt;inner&gt;)  — если inner это 8/12/13/14 цифр И НЕ совпадает
///       с внутренним id (как в синтетическом примере 80309(80309), где это просто дубль id);
///   (3) иначе null (сработает fallback-матчинг по Name на backend).
/// </summary>
private static string? ExtractBarcode(string line, string partIdStr)
{
    // Standard-N releases use different field labels and separators. Restrict the value to digits
    // so a following field cannot accidentally become part of the barcode.
    var explicitMatch = Regex.Match(
        line,
        @"(?:barcode1?|bar_?code|ean(?:13)?|bcode|shtrih|штрих(?:код)?)\s*[:=]\s*[""']?(?<value>\d{8,14})",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    if (explicitMatch.Success)
    {
        var value = explicitMatch.Groups["value"].Value;
        if (IsBarcode(value)) return value;
    }

    // (2) value specifically attached to iPartID=<id>(<inner>), not an unrelated pair of brackets.
    var ipartMatch = Regex.Match(
        line,
        @"iPartID\s*=\s*\d+\s*\(\s*(?<value>\d{8,14})\s*\)",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    var inner = ipartMatch.Success ? ipartMatch.Groups["value"].Value : null;
    if (IsBarcode(inner) && !string.Equals(inner, partIdStr?.Trim(), StringComparison.Ordinal))
        return inner;

    // (3) штрих-кода нет — backend сматчит по имени
    return null;
}

/// <summary>EAN-подобный код: непустой, только цифры, длина 8/12/13/14.</summary>
private static bool IsBarcode(string? s)
{
    if (string.IsNullOrWhiteSpace(s)) return false;
    var len = s.Length;
    if (len != 8 && len != 12 && len != 13 && len != 14) return false;
    foreach (var ch in s)
        if (ch < '0' || ch > '9') return false;
    return true;
}

/// <summary>
/// iPartID кассы — ведущие цифры сразу после "iPartID=". Реальный разделитель неизвестен,
/// поэтому НЕ привязываемся к "(": берём цифры до первого не-цифрового символа. Работает для
/// "iPartID=80309(80309)", "iPartID=80309;sname=…", "iPartID=80306,". null если цифр нет.
/// </summary>
private static string? ExtractPartId(string line)
{
    const string key = "iPartID=";
    var i = line.IndexOf(key, StringComparison.OrdinalIgnoreCase);
    if (i < 0) return null;
    i += key.Length;
    var start = i;
    while (i < line.Length && line[i] >= '0' && line[i] <= '9') i++;
    return i > start ? line.Substring(start, i - start) : null;
}

/// <summary>
/// Explicit cashier/operator marker from a Standard-N log line. This is a fallback for
/// installations where Firebird is temporarily unavailable; the database remains authoritative.
/// </summary>
private static string? ExtractCashier(string line)
{
    var match = Regex.Match(
        line,
        @"(?:kassir|cashier|operator|seller|user_?id|user_?name|кассир|продавец|пользователь)\s*[:=]\s*[\""']?(?<value>[^;\t\r\n\""']+)",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    if (!match.Success) return null;

    var value = match.Groups["value"].Value.Trim().TrimEnd(',', '.');
    return value.Length is > 0 and <= 255 ? value : null;
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
        // iPartID=80306, — берём те же ведущие цифры, что и add-парсер (единый разбор).
        var partIdStr = ExtractPartId(line);
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
                _logCts?.Cancel();
                _standardNReceiptCts?.Cancel();
                _playlistPollCts?.Cancel();
                _mediaWarmupCts?.Cancel();
                _recoCts?.Cancel();
                _heartbeatTimer?.Stop();
                _videoWatchdog?.Stop();
                _updateTimer?.Stop();
                _mediaPlayer?.Stop();
                _mediaPlayer?.Dispose();
                _currentMedia?.Dispose();
                _libVLC?.Dispose();
                _fiscalReceiptTimer?.Dispose();
                StopFulfillment();
                _flusher?.Dispose();
                _appUpdater?.Dispose();
                _epharm?.Dispose();
                _standardNDb?.Dispose();
                _mediaCache?.Dispose();
            }
            catch { /* ignore */ }
        }
    }
}
