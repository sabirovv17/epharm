using System;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services;

public sealed class StaffApiException(string code, int? retryAfterSeconds = null, string? retryAction = null) : Exception("Staff authentication request failed.")
{
    public string Code { get; } = code;
    public int? RetryAfterSeconds { get; } = retryAfterSeconds;
    public string? RetryAction { get; } = retryAction;
}

/// <summary>Separate optional channel: human passwords and staff grants never enter POSM logs/outbox.</summary>
public sealed class StaffAuthClient : IDisposable
{
    private readonly HttpClient _http;
    private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = true };
    public StaffAuthClient(EpharmConfig config, HttpMessageHandler? handler = null)
    {
        var endpoints = config.GetBackendBaseUris();
        // Password/SMS operations can legitimately exceed the 2s media failover budget.
        // Never automatically replay these mutations or forward credentials through redirects.
        _http = new HttpClient(handler ?? new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false }) { BaseAddress = endpoints[0], Timeout = TimeSpan.FromSeconds(15) };
        _http.DefaultRequestHeaders.Add("X-Posm-Key", config.DeviceKey);
        _http.DefaultRequestHeaders.Add("X-Device-Id", config.ResolveDeviceId());
    }

    public async Task<bool> IsEnabledAsync(CancellationToken ct) => (await CheckCapabilitiesAsync(ct).ConfigureAwait(false)).Enabled;

    public async Task<StaffCapabilityCheck> CheckCapabilitiesAsync(CancellationToken ct)
    {
        try
        {
            using var response = await _http.GetAsync("/api/posm/staff/capabilities", ct).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode) return StaffAuthDiagnostics.CapabilityFailure(response.StatusCode);
            var value = await response.Content.ReadFromJsonAsync<StaffCapabilities>(Json, ct).ConfigureAwait(false);
            return StaffAuthDiagnostics.Capability(value);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (OperationCanceledException) { return new(false, "network_unavailable"); }
        catch (HttpRequestException) { return new(false, "network_unavailable"); }
        catch (JsonException) { return new(false, "endpoint_unavailable"); }
    }

    public Task<StaffAuthResponse> DeviceLoginAsync(string iin, string password, StaffDeviceContext context, CancellationToken ct) =>
        PostAsync<StaffAuthResponse>("device/login", new { iin, password, deviceContext = context }, ct);
    public Task<StaffRegistrationResponse> DeviceRegisterAsync(string challengeId, string code, string iin, string phone, string name, string password, StaffDeviceContext context, CancellationToken ct) =>
        PostAsync<StaffRegistrationResponse>("device/register", new { challengeId, code, iin, phone, name, password, deviceContext = context }, ct);

    public Task<StaffRegistrationResponse> DeviceRegisterWithoutSmsAsync(string iin, string phone, string name, string password, StaffDeviceContext context, CancellationToken ct) =>
        PostAsync<StaffRegistrationResponse>("device/register-without-sms", new { iin, phone, name, password, deviceContext = context }, ct);

    public async Task<StaffAuthResponse> ValidateDeviceAsync(string token, StaffDeviceContext context, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, "/api/posm/staff/device/session/check") {
            Content = JsonContent.Create(context, options: Json),
        };
        request.Headers.Add("X-Staff-Session", token);
        return await SendReadAsync<StaffAuthResponse>(request, ct).ConfigureAwait(false);
    }

    public async Task DeviceLogoutAsync(string token, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, "/api/posm/staff/device/logout");
        request.Headers.Add("X-Staff-Session", token);
        using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) throw new StaffApiException("UNAVAILABLE");
    }

    public Task<StaffAuthResponse> LoginAsync(string iin, string password, StaffAuthContext? context, CancellationToken ct)
    {
        if (context == null) throw new StaffApiException("SESSION_REQUIRED");
        return PostAsync<StaffAuthResponse>("login", new { iin, password, context }, ct);
    }
    public Task<StaffOtpResponse> RequestOtpAsync(string purpose, string iin, string phone, CancellationToken ct) =>
        PostAsync<StaffOtpResponse>("otp/request", new { purpose, iin, phone }, ct);
    public Task<StaffRegistrationResponse> RegisterAsync(string challengeId, string code, string iin, string phone, string name, string password, CancellationToken ct) =>
        PostAsync<StaffRegistrationResponse>("register", new { challengeId, code, iin, phone, name, password }, ct);
    public Task<StaffRegistrationResponse> RegisterAsync(string challengeId, string code, string iin, string phone, string name, string password, StaffAuthContext? context, CancellationToken ct) =>
        PostAsync<StaffRegistrationResponse>("register", new { challengeId, code, iin, phone, name, password, context }, ct);
    public Task<StaffPasswordChanged> ChangePasswordAsync(string purpose, string challengeId, string code, string iin, string phone, string password, CancellationToken ct) =>
        PostAsync<StaffPasswordChanged>(purpose == "activate" ? "password/activate" : "password/reset", new { challengeId, code, iin, phone, password }, ct);

    public async Task<StaffAuthResponse> ValidateAsync(string token, StaffAuthContext context, CancellationToken ct)
    {
        // Keep the observed IIN out of URLs and access logs. Do not fall back/replay on an old server.
        using var request = new HttpRequestMessage(HttpMethod.Post, "/api/posm/staff/session/check") {
            Content = JsonContent.Create(context, options: Json),
        };
        request.Headers.Add("X-Staff-Session", token);
        return await SendReadAsync<StaffAuthResponse>(request, ct).ConfigureAwait(false);
    }

    public async Task LogoutAsync(string token, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, "/api/posm/staff/logout");
        request.Headers.Add("X-Staff-Session", token);
        using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) throw new StaffApiException("UNAVAILABLE");
    }

    private async Task<T> PostAsync<T>(string path, object body, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, "/api/posm/staff/" + path) {
            Content = JsonContent.Create(body, options: Json),
        };
        return await SendReadAsync<T>(request, ct).ConfigureAwait(false);
    }
    private async Task<T> SendReadAsync<T>(HttpRequestMessage request, CancellationToken ct)
    {
        // Observe definitive HTTP denial before reading optional error fields. Keep
        // the same whole-operation deadline after switching to streamed headers.
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
        deadline.CancelAfter(TimeSpan.FromSeconds(15));
        using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token).ConfigureAwait(false);
        return await ReadAsync<T>(response, deadline.Token, ct).ConfigureAwait(false);
    }
    private static async Task<T> ReadAsync<T>(HttpResponseMessage response, CancellationToken ct, CancellationToken callerCancellation)
    {
        if (!response.IsSuccessStatusCode)
        {
            // Never forward raw messages/body to logs: they can contain personal identifiers.
            var code = response.StatusCode switch {
                System.Net.HttpStatusCode.Unauthorized => "INVALID_CREDENTIALS",
                System.Net.HttpStatusCode.Forbidden => "FORBIDDEN",
                System.Net.HttpStatusCode.TooManyRequests => "RATE_LIMITED",
                System.Net.HttpStatusCode.BadRequest => "INVALID_DATA",
                System.Net.HttpStatusCode.Conflict => "CONFLICT",
                _ => "UNAVAILABLE",
            };
            int? retryAfterSeconds = null;
            if (code == "RATE_LIMITED") {
                var retry = response.Headers.RetryAfter;
                var seconds = retry?.Delta?.TotalSeconds ?? (retry?.Date - DateTimeOffset.UtcNow)?.TotalSeconds;
                if (seconds is > 0 && double.IsFinite(seconds.Value))
                    retryAfterSeconds = (int)Math.Clamp(Math.Ceiling(seconds.Value), 1, 86400);
            }
            var retryAction = code == "RATE_LIMITED" ? await ReadRetryAction(response, ct, callerCancellation).ConfigureAwait(false) : null;
            if (code == "FORBIDDEN") {
                var assignment = await ReadOptionalMachineValue(response, "code", ct, callerCancellation).ConfigureAwait(false);
                if (assignment is "STAFF_PHARMACY_ASSIGNMENT_REQUIRED" or "STAFF_PHARMACY_MISMATCH") code = assignment;
            }
            throw new StaffApiException(code, retryAfterSeconds, retryAction);
        }
        return await response.Content.ReadFromJsonAsync<T>(Json, ct).ConfigureAwait(false)
            ?? throw new StaffApiException("INVALID_RESPONSE");
    }
    private static async Task<string?> ReadRetryAction(HttpResponseMessage response, CancellationToken ct, CancellationToken callerCancellation)
    {
        var action = await ReadOptionalMachineValue(response, "retryAction", ct, callerCancellation).ConfigureAwait(false);
        return action == "REQUEST_NEW_OTP" ? action : null;
    }
    private static async Task<string?> ReadOptionalMachineValue(HttpResponseMessage response, string property, CancellationToken ct, CancellationToken callerCancellation)
    {
        try { return await ReadBoundedMachineValue(response, property, ct).ConfigureAwait(false); }
        catch (OperationCanceledException) when (callerCancellation.IsCancellationRequested) { throw; }
        catch (Exception error) when (error is HttpRequestException or System.IO.IOException or OperationCanceledException) {
            callerCancellation.ThrowIfCancellationRequested();
            // Broken optional text cannot turn an already received 403 into a
            // recoverable transport outage or replace a known 429 with a generic error.
            return null;
        }
    }
    private static async Task<string?> ReadBoundedMachineValue(HttpResponseMessage response, string property, CancellationToken ct)
    {
        // Optional machine action only. Neither free-form messages nor personal body fields
        // enter diagnostics/UI; malformed, oversized or unknown actions keep generic guidance.
        if (response.Content.Headers.ContentLength is > 4096) return null;
        using var stream = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        var buffer = new byte[4097]; var count = 0;
        while (count < buffer.Length) {
            var read = await stream.ReadAsync(buffer.AsMemory(count, buffer.Length - count), ct).ConfigureAwait(false);
            if (read == 0) break;
            count += read;
        }
        if (count > 4096) return null;
        try {
            using var document = JsonDocument.Parse(buffer.AsMemory(0, count));
            return document.RootElement.ValueKind == JsonValueKind.Object &&
                document.RootElement.TryGetProperty(property, out var action) && action.ValueKind == JsonValueKind.String
                ? action.GetString() : null;
        }
        catch (JsonException) { return null; }
    }
    public void Dispose() => _http.Dispose();
}
