using System.Net;
using System.Text.Json;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StaffAuthClientTests
{
    private static readonly StaffAuthContext Context = new("user-1", "session-2", "ws-3", "44:50000");
    private static StaffAuthClient Client(HttpMessageHandler handler) => new(new EpharmConfig {
        BackendBaseUrl = "https://backend.example", DeviceId = "device-1", DeviceKey = "test-only-device-key" }, handler);

    [Fact]
    public void OlderStaffContextDefaultsToUnavailableIinInsteadOfInventingAMatch()
    {
        var context = JsonSerializer.Deserialize<StaffAuthContext>("""
            {"StandardNUserId":"user-1","StandardNSessionId":"session-2","WorkstationId":"ws-3","ProcessInstanceId":"44:50000"}
            """)!;
        Assert.Equal(Context, context);
        Assert.Null(context.StandardNIin);
        Assert.Equal("unavailable", context.StandardNIinStatus);
        Assert.Null(context.StandardNFullName);
        Assert.Equal("unavailable", context.StandardNFullNameStatus);
    }

    [Theory]
    [InlineData("{\"enabled\":true,\"protocolVersion\":2,\"nameFallbackSupported\":true,\"deviceEmployeeAuthVersion\":1}", true)]
    [InlineData("{\"enabled\":true,\"protocolVersion\":2}", false)]
    [InlineData("{\"enabled\":true,\"protocolVersion\":2,\"nameFallbackSupported\":false}", false)]
    [InlineData("{\"enabled\":false,\"protocolVersion\":2}", false)]
    [InlineData("{\"enabled\":true,\"protocolVersion\":1}", false)]
    [InlineData("{\"enabled\":true,\"protocolVersion\":3}", false)]
    [InlineData("{}", false)]
    public async Task SignInRequiresExplicitSupportedBackendCapability(string json, bool enabled)
    {
        using var client = Client(new Handler((_, _) => System.Threading.Tasks.Task.FromResult(Response(json))));
        Assert.Equal(enabled, await client.IsEnabledAsync(TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task RollbackToAnOldProtocolTwoServerDisablesTheWholeStaffFlow()
    {
        var calls = 0;
        using var client = Client(new Handler((_, _) => Task.FromResult(Response(++calls == 1
            ? "{\"enabled\":true,\"protocolVersion\":2,\"nameFallbackSupported\":true,\"deviceEmployeeAuthVersion\":1}"
            : "{\"enabled\":true,\"protocolVersion\":2}"))));
        Assert.True((await client.CheckCapabilitiesAsync(TestContext.Current.CancellationToken)).Enabled);
        Assert.Equal(new(false, "unsupported_protocol"), await client.CheckCapabilitiesAsync(TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task ExactNameObservationIsBoundToThePostContextWithoutPersonalQuery()
    {
        var context = Context with { StandardNIinStatus = "missing", StandardNFullName = "Иванов Иван", StandardNFullNameStatus = "available" };
        using var client = Client(new Handler(async (request, ct) => {
            Assert.Empty(request.RequestUri!.Query);
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
            var sent = body.RootElement.GetProperty("context");
            Assert.Equal(context.StandardNFullName, sent.GetProperty("standardNFullName").GetString());
            Assert.Equal("available", sent.GetProperty("standardNFullNameStatus").GetString());
            return Response(JsonSerializer.Serialize(new { status = "pending", context }, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase }));
        }));
        Assert.Equal(context, (await client.LoginAsync("000101500011", "F4x!", context, TestContext.Current.CancellationToken)).Context);
    }

    [Fact]
    public async Task MissingEndpointDoesNotEnableAnOldBackend()
    {
        using var client = Client(new Handler((_, _) => System.Threading.Tasks.Task.FromResult(Response("", HttpStatusCode.NotFound))));
        Assert.False(await client.IsEnabledAsync(TestContext.Current.CancellationToken));
    }

    [Theory]
    [InlineData(401, "device_auth_required")]
    [InlineData(403, "forbidden")]
    [InlineData(404, "endpoint_unavailable")]
    [InlineData(503, "endpoint_unavailable")]
    public async Task CapabilityDiagnosticsNeverReadErrorBody(int status, string expected)
    {
        using var client = Client(new Handler((_, _) => Task.FromResult(Response("secret-phone-and-password", (HttpStatusCode)status))));
        var result = await client.CheckCapabilitiesAsync(TestContext.Current.CancellationToken);
        Assert.False(result.Enabled);
        Assert.Equal(expected, result.Status);
        Assert.DoesNotContain("secret", result.ToString());
    }

    [Fact]
    public async Task InvalidCapabilityJsonIsReportedWithoutLoggingItsContents()
    {
        using var client = Client(new Handler((_, _) => Task.FromResult(Response("not-json-secret"))));
        Assert.Equal(new(false, "endpoint_unavailable"), await client.CheckCapabilitiesAsync(TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task NetworkOrTlsFailureIsReportedWithoutExceptionDetails()
    {
        using var client = Client(new Handler((_, _) => throw new HttpRequestException("secret-endpoint-credentials")));
        Assert.Equal(new(false, "network_unavailable"), await client.CheckCapabilitiesAsync(TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task StoppingCapabilityPollRemainsCancellationNotDisabledService()
    {
        using var stop = new CancellationTokenSource();
        stop.Cancel();
        using var client = Client(new Handler((_, ct) => Task.FromCanceled<HttpResponseMessage>(ct)));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => client.CheckCapabilitiesAsync(stop.Token));
    }

    [Fact]
    public async Task LoginBindsDeviceAndExactObservedContextWithoutJwt()
    {
        using var client = Client(new Handler(async (request, ct) => {
            Assert.Equal("/api/posm/staff/login", request.RequestUri!.AbsolutePath);
            Assert.Equal("test-only-device-key", request.Headers.GetValues("X-Posm-Key").Single());
            Assert.Equal("device-1", request.Headers.GetValues("X-Device-Id").Single());
            Assert.Null(request.Headers.Authorization);
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
            Assert.Equal("000000000000", body.RootElement.GetProperty("iin").GetString());
            var context = body.RootElement.GetProperty("context");
            Assert.Equal("user-1", context.GetProperty("standardNUserId").GetString());
            Assert.Equal("44:50000", context.GetProperty("processInstanceId").GetString());
            return Response("{\"status\":\"pending\",\"sessionToken\":null,\"expiresAt\":null,\"pharmacist\":{\"id\":\"p1\",\"name\":\"Test\"},\"context\":{\"standardNUserId\":\"user-1\",\"standardNSessionId\":\"session-2\",\"workstationId\":\"ws-3\",\"processInstanceId\":\"44:50000\"}}");
        }));
        var result = await client.LoginAsync("000000000000", "test-only-password", Context, TestContext.Current.CancellationToken);
        Assert.Equal("pending", result.Status);
        Assert.Null(result.SessionToken);
        Assert.Equal(Context, result.Context);
    }

    [Fact]
    public async Task MissingCashSessionBlocksOnlyLoginLocallyNotRegistrationOrRecovery()
    {
        var calls = 0;
        using var client = Client(new Handler(async (request, ct) => {
            calls++;
            Assert.Equal("test-only-device-key", request.Headers.GetValues("X-Posm-Key").Single());
            using var document = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
            Assert.False(document.RootElement.TryGetProperty("context", out _));
            return request.RequestUri!.AbsolutePath switch {
                "/api/posm/staff/register" => Response("{\"status\":\"pending\"}"),
                "/api/posm/staff/password/reset" => Response("{\"changed\":true}"),
                _ => throw new InvalidOperationException("A login without observed context must never reach HTTP."),
            };
        }));
        var error = await Assert.ThrowsAsync<StaffApiException>(async () =>
            await client.LoginAsync("000000000000", "test-only-password", null, TestContext.Current.CancellationToken));
        Assert.Equal("SESSION_REQUIRED", error.Code);
        Assert.Equal(0, calls);
        Assert.Equal("pending", (await client.RegisterAsync("challenge", "1234", "000000000000", "+77000000000", "Test Pharmacist", "test-only-password", TestContext.Current.CancellationToken)).Status);
        Assert.True((await client.ChangePasswordAsync("reset", "challenge", "1234", "000000000000", "+77000000000", "test-only-password", TestContext.Current.CancellationToken)).Changed);
        Assert.Equal(2, calls);
    }

    [Fact]
    public async Task StaffGrantIsHeaderOnlyAndObservedIinUsesPostBodyWithoutPersonalQuery()
    {
        var observed = Context with { StandardNIin = "000101500011", StandardNIinStatus = "available" };
        using var client = Client(new Handler(async (request, ct) => {
            Assert.Equal(HttpMethod.Post, request.Method);
            Assert.Equal("/api/posm/staff/session/check", request.RequestUri!.AbsolutePath);
            Assert.Empty(request.RequestUri.Query);
            Assert.Equal("opaque-test-grant", request.Headers.GetValues("X-Staff-Session").Single());
            Assert.DoesNotContain("opaque-test-grant", request.RequestUri.ToString());
            Assert.DoesNotContain(observed.StandardNIin, request.RequestUri.ToString());
            Assert.Null(request.Headers.Authorization);
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
            Assert.Equal("user-1", body.RootElement.GetProperty("standardNUserId").GetString());
            Assert.Equal(observed.StandardNIin, body.RootElement.GetProperty("standardNIin").GetString());
            Assert.Equal("available", body.RootElement.GetProperty("standardNIinStatus").GetString());
            Assert.False(body.RootElement.TryGetProperty("sessionToken", out _));
            return Response("{\"status\":\"authenticated\",\"context\":{\"standardNUserId\":\"user-1\",\"standardNSessionId\":\"session-2\",\"workstationId\":\"ws-3\",\"processInstanceId\":\"44:50000\",\"standardNIin\":\"000101500011\",\"standardNIinStatus\":\"available\"}}");
        }));
        var result = await client.ValidateAsync("opaque-test-grant", observed, TestContext.Current.CancellationToken);
        Assert.Equal(observed, result.Context);
    }

    [Fact]
    public async Task OldBackendCannotTriggerAGetFallbackOrReplayOfSessionCheck()
    {
        var calls = 0;
        using var client = Client(new Handler((request, _) => {
            calls++;
            Assert.Equal(HttpMethod.Post, request.Method);
            Assert.Empty(request.RequestUri!.Query);
            return Task.FromResult(Response("", HttpStatusCode.NotFound));
        }));
        var error = await Assert.ThrowsAsync<StaffApiException>(() =>
            client.ValidateAsync("opaque-test-grant", Context, TestContext.Current.CancellationToken));
        Assert.Equal("UNAVAILABLE", error.Code);
        Assert.Equal(1, calls);
    }

    [Theory]
    [InlineData(400, "INVALID_DATA")]
    [InlineData(401, "INVALID_CREDENTIALS")]
    [InlineData(403, "FORBIDDEN")]
    [InlineData(409, "CONFLICT")]
    [InlineData(429, "RATE_LIMITED")]
    [InlineData(503, "UNAVAILABLE")]
    public async Task ErrorsDoNotExposeServerBodyOrPersonalData(int status, string code)
    {
        using var client = Client(new Handler((_, _) => System.Threading.Tasks.Task.FromResult(Response("{\"message\":\"secret-password-and-iin\"}", (HttpStatusCode)status))));
        var error = await Assert.ThrowsAsync<StaffApiException>(() => client.LoginAsync("000000000000", "test-only-password", Context, TestContext.Current.CancellationToken));
        Assert.Equal(code, error.Code);
        Assert.DoesNotContain("secret-password-and-iin", error.ToString());
    }

    [Fact]
    public async Task ClosingWindowCancellationDoesNotConvertToAuthenticationSuccess()
    {
        using var client = Client(new Handler(async (_, ct) => {
            await System.Threading.Tasks.Task.Delay(Timeout.Infinite, ct);
            return Response("{}");
        }));
        using var stop = new CancellationTokenSource();
        var pending = client.LoginAsync("000000000000", "test-only-password", Context, stop.Token);
        stop.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => pending);
    }

    [Fact]
    public async Task OtpIsPurposeBoundAndResetUsesChallengeAndCode()
    {
        var calls = 0;
        using var client = Client(new Handler(async (request, ct) => {
            using var document = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
            var body = document.RootElement;
            calls++;
            if (calls == 1) {
                Assert.Equal("reset", body.GetProperty("purpose").GetString());
                Assert.Equal("/api/posm/staff/otp/request", request.RequestUri!.AbsolutePath);
                return Response("{\"challengeId\":\"challenge-1\",\"ttlSeconds\":300,\"phoneMasked\":\"+7***\"}");
            }
            Assert.Equal("/api/posm/staff/password/reset", request.RequestUri!.AbsolutePath);
            Assert.Equal("challenge-1", body.GetProperty("challengeId").GetString());
            Assert.Equal("1234", body.GetProperty("code").GetString());
            return Response("{\"changed\":true}");
        }));
        var challenge = await client.RequestOtpAsync("reset", "000000000000", "+77000000000", TestContext.Current.CancellationToken);
        Assert.True((await client.ChangePasswordAsync("reset", challenge.ChallengeId, "1234", "000000000000", "+77000000000", "test-only-password", TestContext.Current.CancellationToken)).Changed);
    }

    [Fact]
    public async Task NewStaffRegistrationSendsRequiredFieldsAndDoesNotCreateLocalGrant()
    {
        using var client = Client(new Handler(async (request, ct) => {
            Assert.Equal("/api/posm/staff/register", request.RequestUri!.AbsolutePath);
            using var document = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
            var body = document.RootElement;
            Assert.Equal("challenge-1", body.GetProperty("challengeId").GetString());
            Assert.Equal("1234", body.GetProperty("code").GetString());
            Assert.Equal("Test Pharmacist", body.GetProperty("name").GetString());
            Assert.Equal("+77000000000", body.GetProperty("phone").GetString());
            Assert.Equal("test-only-password", body.GetProperty("password").GetString());
            return Response("{\"status\":\"pending\"}");
        }));
        var response = await client.RegisterAsync("challenge-1", "1234", "000000000000", "+77000000000", "Test Pharmacist", "test-only-password", TestContext.Current.CancellationToken);
        Assert.Equal("pending", response.Status);
        Assert.Null(response.SessionToken);
        Assert.Null(response.ExpiresAt);
        Assert.Null(response.Pharmacist);
        Assert.Null(response.Context);
    }

    [Fact]
    public async Task ContextualRegistrationCarriesObservedIinAndReturnsSessionForAutoClose()
    {
        var observed = Context with { StandardNIin = "000101500011", StandardNIinStatus = "available" };
        var expires = DateTimeOffset.UtcNow.AddHours(8);
        using var client = Client(new Handler(async (request, ct) => {
            Assert.Equal(HttpMethod.Post, request.Method);
            Assert.Equal("/api/posm/staff/register", request.RequestUri!.AbsolutePath);
            Assert.Empty(request.RequestUri.Query);
            Assert.DoesNotContain(observed.StandardNIin, request.RequestUri.ToString());
            using var document = JsonDocument.Parse(await request.Content!.ReadAsStringAsync(ct));
            var body = document.RootElement;
            Assert.Equal("challenge-1", body.GetProperty("challengeId").GetString());
            Assert.Equal("1234", body.GetProperty("code").GetString());
            Assert.Equal("000101500011", body.GetProperty("iin").GetString());
            Assert.Equal("Test Pharmacist", body.GetProperty("name").GetString());
            Assert.Equal("test-only-password", body.GetProperty("password").GetString());
            var context = body.GetProperty("context");
            Assert.Equal("user-1", context.GetProperty("standardNUserId").GetString());
            Assert.Equal(observed.StandardNIin, context.GetProperty("standardNIin").GetString());
            Assert.Equal("available", context.GetProperty("standardNIinStatus").GetString());
            return Response(JsonSerializer.Serialize(new {
                status = "authenticated", sessionToken = "opaque-new-grant", expiresAt = expires,
                pharmacist = new { id = "person-1", name = "Test Pharmacist" }, context = observed,
            }, new JsonSerializerOptions { PropertyNamingPolicy = JsonNamingPolicy.CamelCase }));
        }));
        var response = await client.RegisterAsync("challenge-1", "1234", "000101500011", "+77000000000", "Test Pharmacist",
            "test-only-password", observed, TestContext.Current.CancellationToken);
        Assert.Equal("authenticated", response.Status);
        Assert.Equal("opaque-new-grant", response.SessionToken);
        Assert.Equal(expires, response.ExpiresAt);
        Assert.Equal(new StaffIdentity("person-1", "Test Pharmacist"), response.Pharmacist);
        Assert.Equal(observed, response.Context);
    }

    [Fact]
    public async Task InitialPasswordUsesSeparateActivationPurpose()
    {
        using var client = Client(new Handler((request, _) => {
            Assert.Equal("/api/posm/staff/password/activate", request.RequestUri!.AbsolutePath);
            return System.Threading.Tasks.Task.FromResult(Response("{\"changed\":true}"));
        }));
        Assert.True((await client.ChangePasswordAsync("activate", "challenge", "1234", "000000000000", "+77000000000", "test-only-password", TestContext.Current.CancellationToken)).Changed);
    }

    private static HttpResponseMessage Response(string json, HttpStatusCode status = HttpStatusCode.OK) => new(status) { Content = new StringContent(json) };
    private sealed class Handler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> send) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) => send(request, ct);
    }
}
