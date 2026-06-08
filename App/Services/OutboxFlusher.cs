using System;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Фоновый досыл очереди outbox: каждые N секунд берёт готовые записи и отправляет на backend.
    /// Успех → удаляет; ошибка → reschedule с экспоненциальным backoff. Так sale/outcome не теряются
    /// при пропадании интернета и доезжают, когда связь восстановится.
    /// </summary>
    public sealed class OutboxFlusher : IDisposable
    {
        private readonly OfflineOutbox _outbox;
        private readonly EpharmApiClient _api;
        private readonly Timer _timer;
        private int _busy;

        public OutboxFlusher(OfflineOutbox outbox, EpharmApiClient api, int periodSec = 5)
        {
            _outbox = outbox;
            _api = api;
            var period = TimeSpan.FromSeconds(periodSec);
            _timer = new Timer(_ => _ = FlushAsync(), null, period, period);
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
                    default:
                        return true; // неизвестный тип — выкидываем, чтобы не копился
                }
            }
            catch
            {
                return false;
            }
        }

        public void Dispose() => _timer.Dispose();
    }
}
