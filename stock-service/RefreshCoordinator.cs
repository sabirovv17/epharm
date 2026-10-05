using System.Collections.Concurrent;
using Epharm.StockService.Source;

namespace Epharm.StockService;

public sealed class RefreshCoordinator(
    StockOptions options,
    StockRepository repository,
    StandardNSource source,
    ILogger<RefreshCoordinator> logger)
{
    private readonly SemaphoreSlim _sourceGate = new(1, 1);
    private readonly ConcurrentDictionary<long, SemaphoreSlim> _profileGates = new();
    private DateTimeOffset _profilesUpdatedAt = DateTimeOffset.MinValue;

    public async Task SyncProfilesAsync(CancellationToken cancellationToken)
    {
        if (DateTimeOffset.UtcNow - _profilesUpdatedAt < TimeSpan.FromHours(1)) return;
        await _sourceGate.WaitAsync(cancellationToken);
        try
        {
            if (DateTimeOffset.UtcNow - _profilesUpdatedAt < TimeSpan.FromHours(1)) return;
            var pharmacies = await Task.Run(source.ReadPharmacies, cancellationToken);
            repository.UpsertPharmacies(pharmacies);
            _profilesUpdatedAt = DateTimeOffset.UtcNow;
            logger.LogInformation("Standard-N pharmacy profiles synchronized: {Count}", pharmacies.Count);
        }
        finally
        {
            _sourceGate.Release();
        }
    }

    public async Task<bool> EnsureFreshAsync(long profileId, CancellationToken cancellationToken)
    {
        var pharmacy = repository.GetPharmacy(profileId);
        if (pharmacy is null) return false;
        repository.MarkRequested(profileId);
        if (IsFresh(pharmacy)) return true;
        if (InBackoff(pharmacy)) return pharmacy.LastUpdatedAt is not null;

        var gate = _profileGates.GetOrAdd(profileId, _ => new SemaphoreSlim(1, 1));
        await gate.WaitAsync(cancellationToken);
        try
        {
            pharmacy = repository.GetPharmacy(profileId);
            if (pharmacy is null) return false;
            if (IsFresh(pharmacy)) return true;
            if (InBackoff(pharmacy)) return pharmacy.LastUpdatedAt is not null;

            await _sourceGate.WaitAsync(cancellationToken);
            try
            {
                var rows = await Task.Run(() => source.ReadStock(profileId), cancellationToken);
                repository.ReplaceSnapshot(profileId, rows);
                logger.LogInformation("Standard-N stock refreshed: profile={ProfileId}, rows={Count}", profileId, rows.Count);
            }
            catch (Exception exception)
            {
                try { repository.MarkError(profileId, exception.GetType().Name); }
                catch (Exception cacheError)
                {
                    logger.LogError("Could not store stock refresh error for profile {ProfileId}: {ErrorType}",
                        profileId, cacheError.GetType().Name);
                }
                logger.LogWarning("Standard-N stock refresh failed for profile {ProfileId}: {ErrorType}",
                    profileId, exception.GetType().Name);
            }
            finally
            {
                _sourceGate.Release();
            }
        }
        finally
        {
            gate.Release();
        }
        return repository.GetPharmacy(profileId)?.LastUpdatedAt is not null;
    }

    private bool IsFresh(PharmacyRow pharmacy) =>
        pharmacy.LastUpdatedAt is { } captured &&
        DateTimeOffset.UtcNow - captured < TimeSpan.FromSeconds(options.RefreshSeconds);

    private static bool InBackoff(PharmacyRow pharmacy) =>
        pharmacy.LastErrorAt is { } error &&
        DateTimeOffset.UtcNow - error < TimeSpan.FromMinutes(2);
}

public sealed class RefreshWorker(
    StockRepository repository,
    RefreshCoordinator refresh,
    ILogger<RefreshWorker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await refresh.SyncProfilesAsync(stoppingToken);
                foreach (var id in repository.DueRecentlyRequested(4))
                    await refresh.EnsureFreshAsync(id, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception exception)
            {
                logger.LogWarning(exception, "Stock background refresh failed; cached data remains available");
            }
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(15), stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
        }
    }
}
