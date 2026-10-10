using Epharm.StockService;
using Epharm.StockService.Source;

var builder = WebApplication.CreateBuilder(args);
var options = StockOptions.FromEnvironment();
builder.Services.AddSingleton(options);
builder.Services.AddSingleton<StockRepository>();
builder.Services.AddSingleton<IStockSource>(_ => new StandardNSource(
    options.FirebirdHost, options.FirebirdPort, options.FirebirdPath,
    options.FirebirdUser, options.FirebirdPassword, options.QueryTimeoutSeconds));
builder.Services.AddSingleton<RefreshCoordinator>();
if (options.CollectionEnabled)
    builder.Services.AddHostedService<RefreshWorker>();

var app = builder.Build();
app.Services.GetRequiredService<StockRepository>().Initialize();
app.UseMiddleware<RequestAuth>();

app.MapGet("/healthz", () => Results.Ok(new { status = "ok" }));

app.MapGet("/api/v1/status", (StockRepository repository) =>
    Results.Ok(repository.GetCollectionStatus()));

app.MapGet("/api/v1/cities", (StockRepository repository) =>
    Results.Ok(repository.ListCities()));

app.MapGet("/api/v1/pharmacies", (string? city, StockRepository repository) =>
    Results.Ok(repository.ListPharmacies(city)));

app.MapGet("/api/v1/pharmacies/{id:long}", (long id, StockRepository repository) =>
    repository.GetPharmacy(id) is { } pharmacy
        ? Results.Ok(pharmacy)
        : Results.NotFound(new { error = "pharmacy_not_found" }));

app.MapGet("/api/v1/pharmacies/{id:long}/stocks", IResult (
    long id, HttpRequest request, StockRepository repository) =>
{
    if (repository.GetPharmacy(id) is null)
        return Results.NotFound(new { error = "pharmacy_not_found" });

    var q = request.Query["q"].ToString().Trim();
    if (q.Length > 100)
        return Results.BadRequest(new { error = "search_too_long" });
    if (!int.TryParse(request.Query["limit"], out var limit)) limit = 100;
    if (!int.TryParse(request.Query["offset"], out var offset)) offset = 0;
    if (limit is < 1 or > 500 || offset is < 0 or > 1_000_000)
        return Results.BadRequest(new { error = "invalid_pagination" });

    var expectedSnapshot = request.Query["snapshot"].ToString();
    var page = repository.GetStocks(id, q, limit, offset)!;
    if (expectedSnapshot.Length > 0 &&
        !string.Equals(expectedSnapshot, page.AsOf?.ToString("O"), StringComparison.Ordinal))
        return Results.Conflict(new { error = "snapshot_changed", currentSnapshot = page.AsOf });
    if (page.AsOf is null)
        return Results.Problem("Снимок пока недоступен", statusCode: StatusCodes.Status503ServiceUnavailable);
    return Results.Ok(page);
});

app.Run();
