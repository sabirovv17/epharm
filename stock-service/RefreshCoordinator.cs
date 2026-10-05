using System.Diagnostics;
using Epharm.StockService.Source;

namespace Epharm.StockService;

public sealed class RefreshCoordinator(
    StockRepository repository,
    IStockSource source,
    ILogger<RefreshCoordinator> logger)
{
    // A single worker owns this coordinator. Source reads never overlap; the
    // cashier database sees only one read-only query at a time.
    public async Task RunCycleAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var timer = Stopwatch.StartNew();
        var runId = repository.StartRun(0);
        var interrupted = false;
        try
        {
            var pharmacies = await Task.Run(source.ReadPharmacies, cancellationToken);
            if (pharmacies.Count == 0)
                throw new InvalidDataException("Standard-N returned no pharmacy profiles");
            repository.UpsertPharmacies(pharmacies);
            var ids = repository.ListActiveProfileIds();
            repository.SetRunTotal(runId, ids.Count);
            logger.LogInformation("Stock collection started: run={RunId}, profiles={Count}", runId, ids.Count);
            foreach (var id in ids)
            {
                if (cancellationToken.IsCancellationRequested)
                {
                    interrupted = true;
                    break;
                }
                try
                {
                    var rows = await Task.Run(() => source.ReadStock(id), cancellationToken);
                    repository.ReplaceSnapshot(id, rows);
                    repository.RecordRunResult(runId, succeeded: true);
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    interrupted = true;
                    break;
                }
                catch (Exception exception)
                {
                    repository.MarkError(id, exception.GetType().Name);
                    repository.RecordRunResult(runId, succeeded: false);
                    logger.LogWarning(exception, "Stock collection failed: run={RunId}, profile={ProfileId}", runId, id);
                }
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            interrupted = true;
        }
        finally
        {
            repository.CompleteRun(runId, interrupted);
            var status = repository.GetCollectionStatus();
            logger.LogInformation(
                "Stock collection finished: run={RunId}, status={Status}, succeeded={Succeeded}, failed={Failed}, elapsedMs={ElapsedMs}",
                runId, status.RunStatus, status.RunSucceeded, status.RunFailed, timer.ElapsedMilliseconds);
        }
    }
}

public sealed class RefreshWorker(
    StockOptions options,
    StockRepository repository,
    RefreshCoordinator refresh,
    ILogger<RefreshWorker> logger) : BackgroundService
{
    public static TimeSpan DelayBeforeNextRun(
        DateTimeOffset? previousStart,
        DateTimeOffset? previousEnd,
        int intervalSeconds,
        DateTimeOffset now)
    {
        if (previousStart is null) return TimeSpan.Zero;
        var nextStart = previousStart.Value.AddSeconds(intervalSeconds);
        if (previousEnd is { } end)
        {
            var afterCooldown = end.AddSeconds(180);
            if (afterCooldown > nextStart) nextStart = afterCooldown;
        }
        var delay = nextStart - now;
        return delay > TimeSpan.Zero ? delay : TimeSpan.Zero;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            var previous = repository.GetCollectionStatus();
            var delay = DelayBeforeNextRun(
                previous.RunStartedAt, previous.RunCompletedAt,
                options.SweepIntervalSeconds, DateTimeOffset.UtcNow);
            if (delay > TimeSpan.Zero)
            {
                try { await Task.Delay(delay, stoppingToken); }
                catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            }
            try
            {
                await refresh.RunCycleAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception exception)
            {
                logger.LogError(exception, "Stock collection cycle could not start or finish");
                try { await Task.Delay(TimeSpan.FromMinutes(3), stoppingToken); }
                catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            }
        }
    }
}
