using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class TaskKioskClientTests
{
    [Fact]
    public async Task ActiveTaskIsMappedAndAcknowledgedWithBoundDeviceIdentity()
    {
        var api = new FakeMerchTaskApi
        {
            Response = new MerchTaskDto(
                "dispatch-1",
                "Проверить выкладку",
                "high",
                DateTimeOffset.Parse("2026-09-12T10:00:00Z"),
                DateTimeOffset.Parse("2026-09-13T10:00:00Z"),
                "https://epharm.inkar.kz/merch/staff?token=opaque",
                "delivery-token"),
        };
        using var client = new TaskKioskClient(api, " pharmacy-7 ", " POS-02 ");

        var items = await client.ListAsync(CancellationToken.None);

        var item = Assert.Single(items);
        Assert.Equal("dispatch-1", item.Id);
        Assert.Equal("Аптека pharmacy-7", item.BranchName);
        Assert.Equal("https://epharm.inkar.kz/merch/staff?token=opaque", item.QrUrl);
        Assert.Equal("pharmacy-7", api.RequestedPharmacyId);

        Assert.True(await client.AcknowledgeAsync(item, CancellationToken.None));
        Assert.Equal(("dispatch-1", "pharmacy-7", "POS-02", "delivery-token"), api.Acknowledgement);
    }

    [Fact]
    public async Task EmptyQueueIsSafeAndDoesNotAcknowledgeAnything()
    {
        var api = new FakeMerchTaskApi();
        using var client = new TaskKioskClient(api, "pharmacy-7", "POS-02");

        Assert.Empty(await client.ListAsync(CancellationToken.None));
        Assert.Null(api.Acknowledgement);
    }

    [Theory]
    [InlineData("http://attacker.example/task")]
    [InlineData("javascript:alert(1)")]
    [InlineData("/relative/task")]
    public async Task UnsafeQrLinksAreRejected(string publicUrl)
    {
        var api = new FakeMerchTaskApi
        {
            Response = new MerchTaskDto(
                "dispatch-1",
                "Task",
                null,
                null,
                null,
                publicUrl,
                "delivery-token"),
        };
        using var client = new TaskKioskClient(api, "pharmacy-7", "POS-02");

        await Assert.ThrowsAsync<InvalidOperationException>(
            () => client.ListAsync(CancellationToken.None));
    }

    [Fact]
    public async Task LoopbackHttpRemainsAvailableForLocalDevelopment()
    {
        var api = new FakeMerchTaskApi
        {
            Response = new MerchTaskDto(
                "dispatch-1",
                "Task",
                null,
                null,
                null,
                "http://127.0.0.1:5173/task",
                "delivery-token"),
        };
        using var client = new TaskKioskClient(api, "pharmacy-7", "POS-02");

        Assert.Single(await client.ListAsync(CancellationToken.None));
    }

    private sealed class FakeMerchTaskApi : IMerchTaskApi
    {
        public MerchTaskDto? Response { get; init; }
        public string? RequestedPharmacyId { get; private set; }
        public (string DispatchId, string PharmacyId, string DeviceId, string DeliveryToken)? Acknowledgement { get; private set; }

        public Task<MerchTaskDto?> GetActiveMerchTaskAsync(
            string pharmacyId,
            CancellationToken ct = default)
        {
            RequestedPharmacyId = pharmacyId;
            return System.Threading.Tasks.Task.FromResult(Response);
        }

        public Task<bool> AcknowledgeMerchTaskAsync(
            string dispatchId,
            string pharmacyId,
            string deviceId,
            string deliveryToken,
            CancellationToken ct = default)
        {
            Acknowledgement = (dispatchId, pharmacyId, deviceId, deliveryToken);
            return System.Threading.Tasks.Task.FromResult(true);
        }
    }
}
