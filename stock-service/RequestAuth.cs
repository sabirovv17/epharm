using System.Security.Cryptography;
using System.Text;

namespace Epharm.StockService;

public sealed class RequestAuth(RequestDelegate next, StockOptions options)
{
    public async Task InvokeAsync(HttpContext context)
    {
        if (context.Request.Path == "/healthz")
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
        if (ValidBearer(authorization) || ValidBasic(authorization))
        {
            await next(context);
            return;
        }

        context.Response.StatusCode = StatusCodes.Status401Unauthorized;
        context.Response.Headers["WWW-Authenticate"] = "Basic realm=\"Pharmacy stock\", charset=\"UTF-8\"";
        await context.Response.WriteAsJsonAsync(new { error = "unauthorized" });
    }

    private bool ValidBearer(string authorization) =>
        authorization.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) &&
        SecureEquals(authorization[7..].Trim(), options.ApiKey);

    private bool ValidBasic(string authorization)
    {
        if (!authorization.StartsWith("Basic ", StringComparison.OrdinalIgnoreCase) || authorization.Length > 2048)
            return false;
        try
        {
            var decoded = Encoding.UTF8.GetString(Convert.FromBase64String(authorization[6..].Trim()));
            var split = decoded.IndexOf(':');
            return split > 0 &&
                   SecureEquals(decoded[..split], options.WebUser) &&
                   SecureEquals(decoded[(split + 1)..], options.WebPassword);
        }
        catch (FormatException)
        {
            return false;
        }
    }

    private static bool SecureEquals(string supplied, string expected) =>
        CryptographicOperations.FixedTimeEquals(
            SHA256.HashData(Encoding.UTF8.GetBytes(supplied)),
            SHA256.HashData(Encoding.UTF8.GetBytes(expected)));
}
