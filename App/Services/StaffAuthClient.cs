using System;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services;

public sealed class StaffApiException(string code) : Exception("Staff authentication request failed.")
{
    public string Code { get; } = code;
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

    public async Task<bool> IsEnabledAsync(CancellationToken ct)
    {
        using var response = await _http.GetAsync("/api/posm/staff/capabilities", ct).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) return false;
        var value = await response.Content.ReadFromJsonAsync<StaffCapabilities>(Json, ct).ConfigureAwait(false);
        return value is { Enabled: true, ProtocolVersion: 1 };
    }

    public Task<StaffAuthResponse> LoginAsync(string iin, string password, StaffAuthContext context, CancellationToken ct) =>
        PostAsync<StaffAuthResponse>("login", new { iin, password, context }, ct);
    public Task<StaffOtpResponse> RequestOtpAsync(string purpose, string iin, string phone, CancellationToken ct) =>
        PostAsync<StaffOtpResponse>("otp/request", new { purpose, iin, phone }, ct);
    public Task<StaffRegistrationResponse> RegisterAsync(string challengeId, string code, string iin, string phone, string name, string password, CancellationToken ct) =>
        PostAsync<StaffRegistrationResponse>("register", new { challengeId, code, iin, phone, name, password }, ct);
    public Task<StaffPasswordChanged> ChangePasswordAsync(string purpose, string challengeId, string code, string iin, string phone, string password, CancellationToken ct) =>
        PostAsync<StaffPasswordChanged>(purpose == "activate" ? "password/activate" : "password/reset", new { challengeId, code, iin, phone, password }, ct);

    public async Task<StaffAuthResponse> ValidateAsync(string token, StaffAuthContext context, CancellationToken ct)
    {
        var query = "standardNUserId=" + Uri.EscapeDataString(context.StandardNUserId) +
            "&standardNSessionId=" + Uri.EscapeDataString(context.StandardNSessionId) +
            "&workstationId=" + Uri.EscapeDataString(context.WorkstationId) +
            "&processInstanceId=" + Uri.EscapeDataString(context.ProcessInstanceId);
        using var request = new HttpRequestMessage(HttpMethod.Get, "/api/posm/staff/session?" + query);
        request.Headers.Add("X-Staff-Session", token);
        using var response = await _http.SendAsync(request, ct).ConfigureAwait(false);
        return await ReadAsync<StaffAuthResponse>(response, ct).ConfigureAwait(false);
    }

    public async Task LogoutAsync(string token, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, "/api/posm/staff/logout");
        request.Headers.Add("X-Staff-Session", token);
        using var response = await _http.SendAsync(request, ct).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) throw new StaffApiException("UNAVAILABLE");
    }

    private async Task<T> PostAsync<T>(string path, object body, CancellationToken ct)
    {
        using var response = await _http.PostAsJsonAsync("/api/posm/staff/" + path, body, Json, ct).ConfigureAwait(false);
        return await ReadAsync<T>(response, ct).ConfigureAwait(false);
    }
    private static async Task<T> ReadAsync<T>(HttpResponseMessage response, CancellationToken ct)
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
            throw new StaffApiException(code);
        }
        return await response.Content.ReadFromJsonAsync<T>(Json, ct).ConfigureAwait(false)
            ?? throw new StaffApiException("INVALID_RESPONSE");
    }
    public void Dispose() => _http.Dispose();
}
