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
        private int _busy;
        private int _disposed;

        public OutboxFlusher(OfflineOutbox outbox, EpharmApiClient api, int periodSec = 5)
        {
            _outbox = outbox;
            _api = api;
            var period = TimeSpan.FromSeconds(Math.Max(1, periodSec));
            // dueTime=2с — ранний первый flush на старте (drain backlog после оффлайна/перезапуска),
            // дальше — обычный период.
            _timer = new Timer(_ => _ = FlushAsync(), null, TimeSpan.FromSeconds(2), period);

            // Событие «сеть восстановилась» → мгновенный досыл (не ждём тик таймера).
            _onNetAddr = (_, _) => _ = FlushAsync();
            _onNetAvail = (_, e) => { if (e.IsAvailable) _ = FlushAsync(); };
            NetworkChange.NetworkAddressChanged += _onNetAddr;
            NetworkChange.NetworkAvailabilityChanged += _onNetAvail;
        }

        public async Task FlushAsync()
        {
            if (Interlocked.Exchange(ref _busy, 1) == 1) return; // не наслаиваем циклы
            try
            {
                foreach (var item in _outbox.DequeueReady())
                {
                    var ok = await SendAsync(item).ConfigureAwait(false);
                    if (ok) _outbox.Remove(item.Id);
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
