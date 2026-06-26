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
        private CheckoutSession _session = new();

        // Текущий фармацевт/кассир — берётся из лога кассы (токен kassir=/cashier=), а НЕ из конфига:
        // фармацевты работают посменно, поэтому id динамический. Пусто, пока касса не прислала кассира.
        private string _currentPharmacistId = "";

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
            public string? TriggerSku { get; init; }
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
            var pharmacist = screens.FirstOrDefault(s => CustomerScreen == null || !s.Bounds.Equals(CustomerScreen.Bounds));
            return pharmacist ?? System.Windows.Forms.Screen.PrimaryScreen!;
        }

        /// <summary>Вызывается из конструктора MainWindow. Поднимает клиент, если интеграция включена.</summary>
        private void InitPosm()
        {
            try
            {
                _posmConfig = EpharmConfig.Load();
                if (_posmConfig.Enabled)
                {
                    _epharm = new EpharmApiClient(_posmConfig, Log);
                    // Источник №1 сверки: гарантированная доставка чеков/результатов через outbox.
                    _outbox = new OfflineOutbox(_posmConfig.OutboxDbPath);
                    _saleReporter = new SaleReporter(_posmConfig, _outbox);
                    _flusher = new OutboxFlusher(_outbox, _epharm, _posmConfig.OutboxFlushSec);
                    Log($"POSM включён: backend={_posmConfig.BackendBaseUrl}, аптека={_posmConfig.PharmacyId}");
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
                ResetRecommendationUiState(closeWindows: true);
                _session = new CheckoutSession();
                return;
            }
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
                var request = await Dispatcher.InvokeAsync(() =>
                {
                    var req = _session.BuildRequest(_posmConfig, ReceiptItems, scannedItem, _currentPharmacistId);
                    if (req.Cart.Count == 0)
                    {
                        ResetRecommendationUiState(closeWindows: true);
                        return null;
                    }
                    PruneShownStateForCurrentCart();
                    return req;
                });
                if (request == null) return;

                Log($"POSM recommend request ({reason}): " + string.Join(" | ", request.Cart.Select(i =>
                    $"sku={i.Sku ?? "—"}, ean={i.Barcode ?? "—"}, name={i.Name ?? "—"}, qty={i.Qty:0.###}")));

                var resp = await _epharm.RecommendAsync(request, token).ConfigureAwait(false);
                if (resp == null) return;
                var recommendations = resp.Recommendations ?? new List<Recommendation>();
                var responseConflicts = resp.Conflicts ?? new List<Conflict>();
                Log($"POSM recommend response ({reason}): recs={recommendations.Count}, conflicts={responseConflicts.Count}");

                var conflicts = await Dispatcher.InvokeAsync(() => responseConflicts
                    .Where(c => c != null && !ConflictShown(c))
                    .ToList());
                var recs = await Dispatcher.InvokeAsync(() => recommendations
                    .Take(2)
                    .Where(RecommendationAppliesToCurrentCart)
                    .Where(r => !RecommendationShown(r))
                    .ToList());

                if (conflicts.Count == 0 && recs.Count == 0)
                {
                    Log("POSM recommend: показывать нечего (нет новых рекомендаций/конфликтов)");
                    return;
                }

                await Dispatcher.InvokeAsync(() =>
                {
                    if (conflicts.Count > 0) ShowConflicts(conflicts);
                    if (recs.Count > 0) ShowRecommendations(recs);
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
        private void ShowRecommendations(List<Recommendation> recs)
        {
            if (recs == null || recs.Count == 0) return;
            foreach (var r in recs)
            {
                MarkRecommendationShown(r);
                var kind = r.IsSubstitution ? "замена" : "кросс-селл";
                Log($"POSM popup: {kind} → {r.RecommendName} (EAN {r.RecommendBarcode ?? "—"}), бонус {r.Bonus} ₸");
            }
            _recoWindow?.Close();

            // autoCloseSec=0 → попап НЕ закрывается по таймауту, висит до ✕. Карточка
            // информационная: F9/Esc (принять/пропустить) убраны — факт продажи определяется
            // по реальному чеку (источник №1 сверки), а не нажатием в окне. Поэтому ни
            // ApplyAcceptedToCheque, ни outcome-репорт отсюда не нужны.
            var win = new RecommendationWindow(recs, 0, PharmacistScreen());
            win.Closed += (_, _) => { if (ReferenceEquals(_recoWindow, win)) _recoWindow = null; };
            _recoWindow = win;
            win.Show();
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

        private void MarkRecommendationShown(Recommendation rec)
        {
            if (string.IsNullOrWhiteSpace(rec.EventId)) return;
            _shownRecommendations[rec.EventId] = new ShownRecommendationState
            {
                EventId = rec.EventId,
                TriggerSku = rec.TriggerSku,
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
                string.IsNullOrWhiteSpace(rec.TriggerBarcode) &&
                string.IsNullOrWhiteSpace(rec.TriggerName))
                return true;

            return CartContainsTrigger(rec.TriggerSku, rec.TriggerBarcode, rec.TriggerName);
        }

        private void CloseStaleRecommendationWindowForCurrentCart()
        {
            var win = _recoWindow;
            if (win == null) return;

            var stale = win.Recommendations.Any(r => !RecommendationAppliesToCurrentCart(r));
            if (!stale) return;

            Log("POSM popup закрыт: товар-триггер удалён из чека");
            win.Close();
            if (ReferenceEquals(_recoWindow, win)) _recoWindow = null;
        }

        private void PruneShownStateForCurrentCart()
        {
            if (_shownRecommendations.Count > 0)
            {
                foreach (var kv in _shownRecommendations.ToList())
                {
                    if (!CartContainsTrigger(kv.Value.TriggerSku, kv.Value.TriggerBarcode, kv.Value.TriggerName))
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

        private bool CartContainsTrigger(string? sku, string? barcode, string? name)
        {
            return ReceiptItems.Where(IsRealCashItem).Any(item =>
                (!string.IsNullOrWhiteSpace(sku) &&
                 string.Equals(item.PartId.ToString(), sku.Trim(), StringComparison.OrdinalIgnoreCase))
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
        private void OnReceiptFinalized()
        {
            _saleReporter?.Report(_session, ReceiptItems, _currentPharmacistId); // позиции ещё в чеке
            ResetRecommendationUiState(closeWindows: true);
            _session = new CheckoutSession();
        }
    }
}
