using System.Net;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class MerchTaskDeliveryReplyTests
{
    [Theory]
    [InlineData("{\"accepted\":true,\"available\":true}", true)]
    [InlineData("{\"accepted\":false,\"available\":true}", false)]
    [InlineData("{\"accepted\":false}", false)]
    public async Task OnlyExplicitAvailableAcknowledgementReturnsBoolean(string json, bool expected)
    {
        using var response = Response(HttpStatusCode.OK, json);
        Assert.Equal(expected, await MerchTaskDeliveryReply.ReadAsync(response, TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task HttpFailureIsTransientInsteadOfRetiringPendingReceipt()
    {
        using var response = Response(HttpStatusCode.ServiceUnavailable, "{\"accepted\":false}");
        await Assert.ThrowsAsync<HttpRequestException>(() => MerchTaskDeliveryReply.ReadAsync(response, TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task UnavailableEnvelopeRemainsRetryableEvenWithHttp200()
    {
        using var response = Response(HttpStatusCode.OK, "{\"accepted\":false,\"available\":false}");
        await Assert.ThrowsAsync<MerchTaskUnavailableException>(() => MerchTaskDeliveryReply.ReadAsync(response, TestContext.Current.CancellationToken));
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("null")]
    [InlineData("{\"accepted\":null}")]
    [InlineData("{\"accepted\":\"false\"}")]
    [InlineData("{\"accepted\":false,\"available\":\"true\"}")]
    public async Task MissingOrMalformedFieldsAreNotPermanentRejections(string json)
    {
        using var response = Response(HttpStatusCode.OK, json);
        await Assert.ThrowsAsync<HttpRequestException>(() => MerchTaskDeliveryReply.ReadAsync(response, TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task CancellationPropagatesAndDoesNotMasqueradeAsNegativeReply()
    {
        using var response = Response(HttpStatusCode.OK, "{\"accepted\":true}");
        using var cancelled = new CancellationTokenSource();
        cancelled.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => MerchTaskDeliveryReply.ReadAsync(response, cancelled.Token));
    }

    private static HttpResponseMessage Response(HttpStatusCode status, string body) =>
        new(status) { Content = new StringContent(body) };
}
