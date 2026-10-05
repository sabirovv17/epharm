using Epharm.StockService;
using Epharm.StockService.Source;

namespace stock_service_tests;

public class StockRepositoryTests
{
    [Fact]
    public void FailedReplacementKeepsPreviousCompleteSnapshot()
    {
        var path = Path.Combine(Path.GetTempPath(), $"stock-test-{Guid.NewGuid():N}.sqlite");
        try
        {
            var repository = new StockRepository(new StockOptions { DataPath = path });
            repository.Initialize();
            repository.UpsertPharmacies([new SourcePharmacy(7, "Тестовая аптека", "Алматы", "Адрес", "1000")]);
            repository.ReplaceSnapshot(7,
            [
                Stock(7, 11, "Лекарство 1"),
                Stock(7, 12, "Лекарство 2"),
            ]);
            var original = repository.GetStocks(7, null, 100, 0)!;

            Assert.Throws<Microsoft.Data.Sqlite.SqliteException>(() => repository.ReplaceSnapshot(7,
            [
                Stock(7, 13, "Новая запись"),
                Stock(7, 13, "Дубликат"),
            ]));

            var after = repository.GetStocks(7, null, 100, 0)!;
            Assert.Equal(2, after.Total);
            Assert.Equal(original.SnapshotId, after.SnapshotId);
            Assert.Equal([11L, 12L], after.Items.Select(item => item.PartId).ToArray());
        }
        finally
        {
            foreach (var candidate in new[] { path, path + "-wal", path + "-shm" })
                if (File.Exists(candidate)) File.Delete(candidate);
        }
    }

    [Fact]
    public void SearchTreatsPercentAsLiteralAndKeepsPageCount()
    {
        var path = Path.Combine(Path.GetTempPath(), $"stock-test-{Guid.NewGuid():N}.sqlite");
        try
        {
            var repository = new StockRepository(new StockOptions { DataPath = path });
            repository.Initialize();
            repository.UpsertPharmacies([new SourcePharmacy(8, "Аптека", "Астана", "", "1000")]);
            repository.ReplaceSnapshot(8,
            [Stock(8, 1, "Раствор 5%"), Stock(8, 2, "Обычный товар")]);

            var page = repository.GetStocks(8, "%", 1, 0)!;
            Assert.Equal(1, page.Total);
            Assert.Single(page.Items);
            Assert.Equal(1L, page.Items[0].PartId);
        }
        finally
        {
            foreach (var candidate in new[] { path, path + "-wal", path + "-shm" })
                if (File.Exists(candidate)) File.Delete(candidate);
        }
    }

    private static SourceStock Stock(long profile, long part, string name) =>
        new(profile, part, name, "123", "456", 2.5m, 100m, null, null, "шт");
}
