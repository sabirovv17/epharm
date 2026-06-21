using System;
using System.Collections.Generic;
using System.Linq;
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
    /// и зеркалирование чека. Вызовы-хуки (InitPosm / OnCartChanged / OnReceiptFinalized)
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
        private CancellationTokenSource? _recoCts;
        private RecommendationWindow? _recoWindow;
        private ConflictNotificationWindow? _conflictWindow;

        // Синтетический PartId для товаров, добавленных в чек ПО ПРИНЯТОЙ рекомендации (не из лога
        // кассы). Отрицательный и убывающий — чтобы не пересекаться с реальными iPartID Стандарт-Н.
        private int _acceptedPartIdSeq = -1000;

        // Рекомендации, которые уже показаны в этом чеке — чтобы не всплывать повторно
        // на каждое добавление товара (backend возвращает тот же eventId — идемпотентно).
        private readonly HashSet<string> _shownEventIds = new();

        // Конфликты, уже показанные в этом чеке (подпись = kind+триггер+ruleIds) — чтобы
        // баннер «недоступно» не всплывал повторно на каждое изменение корзины.
        private readonly HashSet<string> _shownConflictSigs = new();

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
                    var why = string.IsNullOrWhiteSpace(_posmConfig.PharmacistId) ? "пустой PharmacistId"
                        : string.IsNullOrWhiteSpace(_posmConfig.PharmacyId) ? "пустой PharmacyId"
                        : "Enabled=false";
                    Log($"POSM ВЫКЛЮЧЕН ({why}) → ни рекомендаций, ни видео из админки. " +
                        "Проверь posm.json (Enabled, PharmacistId, PharmacyId).");
                }
            }
            catch (Exception ex)
            {
                Log($"POSM init error: {ex.Message}");
            }
        }

        /// <summary>Вызывается после каждого изменения корзины. Debounce → запрос рекомендаций.</summary>
        private void OnCartChanged()
        {
            if (_epharm == null || _posmConfig == null) return;

            _recoCts?.Cancel();
            _recoCts = new CancellationTokenSource();
            var token = _recoCts.Token;
            var request = _session.BuildRequest(_posmConfig, ReceiptItems);
            if (request.Cart.Count == 0) return;

            var debounceMs = _posmConfig.DebounceMs;
            _ = Task.Run(async () =>
            {
                try
                {
                    await Task.Delay(debounceMs, token).ConfigureAwait(false);
                    if (token.IsCancellationRequested) return;

                    var resp = await _epharm.RecommendAsync(request, token).ConfigureAwait(false);
                    if (resp == null) return;

                    // Конфликты (T2): правило подошло, но применить нельзя (нет в наличии, кампания
                    // на паузе, …). Показываем баннер с причиной — НЕ блокируя рекомендации ниже.
                    // resp.Conflicts может быть null от старого backend — фильтруем безопасно.
                    var conflicts = (resp.Conflicts ?? new List<Conflict>())
                        .Where(c => c != null && !ConflictShown(c))
                        .ToList();

                    // До 2 рекомендаций (замена + cross-sell). Если ВСЕ уже показаны в этом чеке — пропускаем.
                    var recs = resp.Recommendations
                        .Take(2)
                        .Where(r => !_shownEventIds.Contains(r.EventId))
                        .ToList();

                    if (conflicts.Count == 0 && recs.Count == 0) return;

                    await Dispatcher.InvokeAsync(() =>
                    {
                        if (conflicts.Count > 0) ShowConflicts(conflicts);
                        if (recs.Count > 0) ShowRecommendations(recs);
                    });
                }
                catch (OperationCanceledException) { /* новый товар отменил прошлый запрос */ }
                catch (Exception ex) { Log($"POSM recommend error: {ex.Message}"); }
            }, token);
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
                _shownEventIds.Add(r.EventId);
                var kind = r.IsSubstitution ? "замена" : "кросс-селл";
                Log($"POSM popup: {kind} → {r.RecommendName} (EAN {r.RecommendBarcode ?? "—"}), бонус {r.Bonus} ₸");
            }
            _recoWindow?.Close();

            // autoCloseSec=0 → попап НЕ закрывается по таймауту, ждёт решения фармацевта (F9/Esc/✕).
            var win = new RecommendationWindow(recs, 0, PharmacistScreen());
            // Каждая реко решается независимо — фиксируем именно ту, по которой принято решение
            // (окно не закрывается, пока есть нерешённые: можно принять и замену, и кросс-селл).
            win.Accepted += (_, rec) =>
            {
                ApplyAcceptedToCheque(rec); // товар рекомендации появляется в чеке на экране
                _ = RespondAsync(rec, "accepted");
            };
            win.Skipped += (_, rec) => _ = RespondAsync(rec, "rejected");
            win.Closed += (_, _) => { if (ReferenceEquals(_recoWindow, win)) _recoWindow = null; };
            _recoWindow = win;
            win.Show();
        }

        /// <summary>Подпись конфликта для дедупликации в рамках чека (kind + триггер + правила).</summary>
        private static string ConflictSig(Conflict c) =>
            (c.Kind ?? "") + "|" + (c.TriggerName ?? "") + "|" + string.Join(",", c.RuleIds ?? new List<string>());

        /// <summary>Уже показывали этот конфликт в текущем чеке? (баннер не всплывает повторно).</summary>
        private bool ConflictShown(Conflict c) => _shownConflictSigs.Contains(ConflictSig(c));

        /// <summary>
        /// Показать баннер конфликта(ов) (T2): backend нашёл правило, но применить нельзя — выводим
        /// причину фармацевту. Не блокирует кассу, авто-закрывается; на экране ФАРМАЦЕВТА (не клиент).
        /// </summary>
        private void ShowConflicts(List<Conflict> conflicts)
        {
            if (conflicts == null || conflicts.Count == 0) return;
            foreach (var c in conflicts) _shownConflictSigs.Add(ConflictSig(c));
            _conflictWindow?.Close();

            var win = new ConflictNotificationWindow(conflicts, _posmConfig?.PopupAutoCloseSec ?? 8, PharmacistScreen());
            win.Closed += (_, _) => { if (ReferenceEquals(_conflictWindow, win)) _conflictWindow = null; };
            _conflictWindow = win;
            win.Show();
        }

        /// <summary>
        /// Применить принятую рекомендацию к чеку на ЭКРАНЕ (визуально):
        ///   - замена (substitution): убираем исходный товар (по имени-триггеру, best-effort)
        ///     и добавляем рекомендованный;
        ///   - кросс-селл: просто добавляем рекомендованный товар.
        /// Это зеркало для демо/наглядности — реальный чек ведёт Стандарт-Н. Вызывается из UI-потока
        /// (событие окна), поэтому ObservableCollection меняем напрямую. Новый запрос рекомендаций
        /// НЕ инициируем (OnCartChanged не зовём) — реко по этому товару уже показана.
        /// </summary>
        private void ApplyAcceptedToCheque(Recommendation rec)
        {
            if (rec == null || string.IsNullOrWhiteSpace(rec.RecommendName)) return;
            try
            {
                // Замена → убрать исходный товар из чека (имя из лога может быть короче имени из
                // каталога, поэтому матч по вхождению в любую сторону, без учёта регистра).
                if (string.Equals(rec.Kind, "substitution", StringComparison.OrdinalIgnoreCase)
                    && !string.IsNullOrWhiteSpace(rec.TriggerName))
                {
                    var tn = rec.TriggerName!.Trim();
                    var trig = ReceiptItems.FirstOrDefault(x =>
                        !string.IsNullOrWhiteSpace(x.Name) &&
                        (x.Name.Contains(tn, StringComparison.OrdinalIgnoreCase) ||
                         tn.Contains(x.Name.Trim(), StringComparison.OrdinalIgnoreCase)));
                    if (trig != null) ReceiptItems.Remove(trig);
                }

                ReceiptItems.Add(new Models.ReceiptItem
                {
                    PartId = _acceptedPartIdSeq--,        // синтетический id (не из кассы)
                    Barcode = rec.RecommendBarcode,       // EAN-13 рекомендованного товара
                    Name = rec.RecommendName,
                    Price = rec.RecommendPrice,           // int → decimal
                    Qty = 1m,
                    DiscountPercent = 0m,
                });
                RecalcTotal();
                var kind = rec.IsSubstitution ? "замена" : "кросс-селл";
                Log($"Рекомендация принята ({kind}) — товар добавлен в чек: {rec.RecommendName} " +
                    $"(EAN {rec.RecommendBarcode ?? "—"})");
            }
            catch (Exception ex)
            {
                Log($"ApplyAcceptedToCheque error: {ex.Message}");
            }
        }

        private async Task RespondAsync(Recommendation rec, string outcome)
        {
            // Окно НЕ закрываем здесь — оно живёт, пока есть нерешённые рекомендации, и само
            // обнулит _recoWindow в своём Closed. Тут только фиксируем результат на backend.
            if (_epharm == null) return;
            try
            {
                var ok = await _epharm.RecordOutcomeAsync(
                    rec.EventId,
                    new OutcomeRequest { Outcome = outcome, FinalSku = rec.RecommendSku });
                if (!ok)
                {
                    // нет связи — кладём в outbox, flusher досылет
                    EnqueueOutcome(rec, outcome);
                }
                Log($"POSM outcome {outcome} eventId={rec.EventId} → {(ok ? "ok" : "queued (outbox)")}");
            }
            catch (Exception ex)
            {
                EnqueueOutcome(rec, outcome);
                Log($"POSM outcome error → outbox: {ex.Message}");
            }
        }

        private void EnqueueOutcome(Recommendation rec, string outcome)
        {
            if (_outbox == null) return;
            var payload = System.Text.Json.JsonSerializer.Serialize(
                new OutboxOutcomePayload { EventId = rec.EventId, Outcome = outcome, FinalSku = rec.RecommendSku },
                EpharmJson.Options);
            _outbox.Enqueue($"oc_{rec.EventId}_{outcome}", "outcome", payload);
        }

        /// <summary>
        /// Вызывается при печати/завершении чека ДО очистки позиций. Фиксирует продажу (источник №1),
        /// закрывает popup, открывает новую сессию.
        /// </summary>
        private void OnReceiptFinalized()
        {
            _saleReporter?.Report(_session, ReceiptItems); // позиции ещё в чеке
            _recoCts?.Cancel();
            _recoWindow?.Close();
            _recoWindow = null;
            _conflictWindow?.Close();
            _conflictWindow = null;
            _shownEventIds.Clear();
            _shownConflictSigs.Clear();
            _session = new CheckoutSession();
        }
    }
}
