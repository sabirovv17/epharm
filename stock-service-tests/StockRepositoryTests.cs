using Epharm.StockService;
using Epharm.StockService.Source;
using Npgsql;

namespace stock_service_tests;

public class StockRepositoryTests
{
    [PostgresFact]
    public void ChangedSnapshotIsAtomicAndOlderObservationIsRejected()
    {
        using var database = new StockTestDatabase();
        var repository = database.Repository;
        Assert.True(repository.IsReady());
        repository.UpsertPharmacies([new SourcePharmacy(7, "Аптека", "Алматы", "", "1000")]);
        var first = DateTimeOffset.UtcNow.AddMinutes(-2);
        Assert.True(repository.TryReplaceSnapshot(7, [Stock(7, "a", 11), Stock(7, "b", 12)], first));
        var before = repository.GetStocks(7, null, 100, 0)!;
        Assert.Equal(2, before.Total);

        Assert.Throws<InvalidDataException>(() => repository.TryReplaceSnapshot(7,
            [Stock(7, "duplicate", 13), Stock(7, "duplicate", 14)], first.AddMinutes(1)));
        Assert.Throws<InvalidDataException>(() => repository.TryReplaceSnapshot(7,
            [Stock(8, "wrong-profile", 13)], first.AddMinutes(1)));
        var afterFailure = repository.GetStocks(7, null, 100, 0)!;
        Assert.Equal(before.SnapshotId, afterFailure.SnapshotId);
        Assert.Equal(["a", "b"], afterFailure.Items.Select(item => item.SourceId).ToArray());

        var second = first.AddMinutes(1);
        Assert.True(repository.TryReplaceSnapshot(7, [Stock(7, "a", 11), Stock(7, "b", 12)], second));
        var unchanged = repository.GetStocks(7, null, 100, 0)!;
        Assert.Equal(before.Total, unchanged.Total);
        Assert.NotEqual(before.SnapshotId, unchanged.SnapshotId);
        Assert.False(repository.TryReplaceSnapshot(7, [Stock(7, "old", 99)], first));
        Assert.Equal(unchanged.SnapshotId, repository.GetStocks(7, null, 100, 0)!.SnapshotId);

        Assert.True(repository.TryReplaceSnapshot(7,
            [Stock(7, "a", 11) with { Quantity = 0 }], DateTimeOffset.UtcNow));
        var empty = repository.GetStocks(7, null, 100, 0)!;
        Assert.Equal(0, empty.Total);
        Assert.Empty(empty.Items);
        Assert.Equal(0, empty.Pharmacy.StockCount);
    }

    [PostgresFact]
    public void SearchTreatsWildcardsAsLiteralAndKeepsTotalAcrossPages()
    {
        using var database = new StockTestDatabase();
        var repository = database.Repository;
        repository.UpsertPharmacies([new SourcePharmacy(8, "Аптека", "Астана", "", "1000")]);
        repository.TryReplaceSnapshot(8,
        [
            Stock(8, "a", 1) with { Name = "Раствор 5%" },
            Stock(8, "b", 2) with { Name = "A_B" },
            Stock(8, "c", 3) with { Name = "C\\D" },
            Stock(8, "d", 4) with { Name = "Обычный товар" },
        ], DateTimeOffset.UtcNow);
        var percent = repository.GetStocks(8, "%", 1, 0)!;
        Assert.Equal(1, percent.Total);
        Assert.Equal("a", Assert.Single(percent.Items).SourceId);
        Assert.Equal("b", Assert.Single(repository.GetStocks(8, "_", 1, 0)!.Items).SourceId);
        Assert.Equal("c", Assert.Single(repository.GetStocks(8, "\\", 1, 0)!.Items).SourceId);
        var page = repository.GetStocks(8, null, 2, 1)!;
        Assert.Equal(4, page.Total);
        Assert.Equal(2, page.Items.Count);
        Assert.Equal(percent.SnapshotId, page.SnapshotId);
    }

    [PostgresFact]
    public void LegacyIsAlwaysStaleAndCashierSnapshotClearsError()
    {
        using var database = new StockTestDatabase();
        var repository = database.Repository;
        repository.UpsertPharmacies([new SourcePharmacy(9, "Аптека", "Шымкент", "", "1001")]);
        repository.TryReplaceSnapshot(9, [Stock(9, "legacy", 1)],
            DateTimeOffset.UtcNow, "central_legacy");
        var legacy = repository.GetStocks(9, null, 100, 0)!;
        Assert.Equal("stale", legacy.Pharmacy.Status);
        Assert.Equal("central_legacy", legacy.SourceKind);
        Assert.Null(legacy.SourceObservedAt);
        Assert.NotNull(legacy.IngestedAt);
        var status = repository.GetCollectionStatus();
        Assert.Equal(0, status.FreshCount);
        Assert.Equal(1, status.LegacyCount);

        repository.MarkError(9, "offline");
        Assert.Equal("error", repository.GetPharmacy(9)!.Status);
        Assert.Equal("legacy", Assert.Single(repository.GetStocks(9, null, 100, 0)!.Items).SourceId);
        var observedAt = DateTimeOffset.UtcNow.AddSeconds(1);
        repository.TryReplaceSnapshot(9, [Stock(9, "cashier", 2)], observedAt);
        var cashier = repository.GetStocks(9, null, 100, 0)!;
        Assert.Equal("fresh", cashier.Pharmacy.Status);
        Assert.Equal("cashier_local", cashier.SourceKind);
        Assert.Equal(observedAt.ToUnixTimeSeconds(), cashier.SourceObservedAt!.Value.ToUnixTimeSeconds());
        Assert.Equal(1, repository.GetCollectionStatus().CashierCount);
    }

    [PostgresFact]
    public void ApplicationRoleCannotCreateTables()
    {
        using var database = new StockTestDatabase();
        using var connection = new NpgsqlConnection(database.AppConnectionString);
        connection.Open();
        using var command = new NpgsqlCommand("CREATE TABLE forbidden_by_app(id bigint)", connection);
        var error = Assert.Throws<PostgresException>(() => command.ExecuteNonQuery());
        Assert.Equal("42501", error.SqlState);
    }

    [PostgresFact]
    public void CollectorCredentialsAreReadableButNotWritableByApplicationRole()
    {
        using var database = new StockTestDatabase();
        database.Repository.UpsertPharmacies([new SourcePharmacy(10, "Аптека", "Алматы", "", "10")]);
        using (var owner = new NpgsqlConnection(database.OwnerConnectionString))
        {
            owner.Open();
            using var provision = new NpgsqlCommand("""
                INSERT INTO collector_credentials(profile_id, token_hash, hq_pharmacy_id, enabled, rotated_at)
                VALUES (10, decode(repeat('ab', 32), 'hex'), 'hq-test-10', true, now())
                """, owner);
            provision.ExecuteNonQuery();
        }
        using var app = new NpgsqlConnection(database.AppConnectionString);
        app.Open();
        using var read = new NpgsqlCommand("SELECT octet_length(token_hash) FROM collector_credentials WHERE profile_id = 10", app);
        Assert.Equal(32, read.ExecuteScalar());
        using var update = new NpgsqlCommand("UPDATE collector_credentials SET enabled = false WHERE profile_id = 10", app);
        Assert.Equal("42501", Assert.Throws<PostgresException>(() => update.ExecuteNonQuery()).SqlState);
        using var insert = new NpgsqlCommand("""
            INSERT INTO collector_credentials(profile_id, token_hash, hq_pharmacy_id, enabled, rotated_at)
            VALUES (11, decode(repeat('ab', 32), 'hex'), 'hq-test-11', true, now())
            """, app);
        Assert.Equal("42501", Assert.Throws<PostgresException>(() => insert.ExecuteNonQuery()).SqlState);
    }

    private static SourceStock Stock(long profile, string sourceId, long part) =>
        new(profile, sourceId, part, "Товар", "123", "456", 2.5m, 100m, null, null, "шт");
}

public sealed class PostgresFactAttribute : FactAttribute
{
    public PostgresFactAttribute()
    {
        if (string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("STOCK_TEST_PG_OWNER_CONNECTION_STRING")) ||
            string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("STOCK_TEST_PG_CONNECTION_STRING")))
            Skip = "Set STOCK_TEST_PG_OWNER_CONNECTION_STRING and STOCK_TEST_PG_CONNECTION_STRING";
    }
}

public sealed class StockTestDatabase : IDisposable
{
    private readonly string _schema;
    public string OwnerConnectionString { get; }
    public string AppConnectionString { get; }
    public StockRepository Repository { get; }

    public StockTestDatabase()
    {
        _schema = "stock_test_" + Guid.NewGuid().ToString("N");
        var ownerBuilder = new NpgsqlConnectionStringBuilder(
            Environment.GetEnvironmentVariable("STOCK_TEST_PG_OWNER_CONNECTION_STRING"))
        {
            SearchPath = _schema,
        };
        OwnerConnectionString = ownerBuilder.ConnectionString;
        using (var connection = new NpgsqlConnection(
            Environment.GetEnvironmentVariable("STOCK_TEST_PG_OWNER_CONNECTION_STRING")))
        {
            connection.Open();
            using var create = new NpgsqlCommand($"CREATE SCHEMA {_schema}", connection);
            create.ExecuteNonQuery();
        }
        StockRepository.BootstrapSchema(OwnerConnectionString);
        var appBuilder = new NpgsqlConnectionStringBuilder(
            Environment.GetEnvironmentVariable("STOCK_TEST_PG_CONNECTION_STRING"))
        {
            SearchPath = _schema,
        };
        AppConnectionString = appBuilder.ConnectionString;
        Repository = new StockRepository(new StockOptions
        {
            PostgresConnectionString = AppConnectionString,
            RefreshSeconds = 180,
            CollectionEnabled = false,
        });
        Repository.Initialize();
    }

    public void Dispose()
    {
        using var connection = new NpgsqlConnection(
            Environment.GetEnvironmentVariable("STOCK_TEST_PG_OWNER_CONNECTION_STRING"));
        connection.Open();
        using var drop = new NpgsqlCommand($"DROP SCHEMA {_schema} CASCADE", connection);
        drop.ExecuteNonQuery();
    }
}
