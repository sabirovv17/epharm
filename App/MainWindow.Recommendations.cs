using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;

namespace CustomerDisplay
{
    /// <summary>
    /// POSM-рекомендации (ТЗ §4) — аддитивная надстройка над существующим MainWindow.
    /// Логика тонкая и fail-safe: любые ошибки сети/конфига не должны влиять на работу кассы
    /// и зеркалирование чека. Вызовы-хуки (InitPosm / OnProductScanned /
    /// OnCartChangedLocalOnly / OnReceiptFinalized)
    /// дёргаются из MainWindow.xaml.cs в трёх точках.
    /// </summary>
    public partial class MainWindow
    {
        private EpharmConfig? _posmConfig;
        private EpharmApiClient? _epharm;
        private OfflineOutbox? _outbox;
        private OutboxFlusher? _flusher;
        private SaleReporter? _saleReporter;
        private ReceiptArtifactStore? _receiptArtifacts;
        private System.Threading.Timer? _fiscalReceiptTimer;
        private int _fiscalReceiptPollBusy;
        private string? _activeReceiptDraftId;
        private StandardNDbLookup? _standardNDb;
        private CheckoutSession _session = new();

        // Текущий фармацевт/кассир. Цепочка источников (по убыванию приоритета):
        //   1) БД Стандарт-Н: ACTIVEUSERS либо открытая SESSIONS/SP$SESSIONS;
        //   2) токен kassir=/cashier= из лога кассы — fallback, когда БД недоступна/пуста.
        // При ОШИБКЕ БД прежнее значение НЕ затирается (сбой БД ≠ «кассир вышел»).
        private string _currentPharmacistId = "";
        private string _currentPharmacistName = "";
        private long? _currentStandardNSessionId;
        // true — текущее значение пришло из БД (лог-fallback не должен его перебивать).
        private bool _pharmacistFromDb;

        private CancellationTokenSource? _recoCts;
        private readonly SemaphoreSlim _recommendGate = new(1, 1);
        private RecommendationWindow? _recoWindow;
        private ConflictNotificationWindow? _conflictWindow;

        // Рекомендации, которые уже показаны и всё ещё актуальны для текущей корзины.
        // Важно: backend идемпотентно возвращает тот же eventId для sessionId+ruleId. Если просто
        // держать eventId до конца чека, то сценарий "удалили товар → просканировали снова" больше
        // никогда не покажет рекомендацию. Поэтому состояние привязано к trigger barcode/name и
        // автоматически вычищается, когда исходного товара больше нет в корзине.
        private readonly Dictionary<string, ShownRecommendationState> _shownRecommendations = new();

        // Конфликты, уже показанные в этом чеке (подпись = kind+триггер+ruleIds) — чтобы
        // баннер «недоступно» не всплывал повторно на каждое изменение корзины.
        private readonly Dictionary<string, string?> _shownConflictSigs = new();

        private sealed class ShownRecommendationState
        {
            public string EventId { get; init; } = "";
            public RecommendationTriggerBinding? LocalTrigger { get; init; }
            public string? TriggerSku { get; init; }
            public string? TriggerIpartId { get; init; }
            public string? TriggerBarcode { get; init; }
            public string? TriggerName { get; init; }
        }

        /// <summary>
        /// Монитор КЛИЕНТА (куда выведен киоск промо+чек). Заполняется в MoveToSecondScreenFullscreen.
        /// Рекомендации сюда НЕ показываем — только на экран фармацевта.
        /// </summary>
        internal System.Windows.Forms.Screen? CustomerScreen { get; set; }

        /// <summary>
        /// Экран ФАРМАЦЕВТА = монитор, который НЕ занят клиентским киоском. Если монитор один
        /// (демо в VM) — он же; в реальной кассе с 2 мониторами popup уйдёт на экран кассира,
        /// а клиент его (и бонус!) не увидит.
        /// </summary>
        private System.Windows.Forms.Screen PharmacistScreen()
        {
            var screens = System.Windows.Forms.Screen.AllScreens;
            // Standard-N runs on the primary display in production. Prefer it explicitly instead
            // of relying on Screen.AllScreens ordering, which is not guaranteed by Windows.
            var pharmacist = screens.FirstOrDefault(s =>
                s.Primary && (CustomerScreen == null || !s.Bounds.Equals(CustomerScreen.Bounds)));
            pharmacist ??= screens.FirstOrDefault(s =>
                CustomerScreen == null || !s.Bounds.Equals(CustomerScreen.Bounds));
            return pharmacist ?? System.Windows.Forms.Screen.PrimaryScreen!;
        }

        /// <summary>Вызывается из конструктора MainWindow. Поднимает клиент, если интеграция включена.</summary>
        private void InitPosm()
        {
            try
            {
                _posmConfig = EpharmConfig.Load();
                ConfigureLogPath(_posmConfig.AppLogPath);
                _standardNDb = new StandardNDbLookup(_posmConfig, Log);
                if (_posmConfig.Enabled)
                {
                    _epharm = new EpharmApiClient(_posmConfig, Log);
                    // Источник №1 сверки: гарантированная доставка чеков/результатов через outbox.
                    _outbox = new OfflineOutbox(_posmConfig.OutboxDbPath);
                    _saleReporter = new SaleReporter(_posmConfig, _outbox);
                    if (_posmConfig.ReceiptCaptureEnabled)
                    {
                        try
                        {
                            var fiscalSource = new FiscalReceiptInboxSource(
                                _posmConfig.FiscalReceiptInboxDir,
                                _posmConfig.FiscalReceiptTrustedSources,
                                _posmConfig.FiscalReceiptMaxClockSkewSec,
                                _posmConfig.FiscalReceiptMaxArtifactMb);
                            _receiptArtifacts = new ReceiptArtifactStore(
                                _posmConfig.ReceiptCaptureDir,
                                fiscalSource,
                                Log,
                                _posmConfig.ReceiptCaptureActiveRetentionDays,
                                _posmConfig.FiscalReceiptCompletedRetentionHours);
                            var recovered = _receiptArtifacts.RecoverPending(_outbox);
                            _fiscalReceiptTimer = new System.Threading.Timer(
                                _ => PollFiscalReceiptInbox(),
                                null,
                                TimeSpan.Zero,
                                TimeSpan.FromSeconds(_posmConfig.FiscalReceiptPollSec));
                            Log($"Exact-only захват фискальных чеков включён: " +
                                $"inbox={_posmConfig.FiscalReceiptInboxDir}, store={_posmConfig.ReceiptCaptureDir}; " +
                                $"восстановлено pending={recovered}");
                        }
                        catch (Exception ex)
                        {
                            // Structured sales still reach outbox if the isolated fiscal source fails.
                            _receiptArtifacts = null;
                            Log($"Захват фискального оригинала недоступен: {ex.GetBaseException().Message}");
                        }
                    }
                    _flusher = new OutboxFlusher(
                        _outbox,
                        _epharm,
                        _posmConfig.OutboxFlushSec,
                        OnOutboxDelivered);
                    Log($"POSM включён: backend={string.Join(" -> ", _posmConfig.GetBackendBaseUris())}, аптека={_posmConfig.PharmacyId}");
                }
                else
                {
                    // Частая причина «видео/рекомендации не работают»: Enabled=false в posm.json
                    // ИЛИ пустой PharmacistId/PharmacyId. Пишем явно, чтобы было видно в логе.
                    var why = string.IsNullOrWhiteSpace(_posmConfig.PharmacyId) ? "пустой PharmacyId"
                        : "Enabled=false";
                    Log($"POSM ВЫКЛЮЧЕН ({why}) → ни рекомендаций, ни видео из админки. " +
                        "Проверь posm.json (Enabled, PharmacyId). Фармацевт берётся из лога кассы (kassir=).");
                }
            }
            catch (Exception ex)
            {
                Log($"POSM init error: {ex.Message}");
            }
        }

        /// <summary>
        /// Вызывается только после скана/добавления товара в Стандарт-Н. Debounce схлопывает
        /// быстрые последовательные сканы в один актуальный запрос по текущей корзине.
        /// Удаления/скидки/локальная очистка чека сюда не попадают и backend не нагружают.
        /// </summary>
        private void OnProductScanned(Models.ReceiptItem scannedItem)
        {
            CaptureCurrentSellerForSession();
            SaveActiveReceiptDraft();
            if (_epharm == null || _posmConfig == null) return;

            _recoCts?.Cancel();
            _recoCts = new CancellationTokenSource();
            var token = _recoCts.Token;
            var debounceMs = _posmConfig.DebounceMs;
            var scannedSnapshot = scannedItem.Clone();
            _ = Task.Run(async () =>
            {
                try
                {
                    if (debounceMs > 0)
                        await Task.Delay(debounceMs, token).ConfigureAwait(false);
                    if (token.IsCancellationRequested) return;
                    await RunRecommendCheckAsync("scan", scannedSnapshot, token, waitForPrevious: true).ConfigureAwait(false);
                }
                catch (OperationCanceledException) { /* новый товар отменил прошлый запрос */ }
                catch (Exception ex) { Log($"POSM recommend error: {ex.Message}"); }
            }, token);
        }

        /// <summary>
        /// Локальное изменение корзины без сетевого запроса: удаление позиции, очистка чека,
        /// пересчёт скидки. Нужен только чтобы убрать "уже показано" для удалённых триггеров.
        /// </summary>
        private void OnCartChangedLocalOnly()
        {
            if (_posmConfig == null) return;
            _recoCts?.Cancel();
            if (ReceiptItems.Count == 0)
            {
                DiscardActiveReceiptDraft();
                ResetRecommendationUiState(closeWindows: true);
                StartNewCheckoutSession();
                return;
            }
            SaveActiveReceiptDraft();
            CloseStaleRecommendationWindowForCurrentCart();
            PruneShownStateForCurrentCart();
        }

        private async Task RunRecommendCheckAsync(
            string reason,
            Models.ReceiptItem? scannedItem,
            CancellationToken token,
            bool waitForPrevious = false)
        {
            if (_epharm == null || _posmConfig == null) return;
            var waitMs = Math.Max(500, _posmConfig.RecommendTimeoutMs + 500);
            var entered = waitForPrevious
                ? await _recommendGate.WaitAsync(TimeSpan.FromMilliseconds(waitMs), token).ConfigureAwait(false)
                : await _recommendGate.WaitAsync(0, token).ConfigureAwait(false);
            if (!entered)
            {
                Log($"POSM recommend skipped ({reason}): предыдущий запрос ещё выполняется");
                return;
            }

            try
            {
                List<string> requestLogItems = new();
                string? requestPharmacistId = null;
                string? requestPharmacistName = null;
                var request = await Dispatcher.InvokeAsync(() =>
                {
                    var req = _session.BuildRequest(_posmConfig, ReceiptItems, scannedItem);
                    if (req.Cart.Count == 0)
                    {
                        ResetRecommendationUiState(closeWindows: true);
                        return null;
                    }
                    PruneShownStateForCurrentCart();
                    requestPharmacistId = req.PharmacistId;
                    requestPharmacistName = req.PharmacistName;
                    requestLogItems = req.Cart
                        .Select(i => $"sentSku={i.Sku ?? "—"}, ean={i.Barcode ?? "—"}, " +
                                     $"name={i.Name ?? "—"}, qty={i.Qty:0.###}")
                        .ToList();
                    return req;
                });
                if (request == null) return;

                Log($"POSM recommend request ({reason}): pharmacistId={requestPharmacistId ?? "—"}, " +
                    $"pharmacistName={requestPharmacistName ?? "—"}; " +
                    string.Join(" | ", requestLogItems));

                var resp = await _epharm.RecommendAsync(request, token).ConfigureAwait(false);
                if (resp == null) return;
                var recommendations = resp.Recommendations ?? new List<Recommendation>();
                var responseConflicts = resp.Conflicts ?? new List<Conflict>();
                Log($"POSM recommend response ({reason}): recs={recommendations.Count}, conflicts={responseConflicts.Count}");
                foreach (var rec in recommendations.Take(2))
                {
                    Log($"POSM recommend candidate: rule={rec.RuleId}, event={rec.EventId}, kind={rec.Kind}, " +
                        $"triggerProductId={rec.TriggerSku ?? "—"}, triggerIpartId={rec.TriggerIpartId ?? "—"}, " +
                        $"triggerEAN={rec.TriggerBarcode ?? "—"}, triggerName={rec.TriggerName ?? "—"}, " +
                        $"recommend={rec.RecommendName}");
                }

                // OnProductScanned/OnCartChangedLocalOnly cancel this token whenever the cart changes.
                // Therefore a live response already belongs to the current cart snapshot and is authoritative.
                // Re-matching it locally used to drop valid recommendations because backend triggerSku is our
                // catalog productId while Standard-N exposes iPartID. Stale responses remain protected by CTS.
                token.ThrowIfCancellationRequested();

                var conflicts = await Dispatcher.InvokeAsync(() => responseConflicts
                    .Where(c => c != null && !ConflictShown(c))
                    .ToList());
                var recs = await Dispatcher.InvokeAsync(() => recommendations
                    .Take(2)
                    .Where(r => !RecommendationShown(r))
                    .ToList());

                if (conflicts.Count == 0 && recs.Count == 0)
                {
                    Log("POSM recommend: показывать нечего (нет новых рекомендаций/конфликтов)");
                    return;
                }

                await Dispatcher.InvokeAsync(() =>
                {
                    if (token.IsCancellationRequested) return;
                    if (conflicts.Count > 0) ShowConflicts(conflicts);
                    if (recs.Count > 0) ShowRecommendations(recs, scannedItem);
                });
            }
            finally
            {
                _recommendGate.Release();
            }
        }

        /// <summary>Открыть форму карты клиента (CDP §5.6) — на экране фармацевта.</summary>
        private void ShowCdpForm()
        {
            var form = new CdpForm(_epharm, _posmConfig);
            // окно CDP — для фармацевта; центрируем на его мониторе
            var s = PharmacistScreen().Bounds;
            form.Left = s.Left + (s.Width - form.Width) / 2;
            form.Top = s.Top + 120;
            form.Show();
        }

        /// <summary>
        /// Демо-данные рекомендации (Аквалор → Аквамарис) — как в ТЗ Figure 11 и live-демо r_001.
        /// Используется и для клавиши D, и для режима превью экрана фармацевта.
        /// </summary>
        private static Recommendation DemoRecommendation() => new Recommendation
        {
            EventId = "demo",
            Kind = "substitution",
            TriggerName = "Аквалор Норм спрей",
            TriggerVolume = "150 мл",
            TriggerPrice = 2390,
            RecommendSku = "p_aquamaris_norm",
            RecommendName = "Аквамарис Норм спрей",
            RecommendVendor = "Jadran Galenski",
            RecommendVolume = "150 мл",
            RecommendStock = "в наличии 14 уп.",
            RecommendPrice = 2890,
            PartnerLabel = "ПАРТНЁР EPHARM",
            Bonus = 520,
            Script = "Если ринит у ребёнка — Аквамарис мягче распыляет и подходит с 1 года. Состав почти идентичен.",
            Comparison = new List<ComparisonRow>
            {
                new ComparisonRow { Label = "Состав", TriggerValue = "изотонический раствор", RecommendValue = "✓ изотоническая вода Адриатики", RecommendHighlight = true },
                new ComparisonRow { Label = "Объём", TriggerValue = "150 мл", RecommendValue = "150 мл", RecommendHighlight = false },
                new ComparisonRow { Label = "Распылитель", TriggerValue = "стандартный", RecommendValue = "✓ мягкий душ — для детей с 1 года", RecommendHighlight = true },
                new ComparisonRow { Label = "Срок годн.", TriggerValue = "18 мес", RecommendValue = "✓ 36 мес после вскрытия", RecommendHighlight = true },
            },
            GoalText = "цель «7/10 замен Аквамарис в мае»",
            GoalBonus = 2000,
        };

        /// <summary>Демо cross-sell (допродажа) — к спрею от насморка предлагаем аспиратор.</summary>
        private static Recommendation DemoCrossSell() => new Recommendation
        {
            EventId = "demo-cross",
            Kind = "crosssell",
            TriggerName = "Аквамарис Норм спрей",
            TriggerVolume = "150 мл",
            RecommendSku = "p_humer_aspirator",
            RecommendName = "Хьюмер аспиратор назальный",
            RecommendPrice = 3200,
            PartnerLabel = "ПАРТНЁР EPHARM",
            Bonus = 300,
            Script = "К спрею предложите аспиратор — маме удобнее очищать нос ребёнку, нос дышит сразу.",
            Advantages = new List<string>
            {
                "Сочетается с солевым спреем",
                "Многоразовый, мягкие насадки",
                "Для детей с рождения",
            },
        };

        /// <summary>Полный демо-набор: замена + допродажа (показывает табы в карточке).</summary>
        private static List<Recommendation> DemoRecommendations() => new List<Recommendation>
        {
            DemoRecommendation(),
            DemoCrossSell(),
        };

        /// <summary>
        /// Демо-показ popup рекомендации (клавиша D в киоске) — увидеть UI наглядно
        /// без кассы Стандарт-Н и без backend. Outcome уходит в никуда (fail-safe).
        /// </summary>
        public void ShowDemoRecommendation() => ShowRecommendations(DemoRecommendations());

        /// <summary>
        /// Режим «только экран фармацевта» (EPHARM_PHARMACIST_PREVIEW=true) — для скринов поверх
        /// Стандарт-Н. Киоск не показываем (прячем окно); выводим одну карточку (frameless, поверх
        /// всех окон) без авто-закрытия (1ч — успеть заскринить). Закрытие/Esc → выход.
        /// </summary>
        private void EnterPharmacistPreview()
        {
            Log("Режим превью экрана фармацевта (без киоска) — карточка поверх Стандарт-Н.");
            Hide(); // прячем киоск-окно: на экране остаётся только карточка поверх кассы
            try
            {
                var win = new RecommendationWindow(DemoRecommendations(), autoCloseSec: 0, targetScreen: null);
                // В превью выходим, только когда окно полностью закрыто (по всем рекомендациям
                // принято решение, либо ✕). Приём одной реко НЕ закрывает приложение — можно
                // принять и замену, и кросс-селл по очереди.
                win.Closed += (_, _) => System.Windows.Application.Current.Shutdown();
                win.Show();
            }
            catch (Exception ex)
            {
                Log($"preview error: {ex.Message}");
                System.Windows.Application.Current.Shutdown();
            }
        }

        /// <summary>Показать одну рекомендацию (обёртка над списком).</summary>
        private void ShowRecommendation(Recommendation rec) =>
            ShowRecommendations(new List<Recommendation> { rec });

        /// <summary>
        /// Показать рекомендации (замена и/или cross-sell) одной карточкой с табами. Outcome
        /// фиксируется по ТЕКУЩЕЙ показанной (win.Current) — фармацевт мог переключить таб.
        /// popup ВСЕГДА на экран фармацевта (не клиентский киоск). Бонус — не для клиента.
        /// </summary>
        private void ShowRecommendations(
            List<Recommendation> recs,
            Models.ReceiptItem? scannedItem = null)
        {
            if (recs == null || recs.Count == 0) return;
            _recoWindow?.Close();

            // autoCloseSec=0 → попап НЕ закрывается по таймауту, висит до ✕. Карточка
            // информационная: F9/Esc (принять/пропустить) убраны — факт продажи определяется
            // по реальному чеку (источник №1 сверки), а не нажатием в окне. Поэтому ни
            // ApplyAcceptedToCheque, ни outcome-репорт отсюда не нужны.
            var target = PharmacistScreen();
            var win = new RecommendationWindow(recs, 0, target);
            var loaded = false;
            win.Loaded += (_, _) =>
            {
                if (loaded) return;
                loaded = true;
                foreach (var r in recs)
                {
                    // Mark shown only after WPF confirms that the window is actually loaded.
                    // A construction/Show failure must remain retryable and must not pollute analytics.
                    MarkRecommendationShown(r, scannedItem);
                    var kind = r.IsSubstitution ? "замена" : "кросс-селл";
                    Log($"POSM popup показан: {kind} → {r.RecommendName} " +
                        $"(EAN {r.RecommendBarcode ?? "—"}), бонус {r.Bonus} ₸");
                }
                Log($"POSM popup monitor={target.DeviceName}, primary={target.Primary}, " +
                    $"bounds={target.Bounds}, window=({win.Left:0},{win.Top:0},{win.ActualWidth:0}x{win.ActualHeight:0})");
            };
            win.Closed += (_, _) => { if (ReferenceEquals(_recoWindow, win)) _recoWindow = null; };
            _recoWindow = win;
            try
            {
                win.Show();
            }
            catch (Exception ex)
            {
                if (ReferenceEquals(_recoWindow, win)) _recoWindow = null;
                Log($"POSM popup не удалось открыть: {ex.Message}; следующий скан повторит показ");
            }
        }

        /// <summary>Подпись конфликта для дедупликации в рамках чека (kind + триггер + правила).</summary>
        private static string ConflictSig(Conflict c) =>
            (c.Kind ?? "") + "|" + (c.TriggerName ?? "") + "|" + string.Join(",", c.RuleIds ?? new List<string>());

        /// <summary>Уже показывали этот конфликт в текущем чеке? (баннер не всплывает повторно).</summary>
        private bool ConflictShown(Conflict c) => _shownConflictSigs.ContainsKey(ConflictSig(c));

        /// <summary>
        /// Показать баннер конфликта(ов) (T2): backend нашёл правило, но применить нельзя — выводим
        /// причину фармацевту. Не блокирует кассу, авто-закрывается; на экране ФАРМАЦЕВТА (не клиент).
        /// </summary>
        private void ShowConflicts(List<Conflict> conflicts)
        {
            if (conflicts == null || conflicts.Count == 0) return;
            foreach (var c in conflicts) _shownConflictSigs[ConflictSig(c)] = c.TriggerName;
            _conflictWindow?.Close();

            var win = new ConflictNotificationWindow(conflicts, _posmConfig?.PopupAutoCloseSec ?? 8, PharmacistScreen());
            win.Closed += (_, _) => { if (ReferenceEquals(_conflictWindow, win)) _conflictWindow = null; };
            _conflictWindow = win;
            win.Show();
        }

        private bool RecommendationShown(Recommendation rec) =>
            !string.IsNullOrWhiteSpace(rec.EventId) && _shownRecommendations.ContainsKey(rec.EventId);

        private void MarkRecommendationShown(Recommendation rec, Models.ReceiptItem? scannedItem)
        {
            if (string.IsNullOrWhiteSpace(rec.EventId)) return;
            _shownRecommendations[rec.EventId] = new ShownRecommendationState
            {
                EventId = rec.EventId,
                LocalTrigger = RecommendationTriggerBinding.FromReceiptItem(scannedItem),
                TriggerSku = rec.TriggerSku,
                TriggerIpartId = rec.TriggerIpartId,
                TriggerBarcode = rec.TriggerBarcode,
                TriggerName = rec.TriggerName,
            };
            EnqueueShownPing(rec.EventId);
        }

        /// <summary>
        /// Пинг «рекомендация показана» (V032) в outbox — гарантированная доставка факта и времени
        /// показа фармацевту (для аналитики «время до продажи»). id = "shown_"+eventId → один пинг на
        /// событие (INSERT OR IGNORE идемпотентно). Демо-карточки (клавиша D / превью) не шлём — их
        /// eventId нет в БД. Любая ошибка проглатывается: касса не должна тормозить из-за телеметрии.
        /// </summary>
        private void EnqueueShownPing(string eventId)
        {
            if (_outbox == null || eventId == "demo" || eventId == "demo-cross") return;
            try
            {
                var payload = new OutboxShownPayload { EventId = eventId, ShownAt = DateTimeOffset.UtcNow };
                _outbox.Enqueue("shown_" + eventId, "shown", JsonSerializer.Serialize(payload, EpharmJson.Options));
            }
            catch (Exception ex)
            {
                Log($"shown-ping enqueue error: {ex.Message}");
            }
        }

        private bool RecommendationAppliesToCurrentCart(Recommendation rec)
        {
            if (rec == null) return false;

            // Старый/неполный backend может не вернуть trigger-поля. В этом случае не скрываем
            // рекомендацию: лучше показать её, чем потерять валидную акцию из-за неполного DTO.
            if (string.IsNullOrWhiteSpace(rec.TriggerSku) &&
                string.IsNullOrWhiteSpace(rec.TriggerIpartId) &&
                string.IsNullOrWhiteSpace(rec.TriggerBarcode) &&
                string.IsNullOrWhiteSpace(rec.TriggerName))
                return true;

            return CartContainsTrigger(rec.TriggerSku, rec.TriggerBarcode, rec.TriggerName, rec.TriggerIpartId);
        }

        private void CloseStaleRecommendationWindowForCurrentCart()
        {
            var win = _recoWindow;
            if (win == null) return;

            var stale = win.Recommendations.Any(r => !ShownRecommendationAppliesToCurrentCart(r));
            if (!stale) return;

            Log("POSM popup закрыт: товар-триггер удалён из чека");
            win.Close();
            if (ReferenceEquals(_recoWindow, win)) _recoWindow = null;
        }

        private bool ShownRecommendationAppliesToCurrentCart(Recommendation rec)
        {
            if (!string.IsNullOrWhiteSpace(rec.EventId) &&
                _shownRecommendations.TryGetValue(rec.EventId, out var shown) &&
                shown.LocalTrigger != null)
                return shown.LocalTrigger.IsPresent(ReceiptItems);

            return RecommendationAppliesToCurrentCart(rec);
        }

        private void PruneShownStateForCurrentCart()
        {
            if (_shownRecommendations.Count > 0)
            {
                foreach (var kv in _shownRecommendations.ToList())
                {
                    var applies = kv.Value.LocalTrigger?.IsPresent(ReceiptItems) ??
                        CartContainsTrigger(
                            kv.Value.TriggerSku,
                            kv.Value.TriggerBarcode,
                            kv.Value.TriggerName,
                            kv.Value.TriggerIpartId);
                    if (!applies)
                        _shownRecommendations.Remove(kv.Key);
                }
            }

            if (_shownConflictSigs.Count > 0)
            {
                foreach (var kv in _shownConflictSigs.ToList())
                {
                    if (!CartContainsTrigger(null, null, kv.Value))
                        _shownConflictSigs.Remove(kv.Key);
                }
            }
        }

        private bool CartContainsTrigger(string? sku, string? barcode, string? name, string? ipartId = null)
        {
            return ReceiptItems.Where(IsRealCashItem).Any(item =>
                (!string.IsNullOrWhiteSpace(sku) &&
                 string.Equals(item.PartId.ToString(), sku.Trim(), StringComparison.OrdinalIgnoreCase))
                || (!string.IsNullOrWhiteSpace(ipartId) &&
                 string.Equals(item.PartId.ToString(), ipartId.Trim(), StringComparison.OrdinalIgnoreCase))
                || (!string.IsNullOrWhiteSpace(barcode) &&
                 string.Equals(item.Barcode?.Trim(), barcode.Trim(), StringComparison.OrdinalIgnoreCase))
                || NamesLikelyMatch(item.Name, name));
        }

        private static bool IsRealCashItem(Models.ReceiptItem item) => item.PartId > 0;

        private static bool NamesLikelyMatch(string? a, string? b)
        {
            if (string.IsNullOrWhiteSpace(a) || string.IsNullOrWhiteSpace(b)) return false;
            var x = NormalizeName(a);
            var y = NormalizeName(b);
            return x == y || x.Contains(y, StringComparison.OrdinalIgnoreCase) || y.Contains(x, StringComparison.OrdinalIgnoreCase);
        }

        private static string NormalizeName(string raw)
        {
            var chars = raw.ToLowerInvariant()
                .Select(ch => char.IsLetterOrDigit(ch) || char.IsWhiteSpace(ch) ? ch : ' ')
                .ToArray();
            return string.Join(" ", new string(chars).Split(' ', StringSplitOptions.RemoveEmptyEntries));
        }

        private void ResetRecommendationUiState(bool closeWindows)
        {
            _recoCts?.Cancel();
            if (closeWindows)
            {
                _recoWindow?.Close();
                _recoWindow = null;
                _conflictWindow?.Close();
                _conflictWindow = null;
            }
            _shownRecommendations.Clear();
            _shownConflictSigs.Clear();
        }


        /// <summary>
        /// Вызывается при печати/завершении чека ДО очистки позиций. Фиксирует продажу (источник №1),
        /// закрывает popup, открывает новую сессию.
        /// </summary>
        private bool OnReceiptFinalized(string captureSource, long? sourceDocumentId = null)
        {
            // If the first lookup failed, SellerCaptured remains false. Retry immediately before
            // queuing the completed receipt instead of permanently sending an empty seller.
            if (!_session.SellerCaptured)
            {
                RefreshCurrentPharmacistFromStandardNDb();
                CaptureCurrentSellerForSession();
            }
            var sale = _saleReporter?.Build(
                _session,
                ReceiptItems,
                sourceDocumentId,
                captureSource,
                DateTimeOffset.UtcNow);
            if (sale != null)
            {
                FiscalReceiptCaptureResult? fiscalCapture = null;
                try
                {
                    fiscalCapture = _receiptArtifacts?.Complete(sale);
                }
                catch (Exception ex)
                {
                    // Fiscal evidence is isolated from the cashier and structured sale reporting.
                    Log($"Фискальный оригинал {sale.SaleId} не поставлен на захват: {ex.GetBaseException().Message}");
                }

                _saleReporter?.Enqueue(sale);
                var fiscalStatus = _receiptArtifacts == null
                    ? "захват недоступен"
                    : fiscalCapture?.Status switch
                    {
                        FiscalReceiptCaptureStatus.Stored => "оригинал сохранён",
                        FiscalReceiptCaptureStatus.Rejected => "источник отклонён",
                        _ => "ожидается оригинал ККМ/OFD",
                    };
                Log($"Чек поставлен в гарантированную очередь: id={sale.SaleId}, " +
                    $"doc={sourceDocumentId?.ToString() ?? "—"}, items={sale.Items.Count}, " +
                    $"total={sale.TotalAmount}, fiscal={fiscalStatus}");
            }
            else
            {
                Log($"Завершение чека пропущено: нет реальных позиций Standard-N (source={captureSource})");
            }

            _activeReceiptDraftId = null;
            ResetRecommendationUiState(closeWindows: true);
            StartNewCheckoutSession();
            return sale != null;
        }

        private void SaveActiveReceiptDraft()
        {
            if (_receiptArtifacts == null || _saleReporter == null || ReceiptItems.Count == 0) return;
            try
            {
                CaptureCurrentSellerForSession();
                var draft = _saleReporter.Build(
                    _session,
                    ReceiptItems,
                    _standardNDocumentId,
                    _standardNDocumentId.HasValue ? "standardn-active" : "cash-log-active",
                    DateTimeOffset.UtcNow);
                if (draft == null) return;
                _receiptArtifacts.SaveDraft(draft);
                if (!string.IsNullOrWhiteSpace(_activeReceiptDraftId) &&
                    !string.Equals(_activeReceiptDraftId, draft.SaleId, StringComparison.Ordinal))
                {
                    // The first cash-log event can precede discovery of DOCS.ID. Once Firebird
                    // supplies the stable document id, remove the obsolete session-keyed draft.
                    _receiptArtifacts.DiscardDraft(_activeReceiptDraftId);
                }
                _activeReceiptDraftId = draft.SaleId;
            }
            catch (Exception ex)
            {
                Log($"Черновик активного чека временно не сохранён: {ex.GetBaseException().Message}");
            }
        }

        private void DiscardActiveReceiptDraft()
        {
            if (_receiptArtifacts == null || string.IsNullOrWhiteSpace(_activeReceiptDraftId)) return;
            _receiptArtifacts.DiscardDraft(_activeReceiptDraftId);
            _activeReceiptDraftId = null;
        }

        private void OnOutboxDelivered(OutboxItem item)
        {
            try
            {
                if (string.Equals(item.Kind, "sale", StringComparison.OrdinalIgnoreCase))
                {
                    _receiptArtifacts?.MarkSaleDelivered(item.Id);
                    return;
                }
                if (string.Equals(item.Kind, "fiscal-sale", StringComparison.OrdinalIgnoreCase))
                {
                    var sale = JsonSerializer.Deserialize<SaleReport>(item.Payload, EpharmJson.Options);
                    if (sale != null && !string.IsNullOrWhiteSpace(sale.ArtifactSha256))
                        _receiptArtifacts?.MarkFiscalMetadataDelivered(sale.SaleId, sale.ArtifactSha256);
                }
            }
            catch (Exception ex)
            {
                Log($"ACK outbox {item.Id} получен, но marker локального retention не записан: " +
                    ex.GetBaseException().Message);
            }
        }

        private void PollFiscalReceiptInbox()
        {
            if (Interlocked.Exchange(ref _fiscalReceiptPollBusy, 1) != 0) return;
            try
            {
                var store = _receiptArtifacts;
                var outbox = _outbox;
                if (store == null || outbox == null) return;
                var result = store.RefreshFiscalArtifacts(outbox);
                if (result.Captured > 0 || result.Cleaned > 0)
                {
                    Log($"Фискальные артефакты: сохранено={result.Captured}, " +
                        $"удалено по retention={result.Cleaned}");
                }
            }
            catch (Exception ex)
            {
                Log($"Фоновая проверка фискального inbox завершилась ошибкой: {ex.GetBaseException().Message}");
            }
            finally
            {
                Interlocked.Exchange(ref _fiscalReceiptPollBusy, 0);
            }
        }

        private void CaptureCurrentSellerForSession()
        {
            if (_session.SellerCaptured) return;
            _session.CaptureSeller(
                _currentPharmacistId,
                _currentPharmacistName,
                _currentStandardNSessionId);
            if (!_session.SellerCaptured) return;

            Log($"Продавец чека зафиксирован: id={(_session.PharmacistId.Length == 0 ? "—" : _session.PharmacistId)}, " +
                $"name={(_session.PharmacistName.Length == 0 ? "—" : _session.PharmacistName)}, " +
                $"standardNSession={_session.StandardNSessionId?.ToString() ?? "—"}");
        }

        private void StartNewCheckoutSession()
        {
            _session = new CheckoutSession();
        }
    }
}
