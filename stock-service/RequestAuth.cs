using System.Security.Cryptography;
using System.Text;
using Npgsql;

namespace Epharm.StockService;

public sealed class RequestAuth(
    RequestDelegate next, StockOptions options, CollectorAuth collectorAuth,
    ILogger<RequestAuth> logger)
{
    private readonly SemaphoreSlim _collectorSlots = new(4, 4);

    public async Task InvokeAsync(HttpContext context)
    {
        if (context.Request.Path == "/healthz" || context.Request.Path == "/readyz")
        {
            await next(context);
            return;
        }

        context.Response.Headers["Cache-Control"] = "no-store";
        context.Response.Headers["X-Content-Type-Options"] = "nosniff";
        context.Response.Headers["X-Frame-Options"] = "DENY";
        context.Response.Headers["Referrer-Policy"] = "no-referrer";
        context.Response.Headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'";

        var authorization = context.Request.Headers.Authorization.ToString();
        if (context.Request.Path.StartsWithSegments("/internal/v1"))
        {
            var segments = context.Request.Path.Value?.Split('/', StringSplitOptions.RemoveEmptyEntries);
            if (context.Request.Method != "POST" || segments is null || segments.Length != 5 ||
                segments[0] != "internal" || segments[1] != "v1" ||
                segments[2] != "pharmacies" || segments[4] != "snapshot" ||
                !long.TryParse(segments[3], out var profileId) || profileId <= 0)
            {
                context.Response.StatusCode = StatusCodes.Status404NotFound;
                return;
            }
            if (!_collectorSlots.Wait(0))
            {
                context.Response.StatusCode = StatusCodes.Status429TooManyRequests;
                context.Response.Headers.RetryAfter = "3";
                await context.Response.WriteAsJsonAsync(new { error = "collector_busy" });
                return;
            }
            try
            {
                try
                {
                    var identity = await collectorAuth.AuthenticateAsync(
                        profileId, authorization, context.RequestAborted);
                    if (identity is not null)
                    {
                        context.Items[nameof(CollectorIdentity)] = identity;
                        await next(context);
                        return;
                    }
                }
                catch (NpgsqlException exception)
                {
                    logger.LogError(exception, "Collector authentication store unavailable");
                    context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
                    await context.Response.WriteAsJsonAsync(new { error = "authentication_unavailable" });
                    return;
                }
                await Unauthorized(context);
            }
            finally
            {
                _collectorSlots.Release();
            }
            return;
        }
        if (ValidBearer(authorization))
        {
            await next(context);
            return;
        }

        await Unauthorized(context);
    }

    private bool ValidBearer(string authorization) =>
        authorization.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) &&
        SecureEquals(authorization[7..].Trim(), options.ApiKey);

    private static bool SecureEquals(string supplied, string expected) =>
        CryptographicOperations.FixedTimeEquals(
            SHA256.HashData(Encoding.UTF8.GetBytes(supplied)),
            SHA256.HashData(Encoding.UTF8.GetBytes(expected)));

    private static async Task Unauthorized(HttpContext context)
    {
        context.Response.StatusCode = StatusCodes.Status401Unauthorized;
        context.Response.Headers["WWW-Authenticate"] = "Bearer";
        await context.Response.WriteAsJsonAsync(new { error = "unauthorized" });
    }
}
