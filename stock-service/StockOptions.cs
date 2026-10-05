namespace Epharm.StockService;

public sealed class StockOptions
{
    public string FirebirdHost { get; init; } = "";
    public int FirebirdPort { get; init; } = 3050;
    public string FirebirdPath { get; init; } = "";
    public string FirebirdUser { get; init; } = "";
    public string FirebirdPassword { get; init; } = "";
    public string WebUser { get; init; } = "";
    public string WebPassword { get; init; } = "";
    public string ApiKey { get; init; } = "";
    public string DataPath { get; init; } = "/app/data/stocks.sqlite";
    public int RefreshSeconds { get; init; } = 180;
    public int QueryTimeoutSeconds { get; init; } = 25;

    public static StockOptions FromEnvironment() => new()
    {
        FirebirdHost = Required("STOCK_FB_HOST"),
        FirebirdPort = Math.Clamp(Int("STOCK_FB_PORT", 3050), 1, 65535),
        FirebirdPath = Required("STOCK_FB_PATH"),
        FirebirdUser = Required("STOCK_FB_USER"),
        FirebirdPassword = Required("STOCK_FB_PASSWORD"),
        WebUser = Required("STOCK_WEB_USER"),
        WebPassword = Required("STOCK_WEB_PASSWORD"),
        ApiKey = Required("STOCK_API_KEY"),
        DataPath = Environment.GetEnvironmentVariable("STOCK_DATA_PATH") ?? "/app/data/stocks.sqlite",
        RefreshSeconds = Math.Max(60, Int("STOCK_REFRESH_SECONDS", 180)),
        QueryTimeoutSeconds = Math.Clamp(Int("STOCK_QUERY_TIMEOUT_SECONDS", 25), 5, 120),
    };

    private static string Required(string name) =>
        Environment.GetEnvironmentVariable(name) is { Length: > 0 } value
            ? value
            : throw new InvalidOperationException($"Missing required environment variable: {name}");

    private static int Int(string name, int fallback) =>
        int.TryParse(Environment.GetEnvironmentVariable(name), out var value) ? value : fallback;
}
