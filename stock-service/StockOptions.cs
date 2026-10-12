using Npgsql;

namespace Epharm.StockService;

public sealed class StockOptions
{
    public string ApiKey { get; init; } = "";
    public string PostgresConnectionString { get; init; } = "";
    public int RefreshSeconds { get; init; } = 180;
    public int SweepIntervalSeconds { get; init; } = 1800;
    public bool CollectionEnabled { get; init; } = false;

    public static StockOptions FromEnvironment() => new()
    {
        ApiKey = Required("STOCK_API_KEY"),
        PostgresConnectionString = PostgresConnection(),
        RefreshSeconds = Math.Max(60, Int("STOCK_REFRESH_SECONDS", 180)),
        SweepIntervalSeconds = Math.Max(180, Int("STOCK_SWEEP_INTERVAL_SECONDS", 1800)),
        CollectionEnabled = DisabledCentralCollection(),
    };

    private static string Required(string name) =>
        Environment.GetEnvironmentVariable(name) is { Length: > 0 } value
            ? value
            : throw new InvalidOperationException($"Missing required environment variable: {name}");

    private static string PostgresConnection()
    {
        var explicitConnection = Environment.GetEnvironmentVariable("STOCK_PG_CONNECTION_STRING");
        if (!string.IsNullOrWhiteSpace(explicitConnection)) return explicitConnection;
        return new NpgsqlConnectionStringBuilder
        {
            Host = Environment.GetEnvironmentVariable("STOCK_PG_HOST") ?? "postgres",
            Port = Math.Clamp(Int("STOCK_PG_PORT", 5432), 1, 65535),
            Database = Environment.GetEnvironmentVariable("STOCK_PG_DATABASE") ?? "stocks",
            Username = Environment.GetEnvironmentVariable("STOCK_PG_USER") ?? "stock_app",
            Password = Required("STOCK_PG_PASSWORD"),
        }.ConnectionString;
    }

    private static int Int(string name, int fallback) =>
        int.TryParse(Environment.GetEnvironmentVariable(name), out var value) ? value : fallback;

    private static bool DisabledCentralCollection()
    {
        var value = Environment.GetEnvironmentVariable("STOCK_COLLECTION_ENABLED");
        if (string.IsNullOrWhiteSpace(value) || value.Equals("false", StringComparison.OrdinalIgnoreCase))
            return false;
        throw new InvalidOperationException("STOCK_COLLECTION_ENABLED must be false; central collection is retired");
    }
}
