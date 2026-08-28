using System;
using System.Net.NetworkInformation;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Фоновый досыл очереди outbox — гарантированная доставка «при любых условиях». Три триггера
    /// досыла (используем ВСЕ доступные методы), чтобы данные доезжали максимально быстро и надёжно:
    ///   1) опрос по таймеру каждые N секунд (базовый ритм);
    ///   2) ранний flush почти сразу после старта (через 2с) — добивает backlog, накопленный за оффлайн
    ///      или после краша/перезапуска кассы;
    ///   3) событие восстановления сети (NetworkAddressChanged / NetworkAvailabilityChanged) —
    ///      мгновенный flush, как только интернет вернулся (не ждём следующего тика таймера).
    /// Успех → удаляет запись; ошибка → reschedule с экспоненциальным backoff. Так sale/outcome не
    /// теряются при пропадании интернета и доезжают, когда связь восстановится. Фон не падает.
    /// </summary>
    public sealed class OutboxFlusher : IDisposable
    {
        private readonly OfflineOutbox _outbox;
        private readonly EpharmApiClient _api;
        private readonly Timer _timer;
        private readonly NetworkAddressChangedEventHandler _onNetAddr;
        private readonly NetworkAvailabilityChangedEventHandler _onNetAvail;
        private readonly Action<OutboxItem>? _onDelivered;
        private int _busy;
        private int _disposed;

        public OutboxFlusher(
            OfflineOutbox outbox,
            EpharmApiClient api,
            int periodSec = 5,
            Action<OutboxItem>? onDelivered = null)
        {
            _outbox = outbox;
            _api = api;
            _onDelivered = onDelivered;
            var period = TimeSpan.FromSeconds(Math.Max(1, periodSec));
            // dueTime=2с — ранний первый flush на старте (drain backlog после оффлайна/перезапуска),
            // дальше — обычный период.
            _timer = new Timer(_ => _ = FlushAsync(), null, TimeSpan.FromSeconds(2), period);

            // Событие «сеть восстановилась» → мгновенный досыл со сбросом backoff (не ждём тик таймера
            // и не ждём окна экспоненциального backoff, накопленного за офлайн).
            _onNetAddr = (_, _) => _ = FlushNowAsync();
            _onNetAvail = (_, e) => { if (e.IsAvailable) _ = FlushNowAsync(); };
            NetworkChange.NetworkAddressChanged += _onNetAddr;
            NetworkChange.NetworkAvailabilityChanged += _onNetAvail;
        }

        /// <summary>
        /// Досыл сразу после возврата сети: сбрасываем расписание (всё «к отправке сейчас») и шлём.
        /// Так sale/shown, накопленные в офлайне, доезжают мгновенно при реконнекте, а не ждут
        /// окна backoff (которое могло вырасти до 30 минут).
        /// </summary>
        public async Task FlushNowAsync()
        {
            try { _outbox.MarkAllDue(); } catch { /* фон не должен падать */ }
            await FlushAsync().ConfigureAwait(false);
        }

        public async Task FlushAsync()
        {
            if (Interlocked.Exchange(ref _busy, 1) == 1) return; // не наслаиваем циклы
            try
            {
                foreach (var item in _outbox.DequeueReady())
                {
                    var ok = await SendAsync(item).ConfigureAwait(false);
                    if (ok)
                    {
                        _outbox.Remove(item.Id);
                        try { _onDelivered?.Invoke(item); }
                        catch { /* ACK уже зафиксирован; cleanup не должен ломать flusher */ }
                    }
                    else _outbox.Reschedule(item.Id, item.Attempts);
                }
            }
            catch
            {
                // фон не должен падать
            }
            finally
            {
                Interlocked.Exchange(ref _busy, 0);
            }
        }

        private async Task<bool> SendAsync(OutboxItem item)
        {
            try
            {
                switch (item.Kind)
                {
                    case "sale":
                        var sale = JsonSerializer.Deserialize<SaleReport>(item.Payload, EpharmJson.Options);
                        return sale != null && await _api.PostSaleAsync(sale).ConfigureAwait(false);
                    case "outcome":
                        var o = JsonSerializer.Deserialize<OutboxOutcomePayload>(item.Payload, EpharmJson.Options);
                        return o != null && await _api
                            .RecordOutcomeAsync(o.EventId, new OutcomeRequest { Outcome = o.Outcome, FinalSku = o.FinalSku })
                            .ConfigureAwait(false);
                    case "shown":
                        var sh = JsonSerializer.Deserialize<OutboxShownPayload>(item.Payload, EpharmJson.Options);
                        return sh != null && await _api
                            .MarkShownAsync(sh.EventId, sh.ShownAt)
                            .ConfigureAwait(false);
                    default:
                        return true; // неизвестный тип — выкидываем, чтобы не копился
                }
            }
            catch
            {
                return false;
            }
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) == 1) return;
            NetworkChange.NetworkAddressChanged -= _onNetAddr;
            NetworkChange.NetworkAvailabilityChanged -= _onNetAvail;
            _timer.Dispose();
        }
    }
}
