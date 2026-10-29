using System.Net;
using System.Net.Sockets;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;
using V57 = CustomerDisplay.Core.Tests.Legacy.V1057.Services;
using V66 = CustomerDisplay.Core.Tests.Legacy.V1066.Services;
using Config57 = CustomerDisplay.Core.Tests.Legacy.V1057.Config.EpharmConfig;
using Config66 = CustomerDisplay.Core.Tests.Legacy.V1066.Config.EpharmConfig;

namespace CustomerDisplay.Core.Tests;

public sealed class LegacyPosmCompatibilityTests
{
    private const string Origin = "https://epharm.inkar.kz";

    [Theory]
    [InlineData("1.0.57")]
    [InlineData("1.0.66")]
    public async Task HistoricalClientsExecuteGoldenHttpPathsHeadersBodiesAndParseCurrentResponses(string version)
    {
        var fixture = Fixture(version);
        var steps = fixture["steps"]!.AsArray();
        await using var server = new LoopbackServer(steps.Select(step => (HttpStatusCode.OK, step!["response"]!)));
        dynamic api = Api(version, server.Origin);
        using var disposeApi = (IDisposable)api;
        var ct = TestContext.Current.CancellationToken;
        var pharmacy = fixture["pharmacyId"]!.GetValue<string>();
        var device = fixture["deviceId"]!.GetValue<string>();
        dynamic recommendation = await api.RecommendAsync((dynamic)Model(version, "RecommendRequest", steps[0]!["body"]!), ct);
        var parsed = Json((object)recommendation);
        Assert.Equal("rec_synthetic_1", parsed["recommendations"]![0]!["eventId"]!.GetValue<string>());
        Assert.Equal(50, parsed["recommendations"]![0]!["bonus"]!.GetValue<int>());
        // Additive backend stock/source fields are deliberately unknown to both historical DTOs.
        Assert.Null(parsed["recommendations"]![0]!["balance"]);
        Assert.True((bool)await api.MarkShownAsync("rec_synthetic_1", DateTimeOffset.Parse("2026-10-29T00:00:00Z"), ct));
        Assert.True((bool)await api.RecordOutcomeAsync("rec_synthetic_1", (dynamic)Model(version, "OutcomeRequest", steps[2]!["body"]!), ct));
        Assert.True((bool)await api.PostSaleAsync((dynamic)Model(version, "SaleReport", steps[3]!["body"]!), ct));
        dynamic playlist = await api.GetActivePlaylistAsync(pharmacy, ct);
        Assert.Equal(Origin + "/s3/synthetic/slide.mp4", Json((object)playlist)["slides"]![0]!["url"]!.GetValue<string>());
        Assert.True((bool)await api.HeartbeatAsync(device, pharmacy, 2, version, ct));
        dynamic update = await api.GetAppVersionAsync("win-x64", device, version, ct);
        Assert.Equal(Origin + "/downloads/synthetic-posm.zip", Json((object)update)["url"]!.GetValue<string>());
        Assert.NotNull((object?)await api.CdpLookupAsync("+70000000000", ct));
        Assert.NotNull((object?)await api.CdpRegisterAsync((dynamic)Model(version, "CdpRegisterRequestDto", steps[8]!["body"]!), ct));
        dynamic kiosk = Kiosk(version, api, pharmacy, device);
        using var disposeKiosk = (IDisposable)kiosk;
        dynamic items = await kiosk.ListAsync(ct);
        Assert.Equal(1, (int)items.Count);
        Assert.Equal(steps[9]!["response"]!["task"]!["publicUrl"]!.GetValue<string>(), (string)items[0].QrUrl);
        Assert.True((bool)await kiosk.AcknowledgeAsync(items[0], ct));
        dynamic fulfillment = Fulfillment(version, server.Origin);
        using var disposeFulfillment = (IDisposable)fulfillment;
        Assert.True((bool)(await fulfillment.RegisterAsync(device, pharmacy, ct)).IsSuccess);
        const string fulfillmentToken = "synthetic-fulfillment-token-only-0000000000";
        Assert.True((bool)(await fulfillment.ListAsync(fulfillmentToken, "active", 0, 50, ct)).IsSuccess);
        Assert.True((bool)(await fulfillment.GetAsync(fulfillmentToken, "order-synthetic-1", ct)).IsSuccess);
        Assert.True((bool)(await fulfillment.ActAsync(fulfillmentToken, "order-synthetic-1",
            (dynamic)Model(version, "FulfillmentActionRequest", steps[14]!["body"]!), ct)).IsSuccess);
        await server.Completion.WaitAsync(TimeSpan.FromSeconds(10), ct);
        Assert.Equal(steps.Count, server.Requests.Count);
        for (var index = 0; index < steps.Count; index++)
        {
            var expected = steps[index]!;
            var actual = server.Requests[index];
            Assert.Equal(expected["method"]!.GetValue<string>(), actual.Method);
            Assert.Equal(expected["pathAndQuery"]!.GetValue<string>(), actual.Path);
            Assert.True(JsonNode.DeepEquals(expected["body"], actual.Body), $"{version}/{expected["name"]}: {actual.Body}");
            foreach (var header in new[] { "X-Posm-Key", "X-Device-Id", "X-Fulfillment-Device", "Authorization", "Cookie", "X-Staff-Session" })
                Assert.Equal(expected["headers"]?[header]?.GetValue<string>(), actual.Headers.GetValueOrDefault(header));
        }
    }

    [Theory]
    [InlineData("1.0.57")]
    [InlineData("1.0.66")]
    public void LegacyGoldenPayloadsStillDecodeIntoCurrentModels(string version)
    {
        var steps = Fixture(version)["steps"]!.AsArray();
        var recommend = steps[0]!["body"]!.Deserialize<RecommendRequest>(EpharmJson.Options)!;
        Assert.Equal("pharmacy-synthetic-1", recommend.PharmacyId);
        Assert.Equal("synthetic-ipart-1", Assert.Single(recommend.Cart).Sku);
        var sale = steps[3]!["body"]!.Deserialize<SaleReport>(EpharmJson.Options)!;
        Assert.Equal("sale-synthetic-1", sale.SaleId);
        Assert.Equal(101, sale.SourceDocumentId);
        Assert.Equal(1, Assert.Single(sale.Items).Qty);
        Assert.Equal(900, sale.TotalAmount);
        Assert.Equal(DateTimeOffset.Parse("2026-10-29T00:00:00Z"), sale.PrintedAt);
        Assert.Null(sale.ArtifactSha256);
        var response = steps[0]!["response"]!.Deserialize<RecommendResponse>(EpharmJson.Options)!;
        Assert.Equal("confirmed", Assert.Single(response.Recommendations).Balance!.Status);
        Assert.Equal("epharm", response.Recommendations[0].Source);
        Assert.Equal(2, response.Recommendations[0].Balance!.PharmacyAvailableQty);
        var order = steps[13]!["response"]!.Deserialize<FulfillmentOrder>(EpharmJson.Options)!;
        Assert.True(order.IsPickupCash);
        Assert.False(FulfillmentRules.CanIssue(order, "123456", cashCollected: false));
        Assert.True(FulfillmentRules.CanIssue(order, "123456", cashCollected: true));
    }

    [Theory]
    [InlineData("1.0.57")]
    [InlineData("1.0.66")]
    public async Task UnauthorizedLegacyCredentialFailsSafeWithoutHumanLogin(string version)
    {
        var steps = Fixture(version)["steps"]!.AsArray();
        await using var server = new LoopbackServer(Enumerable.Repeat((HttpStatusCode.Unauthorized, (JsonNode)JsonNode.Parse("{\"code\":\"UNAUTHORIZED\"}")!), 3));
        dynamic api = Api(version, server.Origin);
        using var disposable = (IDisposable)api;
        var ct = TestContext.Current.CancellationToken;
        Assert.Null((object?)await api.RecommendAsync((dynamic)Model(version, "RecommendRequest", steps[0]!["body"]!), ct));
        Assert.False((bool)await api.PostSaleAsync((dynamic)Model(version, "SaleReport", steps[3]!["body"]!), ct));
        Assert.False((bool)await api.MarkShownAsync("rec_synthetic_1", DateTimeOffset.UtcNow, ct));
        await server.Completion.WaitAsync(TimeSpan.FromSeconds(10), ct);
        Assert.All(server.Requests, request => Assert.False(request.Headers.ContainsKey("Authorization")));
    }

    [Theory]
    [InlineData("1.0.57", false)]
    [InlineData("1.0.66", true)]
    public async Task TaskAvailabilityDifferenceIsPreservedInsteadOfClaimingLegacyFeatureParity(string version, bool throws)
    {
        var fixture = Fixture(version);
        await using var server = new LoopbackServer([(HttpStatusCode.OK, fixture["taskUnavailable"]!)]);
        dynamic api = Api(version, server.Origin);
        using var disposable = (IDisposable)api;
        var ct = TestContext.Current.CancellationToken;
        if (throws)
            await Assert.ThrowsAsync<V66.MerchTaskUnavailableException>(async () =>
                await api.GetActiveMerchTaskAsync("pharmacy-synthetic-1", "SYNTHETIC-POS-01", ct));
        else
            Assert.Null((object?)await api.GetActiveMerchTaskAsync("pharmacy-synthetic-1", ct));
    }

    [Theory]
    [InlineData("1.0.57")]
    [InlineData("1.0.66")]
    public async Task HistoricalQrAcknowledgementConflatesUnavailableAndRejection(string version)
    {
        var fixture = Fixture(version);
        await using var server = new LoopbackServer([(HttpStatusCode.OK, fixture["taskShownUnavailable"]!)]);
        dynamic api = Api(version, server.Origin);
        using var disposable = (IDisposable)api;
        Assert.False((bool)await api.AcknowledgeMerchTaskAsync("11111111-2222-4333-8444-555555555555",
            "pharmacy-synthetic-1", "SYNTHETIC-POS-01", "synthetic-token", TestContext.Current.CancellationToken));
    }

    [Theory]
    [InlineData("1.0.57")]
    [InlineData("1.0.66")]
    public async Task HistoricalTaskListReturnsPastDueTaskAndKeepsFragmentLinksIntact(string version)
    {
        var fixture = Fixture(version);
        await using var server = new LoopbackServer([
            (HttpStatusCode.OK, (JsonNode)new JsonObject { ["available"] = true, ["task"] = fixture["taskPastDue"]!.DeepClone() }),
            (HttpStatusCode.OK, (JsonNode)new JsonObject { ["available"] = true, ["task"] = fixture["fragmentTask"]!.DeepClone() }),
        ]);
        dynamic api = Api(version, server.Origin);
        using var disposable = (IDisposable)api;
        dynamic kiosk = Kiosk(version, api, "pharmacy-synthetic-1", "SYNTHETIC-POS-01");
        using var disposeKiosk = (IDisposable)kiosk;
        dynamic expired = await kiosk.ListAsync(TestContext.Current.CancellationToken);
        Assert.Equal(1, (int)expired.Count); // This is a legacy limitation, not a desired server behavior.
        dynamic fragment = await kiosk.ListAsync(TestContext.Current.CancellationToken);
        Assert.Equal(fixture["fragmentTask"]!["publicUrl"]!.GetValue<string>(), (string)fragment[0].QrUrl);
    }

    [Theory]
    [InlineData("1.0.57", true)]
    [InlineData("1.0.66", false)]
    public void FulfillmentLegacyClientRulesCannotReplaceCurrentServerPaymentGuards(string version, bool oldPermissiveRules)
    {
        var fixture = Fixture(version);
        dynamic card = Model(version, "FulfillmentOrder", fixture["fulfillmentNonCash"]!);
        dynamic demo = Model(version, "FulfillmentOrder", fixture["fulfillmentDemo"]!);
        var rules = LegacyType(version, "Models.Posm", "FulfillmentRules").GetMethod("CanIssue")!;
        Assert.Equal(oldPermissiveRules, (bool)rules.Invoke(null, [(object)card, "123456", true])!);
        Assert.Equal(oldPermissiveRules, (bool)rules.Invoke(null, [(object)demo, "123456", false])!);
        Assert.False(FulfillmentRules.CanIssue(fixture["fulfillmentNonCash"]!.Deserialize<FulfillmentOrder>(EpharmJson.Options)!, "123456", true));
    }

    [Theory]
    [InlineData("1.0.57")]
    [InlineData("1.0.66")]
    public void HistoricalSignedManifestCanonicalBytesAndPinnedVerificationRemainCompatible(string version)
    {
        var current = Fixture(version)["steps"]![6]!["response"]!.Deserialize<AppVersionInfo>(EpharmJson.Options)!;
        using var signer = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        current.ManifestSignature = Convert.ToBase64String(signer.SignData(Encoding.UTF8.GetBytes(UpdateManifestVerifier.Canonical(current)), HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence));
        var pin = Convert.ToBase64String(signer.ExportSubjectPublicKeyInfo());
        var legacy = Model(version, "AppVersionInfo", Json(current));
        var verifier = LegacyType(version, "Services", "UpdateManifestVerifier");
        var canonical = verifier.GetMethod("Canonical", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static)!;
        var verify = verifier.GetMethod("Verify")!;
        Assert.Equal(UpdateManifestVerifier.Canonical(current), (string)canonical.Invoke(null, [legacy])!);
        Assert.True((bool)verify.Invoke(null, [legacy, pin])!);
        Assert.False((bool)verify.Invoke(null, [legacy, ""])!);
        foreach (var field in new[] { "Url", "Sha256", "Version", "Platform", "Mandatory" })
        {
            var tampered = Model(version, "AppVersionInfo", Json(current));
            var property = tampered.GetType().GetProperty(field)!;
            property.SetValue(tampered, field == "Mandatory" ? true : "tampered");
            Assert.False((bool)verify.Invoke(null, [tampered, pin])!);
        }
    }

    [Theory]
    [InlineData("1.0.57")]
    [InlineData("1.0.66")]
    public void HistoricalOutboxPayloadSurvivesCurrentQueueReopenAndIdempotentEnqueue(string version)
    {
        var root = Path.Combine(Path.GetTempPath(), "synthetic-legacy-posm-" + Guid.NewGuid().ToString("N"));
        var payload = JsonSerializer.Serialize(Model(version, "SaleReport", Fixture(version)["steps"]![3]!["body"]!), EpharmJson.Options);
        try
        {
            var path = Path.Combine(root, "outbox.db");
            var outbox = new OfflineOutbox(path);
            outbox.Enqueue("sale-synthetic-1", "sale", payload);
            outbox.Enqueue("sale-synthetic-1", "sale", payload);
            var reopened = new OfflineOutbox(path);
            var pending = Assert.Single(reopened.DequeueReady());
            Assert.Equal("sale", pending.Kind);
            Assert.Equal("sale-synthetic-1", JsonSerializer.Deserialize<SaleReport>(pending.Payload, EpharmJson.Options)!.SaleId);
            reopened.Reschedule(pending.Id, pending.Attempts);
            reopened.MarkAllDue();
            Assert.Single(new OfflineOutbox(path).DequeueReady());
            reopened.Remove(pending.Id);
            Assert.Empty(new OfflineOutbox(path).DequeueReady());
        }
        finally
        {
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            if (Directory.Exists(root)) Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void PublicOriginAndNoMandatoryRpsRemainExplicitBoundaries()
    {
        var config = new CustomerDisplay.Config.EpharmConfig { BackendBaseUrl = Origin };
        Assert.Equal(new Uri(Origin), Assert.Single(config.GetBackendBaseUris()));
        Assert.False(CustomerDisplay.Config.EpharmConfig.StaffAuthenticationReleaseEnabled);
        Assert.Equal(Origin, Fixture("1.0.57")["canonicalPublicOrigin"]!.GetValue<string>());
        Assert.Equal(Origin, Fixture("1.0.66")["canonicalPublicOrigin"]!.GetValue<string>());
    }

    private static JsonNode Fixture(string version, [CallerFilePath] string file = "") =>
        JsonNode.Parse(File.ReadAllText(Path.Combine(Path.GetDirectoryName(file)!, "Fixtures", "LegacyPosm", version == "1.0.57" ? "V1057" : "V1066", "wire.json")))!;

    private static Type LegacyType(string version, string area, string name) =>
        typeof(LegacyPosmCompatibilityTests).Assembly.GetType($"CustomerDisplay.Core.Tests.Legacy.{(version == "1.0.57" ? "V1057" : "V1066")}.{area}.{name}", throwOnError: true)!;

    private static object Model(string version, string name, JsonNode node) =>
        JsonSerializer.Deserialize(node.ToJsonString(), LegacyType(version, "Models.Posm", name), EpharmJson.Options)!;

    private static JsonNode Json(object value) => JsonSerializer.SerializeToNode(value, value.GetType(), EpharmJson.Options)!;

    private static object Config(string version, string origin) => version == "1.0.57"
        ? new Config57 { BackendBaseUrl = origin, DeviceKey = "synthetic-posm-device-token-for-1.0.57-only" }
        : new Config66 { BackendBaseUrl = origin, DeviceKey = "synthetic-posm-device-token-for-1.0.66-only" };

    private static object Api(string version, string origin) => version == "1.0.57"
        ? new V57.EpharmApiClient((Config57)Config(version, origin))
        : new V66.EpharmApiClient((Config66)Config(version, origin));

    private static object Kiosk(string version, object api, string pharmacy, string device) => version == "1.0.57"
        ? new V57.TaskKioskClient((V57.EpharmApiClient)api, pharmacy, device)
        : new V66.TaskKioskClient((V66.EpharmApiClient)api, pharmacy, device);

    private static object Fulfillment(string version, string origin) => version == "1.0.57"
        ? new V57.FulfillmentClient((Config57)Config(version, origin), null)
        : new V66.FulfillmentClient((Config66)Config(version, origin), null);

    private sealed record CapturedRequest(string Method, string Path, Dictionary<string, string?> Headers, JsonNode? Body);

    // Real historical HttpClient code talks only to this in-process loopback listener.
    private sealed class LoopbackServer : IAsyncDisposable
    {
        private readonly HttpListener _listener = new();
        private readonly CancellationTokenSource _stop = new();
        public string Origin { get; }
        public List<CapturedRequest> Requests { get; } = [];
        public Task Completion { get; }

        public LoopbackServer(IEnumerable<(HttpStatusCode Status, JsonNode Body)> replies)
        {
            var socket = new TcpListener(IPAddress.Loopback, 0);
            socket.Start();
            var port = ((IPEndPoint)socket.LocalEndpoint).Port;
            socket.Stop();
            Origin = $"http://127.0.0.1:{port}/";
            _listener.Prefixes.Add(Origin);
            _listener.Start();
            Completion = Serve(replies.ToArray());
        }

        private async Task Serve((HttpStatusCode Status, JsonNode Body)[] replies)
        {
            foreach (var reply in replies)
            {
                var context = await _listener.GetContextAsync().WaitAsync(_stop.Token);
                using var reader = new StreamReader(context.Request.InputStream);
                var raw = await reader.ReadToEndAsync(_stop.Token);
                var headers = context.Request.Headers.AllKeys.Where(key => key != null)
                    .ToDictionary(key => key!, key => context.Request.Headers[key!], StringComparer.OrdinalIgnoreCase);
                Requests.Add(new(context.Request.HttpMethod, context.Request.RawUrl!, headers, raw.Length == 0 ? null : JsonNode.Parse(raw)));
                var bytes = Encoding.UTF8.GetBytes(reply.Body.ToJsonString());
                context.Response.StatusCode = (int)reply.Status;
                context.Response.ContentType = "application/json";
                context.Response.ContentLength64 = bytes.Length;
                await context.Response.OutputStream.WriteAsync(bytes, _stop.Token);
                context.Response.Close();
            }
        }

        public async ValueTask DisposeAsync()
        {
            await _stop.CancelAsync();
            _listener.Close();
            try { await Completion; }
            catch (Exception error) when (error is OperationCanceledException or HttpListenerException or ObjectDisposedException) { }
            _stop.Dispose();
        }
    }
}
