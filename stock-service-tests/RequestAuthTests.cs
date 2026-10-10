using Epharm.StockService;
using Epharm.StockService.Source;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging.Abstractions;
using Npgsql;
using System.Security.Cryptography;
using System.Text;

namespace stock_service_tests;

public class RequestAuthTests
{
    [Theory]
    [InlineData("Bearer test-api-key", true)]
    [InlineData("Basic dGVzdDpzdG9jazEyMw==", false)]
    [InlineData("Bearer wrong-key", false)]
    [InlineData("", false)]
    public async Task OnlyConfiguredBearerKeyReachesApi(string authorization, bool allowed)
    {
        var reached = false;
        var options = new StockOptions { ApiKey = "test-api-key" };
        var middleware = new RequestAuth(_ =>
        {
            reached = true;
            return Task.CompletedTask;
        }, options, new CollectorAuth(options), NullLogger<RequestAuth>.Instance);
        var context = new DefaultHttpContext();
        context.Request.Path = "/api/v1/status";
        context.Request.Headers.Authorization = authorization;
        context.Response.Body = new MemoryStream();

        await middleware.InvokeAsync(context);

        Assert.Equal(allowed, reached);
        Assert.Equal(allowed ? 200 : 401, context.Response.StatusCode);
    }

    [Fact]
    public async Task ReadApiKeyCannotAuthorizeCollectorPath()
    {
        var options = new StockOptions { ApiKey = "test-api-key" };
        var middleware = new RequestAuth(_ => Task.CompletedTask, options,
            new CollectorAuth(options), NullLogger<RequestAuth>.Instance);
        var context = new DefaultHttpContext();
        context.Request.Path = "/internal/v1/pharmacies/432/snapshot";
        context.Request.Method = "POST";
        context.Request.Headers.Authorization = "Bearer test-api-key";
        context.Response.Body = new MemoryStream();

        await middleware.InvokeAsync(context);

        Assert.Equal(401, context.Response.StatusCode);
    }

    [PostgresFact]
    public async Task CollectorTokenAuthorizesOnlyItsMappedActivePharmacy()
    {
        using var database = new StockTestDatabase();
        database.Repository.UpsertPharmacies([
            new SourcePharmacy(432, "Пилот", "Алматы", "", "1000"),
            new SourcePharmacy(433, "Другая", "Алматы", "", "1001"),
        ]);
        const string token = "local-collector-test-token-with-40-characters";
        using (var owner = new NpgsqlConnection(database.OwnerConnectionString))
        {
            owner.Open();
            using var provision = new NpgsqlCommand("""
                INSERT INTO collector_credentials(profile_id, token_hash, hq_pharmacy_id, enabled, rotated_at)
                VALUES (432, @hash, 'hq-abaya-150', true, now())
                """, owner);
            provision.Parameters.AddWithValue("hash", SHA256.HashData(Encoding.UTF8.GetBytes(token)));
            provision.ExecuteNonQuery();
        }

        var reached = false;
        var options = new StockOptions
        {
            ApiKey = "read-api-key",
            PostgresConnectionString = database.AppConnectionString,
        };
        var middleware = new RequestAuth(_ =>
        {
            reached = true;
            return Task.CompletedTask;
        }, options, new CollectorAuth(options), NullLogger<RequestAuth>.Instance);

        var accepted = new DefaultHttpContext();
        accepted.Request.Path = "/internal/v1/pharmacies/432/snapshot";
        accepted.Request.Method = "POST";
        accepted.Request.Headers.Authorization = $"Bearer {token}";
        accepted.Response.Body = new MemoryStream();
        await middleware.InvokeAsync(accepted);
        Assert.True(reached);
        Assert.Equal(new CollectorIdentity(432, "hq-abaya-150"),
            accepted.Items[nameof(CollectorIdentity)]);

        reached = false;
        var rejected = new DefaultHttpContext();
        rejected.Request.Path = "/internal/v1/pharmacies/433/snapshot";
        rejected.Request.Method = "POST";
        rejected.Request.Headers.Authorization = $"Bearer {token}";
        rejected.Response.Body = new MemoryStream();
        await middleware.InvokeAsync(rejected);
        Assert.False(reached);
        Assert.Equal(401, rejected.Response.StatusCode);
    }
}
