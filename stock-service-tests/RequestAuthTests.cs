using Epharm.StockService;
using Microsoft.AspNetCore.Http;

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
        var middleware = new RequestAuth(_ =>
        {
            reached = true;
            return Task.CompletedTask;
        }, new StockOptions { ApiKey = "test-api-key" });
        var context = new DefaultHttpContext();
        context.Request.Path = "/api/v1/status";
        context.Request.Headers.Authorization = authorization;
        context.Response.Body = new MemoryStream();

        await middleware.InvokeAsync(context);

        Assert.Equal(allowed, reached);
        Assert.Equal(allowed ? 200 : 401, context.Response.StatusCode);
    }
}
