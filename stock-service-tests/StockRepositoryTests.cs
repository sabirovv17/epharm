using Epharm.StockService;
using Epharm.StockService.Source;
using Microsoft.Data.Sqlite;

namespace stock_service_tests;

public class StockRepositoryTests
{
    [Fact]
    public void ExistingCacheIsReadableAfterSchemaUpgrade()
    {
        var path = Path.Combine(Path.GetTempPath(), $"stock-test-{Guid.NewGuid():N}.sqlite");
        try
        {
            using (var connection = new SqliteConnection($"Data Source={path}"))
            {
                connection.Open();
                using var command = connection.CreateCommand();
                command.CommandText = """
                    CREATE TABLE pharmacies (
                      id INTEGER PRIMARY KEY, name TEXT NOT NULL, city TEXT NOT NULL,
                      address TEXT NOT NULL, pharmacy_number TEXT NOT NULL,
                      stock_count INTEGER, last_updated_at TEXT, last_requested_at TEXT,
                      last_error_at TEXT, last_error TEXT);
                    CREATE TABLE stocks (
                      profile_id INTEGER NOT NULL, part_id INTEGER NOT NULL,
                      name TEXT NOT NULL, manufacturer_barcode TEXT, barcode TEXT,
                      quantity TEXT NOT NULL, price TEXT, expiry_date TEXT, series TEXT,
                      unit TEXT, PRIMARY KEY(profile_id, part_id));
                    INSERT INTO pharmacies(id, name, city, address, pharmacy_number,
                      stock_count, last_updated_at)
                    VALUES (7, 'Старая аптека', 'Алматы', '', '7', 1,
                      '2026-10-05T00:00:00.0000000+00:00');
                    INSERT INTO stocks(profile_id, part_id, name, quantity)
                    VALUES (7, 11, 'Старая партия', '2.5');
                    """;
                command.ExecuteNonQuery();
            }
            var repository = new StockRepository(new StockOptions { DataPath = path });
            repository.Initialize();
            var page = repository.GetStocks(7, null, 100, 0)!;
            Assert.Equal(1, page.Total);
            Assert.Equal("legacy:11", Assert.Single(page.Items).SourceId);
            Assert.Null(page.AsOf);
            Assert.Equal("pending", page.Pharmacy.Status);
            repository.ReplaceSnapshot(7, [Stock(7, 11, "Новая партия")]);
            var refreshed = repository.GetStocks(7, null, 100, 0)!;
            Assert.NotNull(refreshed.AsOf);
            Assert.Equal("00000000-0000-0000-0000-000000000011",
                Assert.Single(refreshed.Items).SourceId);
        }
        finally
        {
            foreach (var candidate in new[] { path, path + "-wal", path + "-shm" })
                if (File.Exists(candidate)) File.Delete(candidate);
        }
    }

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
        new(profile, $"00000000-0000-0000-0000-{part:D12}", part, name, "123", "456", 2.5m, 100m, null, null, "шт");
}
