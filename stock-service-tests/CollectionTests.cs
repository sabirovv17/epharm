using Epharm.StockService;
using Epharm.StockService.Source;
using Microsoft.Extensions.Logging.Abstractions;

namespace stock_service_tests;

public class CollectionTests
{
    [Fact]
    public void RestartKeepsSweepCadenceAndCooldown()
    {
        var now = new DateTimeOffset(2026, 10, 5, 15, 0, 0, TimeSpan.Zero);
        Assert.Equal(TimeSpan.Zero, RefreshWorker.DelayBeforeNextRun(null, null, 1800, now));
        Assert.Equal(TimeSpan.FromMinutes(25),
            RefreshWorker.DelayBeforeNextRun(now.AddMinutes(-5), now, 1800, now));
        Assert.Equal(TimeSpan.FromMinutes(3),
            RefreshWorker.DelayBeforeNextRun(now.AddMinutes(-31), now, 1800, now));
        Assert.Equal(TimeSpan.FromMinutes(1),
            RefreshWorker.DelayBeforeNextRun(now.AddMinutes(-2), now, 180, now));
    }

    [Fact]
    public async Task FullCycleRefreshesEveryProfileAndArchivesMissingOnes()
    {
        var path = Path.Combine(Path.GetTempPath(), $"stock-cycle-{Guid.NewGuid():N}.sqlite");
        try
        {
            var repository = new StockRepository(new StockOptions { DataPath = path });
            repository.Initialize();
            var source = new FakeSource();
            source.Profiles.AddRange([
                new SourcePharmacy(7, "Первая", "Алматы", "", "7"),
                new SourcePharmacy(8, "Вторая", "Алматы", "", "8"),
            ]);
            source.Stocks[7] = [Stock(7, "a", 1), Stock(7, "b", 1)];
            source.Stocks[8] = [Stock(8, "c", 2)];
            var coordinator = new RefreshCoordinator(repository, source, NullLogger<RefreshCoordinator>.Instance);

            await coordinator.RunCycleAsync(CancellationToken.None);

            Assert.Equal([7L, 8L], source.ReadIds);
            Assert.Equal(2, repository.GetStocks(7, null, 100, 0)!.Total);
            Assert.Equal(["a", "b"], repository.GetStocks(7, null, 100, 0)!.Items.Select(x => x.SourceId).ToArray());
            var status = repository.GetCollectionStatus();
            Assert.Equal("succeeded", status.RunStatus);
            Assert.Equal(2, status.RunSucceeded);
            Assert.Equal(2, status.FreshCount);

            source.Profiles.RemoveAt(1);
            source.ReadIds.Clear();
            await coordinator.RunCycleAsync(CancellationToken.None);

            Assert.Equal([7L], source.ReadIds);
            Assert.Null(repository.GetPharmacy(8));
            Assert.Equal(1, repository.GetCollectionStatus().PharmacyCount);
        }
        finally { Delete(path); }
    }

    [Fact]
    public async Task FailedProfilePreservesLastSnapshotAndReportsPartialCycle()
    {
        var path = Path.Combine(Path.GetTempPath(), $"stock-cycle-{Guid.NewGuid():N}.sqlite");
        try
        {
            var repository = new StockRepository(new StockOptions { DataPath = path });
            repository.Initialize();
            var source = new FakeSource();
            source.Profiles.AddRange([
                new SourcePharmacy(7, "Первая", "Алматы", "", "7"),
                new SourcePharmacy(8, "Вторая", "Алматы", "", "8"),
            ]);
            source.Stocks[7] = [Stock(7, "old", 1)];
            source.Stocks[8] = [Stock(8, "ok", 2)];
            var coordinator = new RefreshCoordinator(repository, source, NullLogger<RefreshCoordinator>.Instance);
            await coordinator.RunCycleAsync(CancellationToken.None);
            var snapshot = repository.GetStocks(7, null, 100, 0)!.SnapshotId;

            source.FailProfile = 7;
            await coordinator.RunCycleAsync(CancellationToken.None);

            var page = repository.GetStocks(7, null, 100, 0)!;
            Assert.Equal(snapshot, page.SnapshotId);
            Assert.Equal("old", Assert.Single(page.Items).SourceId);
            Assert.Equal("error", page.Pharmacy.Status);
            var status = repository.GetCollectionStatus();
            Assert.Equal("partial", status.RunStatus);
            Assert.Equal(1, status.RunSucceeded);
            Assert.Equal(1, status.RunFailed);
        }
        finally { Delete(path); }
    }

    private static SourceStock Stock(long profile, string sourceId, long part) =>
        new(profile, sourceId, part, "Товар", null, null, 1m, 2m, null, null, "шт");

    private static void Delete(string path)
    {
        foreach (var candidate in new[] { path, path + "-wal", path + "-shm" })
            if (File.Exists(candidate)) File.Delete(candidate);
    }

    private sealed class FakeSource : IStockSource
    {
        public List<SourcePharmacy> Profiles { get; } = [];
        public Dictionary<long, IReadOnlyList<SourceStock>> Stocks { get; } = [];
        public List<long> ReadIds { get; } = [];
        public long? FailProfile { get; set; }

        public IReadOnlyList<SourcePharmacy> ReadPharmacies() => Profiles.ToArray();

        public IReadOnlyList<SourceStock> ReadStock(long profileId)
        {
            ReadIds.Add(profileId);
            if (FailProfile == profileId) throw new IOException("Simulated source failure");
            return Stocks[profileId];
        }
    }
}
