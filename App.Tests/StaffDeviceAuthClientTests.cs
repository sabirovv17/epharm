using System.Net;
using System.Text;
using System.Text.Json;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StaffDeviceAuthClientTests
{
    private static readonly StaffDeviceContext Device = new("42:639295055776695955");
    private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
    private static StaffAuthClient Client(HttpMessageHandler handler) => new(new EpharmConfig {
        BackendBaseUrl = "https://staff.invalid", BackendFallbackBaseUrls = [], DeviceId = "device-1", DeviceKey = "test-device-key",
    }, handler);
    private static HttpResponseMessage Reply(object body, HttpStatusCode status = HttpStatusCode.OK) => new(status) {
        Content = new StringContent(JsonSerializer.Serialize(body, Json), Encoding.UTF8, "application/json"),
    };
    private static void DeviceHeaders(HttpRequestMessage request)
    {
        Assert.Equal("test-device-key", request.Headers.GetValues("X-Posm-Key").Single());
        Assert.Equal("device-1", request.Headers.GetValues("X-Device-Id").Single());
        Assert.Null(request.Headers.Authorization); Assert.Empty(request.RequestUri!.Query);
    }
    private static object Auth(StaffDeviceContext deviceContext) => new {
        status = "authenticated", sessionToken = "opaque-staff-token", expiresAt = DateTimeOffset.UtcNow.AddHours(1),
        pharmacist = new StaffIdentity("p1", "Synthetic Person"), deviceContext,
    };

    [Fact]
    public async Task DeviceLoginWorksWithoutSourceIdsAndUsesOnlyTheNewRoute()
    {
        using var client = Client(new Handler(async (request, ct) => {
            DeviceHeaders(request); Assert.Equal("/api/posm/staff/device/login", request.RequestUri!.AbsolutePath);
            Assert.False(request.Headers.Contains("X-Staff-Session"));
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
            Assert.Equal("000101500011", body.RootElement.GetProperty("iin").GetString());
            Assert.Equal("A4x!", body.RootElement.GetProperty("password").GetString());
            var context = body.RootElement.GetProperty("deviceContext");
            Assert.Equal(Device.ProcessInstanceId, context.GetProperty("processInstanceId").GetString());
            Assert.Equal(JsonValueKind.Null, context.GetProperty("standardNContext").ValueKind);
            Assert.False(body.RootElement.TryGetProperty("context", out _));
            return Reply(Auth(Device));
        }));
        var response = await client.DeviceLoginAsync("000101500011", "A4x!", Device, TestContext.Current.CancellationToken);
        Assert.Equal(Device, response.DeviceContext); Assert.Null(response.Context); Assert.Equal("authenticated", response.Status);
    }

    [Fact]
    public async Task DeviceRegistrationRetainsRealSmsAndFourCharacterPasswordWithoutSyntheticSourceContext()
    {
        using var client = Client(new Handler(async (request, ct) => {
            DeviceHeaders(request); Assert.Equal("/api/posm/staff/device/register", request.RequestUri!.AbsolutePath);
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
            Assert.Equal("challenge", body.RootElement.GetProperty("challengeId").GetString());
            Assert.Equal("1234", body.RootElement.GetProperty("code").GetString());
            Assert.Equal("Synthetic Person", body.RootElement.GetProperty("name").GetString());
            Assert.Equal("+77000000000", body.RootElement.GetProperty("phone").GetString());
            Assert.Equal("A4x!", body.RootElement.GetProperty("password").GetString());
            Assert.Equal(JsonValueKind.Null, body.RootElement.GetProperty("deviceContext").GetProperty("standardNContext").ValueKind);
            return Reply(Auth(Device));
        }));
        var response = await client.DeviceRegisterAsync("challenge", "1234", "000101500011", "+77000000000", "Synthetic Person", "A4x!", Device, TestContext.Current.CancellationToken);
        Assert.Equal(Device, response.DeviceContext); Assert.Null(response.Context); Assert.NotNull(response.SessionToken);
    }

    [Fact]
    public async Task DirectRegistrationUsesOnlyTheExplicitRouteAndNoOtpOrVerificationClaim()
    {
        var calls = 0;
        using var client = Client(new Handler(async (request, ct) => {
            calls++; DeviceHeaders(request); Assert.Equal("/api/posm/staff/device/register-without-sms", request.RequestUri!.AbsolutePath);
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
            Assert.Equal(new[] { "deviceContext", "iin", "name", "password", "phone" }, body.RootElement.EnumerateObject().Select(x => x.Name).Order().ToArray());
            Assert.Equal("A4x!", body.RootElement.GetProperty("password").GetString());
            Assert.Equal("+77000000000", body.RootElement.GetProperty("phone").GetString());
            Assert.Equal(JsonValueKind.Null, body.RootElement.GetProperty("deviceContext").GetProperty("standardNContext").ValueKind);
            return Reply(Auth(Device));
        }));
        var response = await client.DeviceRegisterWithoutSmsAsync("000101500011", "+77000000000", "Synthetic Person", "A4x!", Device, TestContext.Current.CancellationToken);
        Assert.Equal(1, calls); Assert.Equal("authenticated", response.Status); Assert.Equal(Device, response.DeviceContext);
    }

    [Theory]
    [InlineData(403, "FORBIDDEN")]
    [InlineData(404, "UNAVAILABLE")]
    [InlineData(429, "RATE_LIMITED")]
    [InlineData(503, "UNAVAILABLE")]
    public async Task DirectRegistrationIsNeverReplayedAsSmsRegistrationOrRecovery(int status, string code)
    {
        var calls = 0;
        using var client = Client(new Handler((request, _) => {
            calls++; Assert.Equal("/api/posm/staff/device/register-without-sms", request.RequestUri!.AbsolutePath);
            return Task.FromResult(Reply(new { }, (HttpStatusCode)status));
        }));
        var error = await Assert.ThrowsAsync<StaffApiException>(() => client.DeviceRegisterWithoutSmsAsync("000101500011", "+77000000000", "Synthetic Person", "A4x!", Device, TestContext.Current.CancellationToken));
        Assert.Equal(code, error.Code); Assert.Equal(1, calls);
    }

    [Theory]
    [InlineData("", true, true)]
    [InlineData(",\"registrationSmsRequired\":true", true, true)]
    [InlineData(",\"registrationSmsRequired\":false", false, true)]
    [InlineData(",\"registrationSmsRequired\":null", true, false)]
    [InlineData(",\"registrationSmsRequired\":\"false\"", true, false)]
    public async Task OnlyExplicitValidFalseCapabilityCanDisableRegistrationSms(string suffix, bool required, bool enabled)
    {
        using var client = Client(new Handler((_, _) => Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) {
            Content = new StringContent("{\"enabled\":true,\"protocolVersion\":2,\"nameFallbackSupported\":true,\"deviceEmployeeAuthVersion\":1" + suffix + "}"),
        })));
        var capability = await client.CheckCapabilitiesAsync(TestContext.Current.CancellationToken);
        Assert.Equal(required, capability.RegistrationSmsRequired); Assert.Equal(enabled, capability.Enabled);
    }

    [Fact]
    public async Task FailedCapabilityNeverAuthorizesDirectRegistration()
    {
        using var client = Client(new Handler((_, _) => Task.FromResult(Reply(new { }, HttpStatusCode.ServiceUnavailable))));
        var capability = await client.CheckCapabilitiesAsync(TestContext.Current.CancellationToken);
        Assert.False(capability.Enabled); Assert.True(capability.RegistrationSmsRequired);
    }

    [Theory]
    [InlineData("http503")]
    [InlineData("malformed")]
    [InlineData("network")]
    [InlineData("timeout")]
    public async Task AFailedHttpRefreshCannotChangeAKnownNoSmsPolicyOrItsPendingGeneration(string failure)
    {
        var calls = 0;
        using var client = Client(new Handler((request, _) => {
            Assert.Equal(HttpMethod.Get, request.Method); Assert.Equal("/api/posm/staff/capabilities", request.RequestUri!.AbsolutePath);
            if (++calls == 1) return Task.FromResult(Reply(new {
                enabled = true, protocolVersion = 2, nameFallbackSupported = true, deviceEmployeeAuthVersion = 1,
                registrationSmsRequired = false,
            }));
            return failure switch {
                "network" => throw new HttpRequestException("Synthetic transport failure"),
                "timeout" => throw new OperationCanceledException(),
                "malformed" => Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("not-json") }),
                _ => Task.FromResult(Reply(new { }, HttpStatusCode.ServiceUnavailable)),
            };
        }));
        var policy = new StaffRegistrationPolicy();
        var first = await client.CheckCapabilitiesAsync(TestContext.Current.CancellationToken);
        Assert.True(first.Enabled); Assert.False(first.RegistrationSmsRequired);
        policy.SetSmsRequired(StaffAuthDiagnostics.RegistrationPolicy(first)!.Value); var pending = policy.Generation;
        var second = await client.CheckCapabilitiesAsync(TestContext.Current.CancellationToken);
        Assert.False(second.Enabled);
        if (StaffAuthDiagnostics.RegistrationPolicy(second) is { } required) policy.SetSmsRequired(required);
        Assert.False(policy.SmsRequired); Assert.True(policy.IsCurrent(pending)); Assert.Equal(2, calls);
    }

    [Fact]
    public async Task DeviceValidationPostsTheOptionalActualSourceAndCarriesGrantOnlyInHeader()
    {
        var context = Device with { StandardNContext = new("user-1", "session-1", "ws-1", Device.ProcessInstanceId,
            "000101500011", "available") };
        using var client = Client(new Handler(async (request, ct) => {
            DeviceHeaders(request); Assert.Equal("/api/posm/staff/device/session/check", request.RequestUri!.AbsolutePath);
            Assert.Equal("opaque-staff-token", request.Headers.GetValues("X-Staff-Session").Single());
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
            Assert.Equal(Device.ProcessInstanceId, body.RootElement.GetProperty("processInstanceId").GetString());
            Assert.Equal("000101500011", body.RootElement.GetProperty("standardNContext").GetProperty("standardNIin").GetString());
            Assert.False(body.RootElement.TryGetProperty("sessionToken", out _));
            return Reply(Auth(context));
        }));
        var response = await client.ValidateDeviceAsync("opaque-staff-token", context, TestContext.Current.CancellationToken);
        Assert.Equal(context, response.DeviceContext);
    }

    [Fact]
    public async Task DeviceLogoutUsesTheNewRouteAndNoPersonalBodyOrQuery()
    {
        using var client = Client(new Handler((request, _) => {
            DeviceHeaders(request); Assert.Equal("/api/posm/staff/device/logout", request.RequestUri!.AbsolutePath);
            Assert.Equal("opaque-staff-token", request.Headers.GetValues("X-Staff-Session").Single()); Assert.Null(request.Content);
            return Task.FromResult(Reply(new { revoked = true }));
        }));
        await client.DeviceLogoutAsync("opaque-staff-token", TestContext.Current.CancellationToken);
    }

    [Theory]
    [InlineData(404, "UNAVAILABLE")]
    [InlineData(401, "INVALID_CREDENTIALS")]
    [InlineData(503, "UNAVAILABLE")]
    public async Task DeviceMutationsAreNotReplayedThroughLegacyRoutesOrFallbackHosts(int status, string code)
    {
        var calls = 0;
        using var client = Client(new Handler((request, _) => {
            calls++; Assert.Equal("/api/posm/staff/device/login", request.RequestUri!.AbsolutePath);
            return Task.FromResult(Reply(new { privateServerMessage = "must never escape" }, (HttpStatusCode)status));
        }));
        var error = await Assert.ThrowsAsync<StaffApiException>(() => client.DeviceLoginAsync("000101500011", "A4x!", Device, TestContext.Current.CancellationToken));
        Assert.Equal(code, error.Code); Assert.Equal(1, calls); Assert.DoesNotContain("must never escape", error.Message);
    }

    [Theory]
    [InlineData(0, false)]
    [InlineData(1, true)]
    [InlineData(2, false)]
    public async Task NewClientRequiresAnExplicitCompatibleDeviceLoginCapability(int version, bool allowed)
    {
        using var client = Client(new Handler((_, _) => Task.FromResult(Reply(new {
            enabled = true, protocolVersion = 2, nameFallbackSupported = true, deviceEmployeeAuthVersion = version,
        }))));
        Assert.Equal(allowed, (await client.CheckCapabilitiesAsync(TestContext.Current.CancellationToken)).Enabled);
    }

    private sealed class Handler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) => send(request, ct);
    }
}
