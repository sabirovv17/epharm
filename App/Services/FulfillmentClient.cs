using System;
using System.Net;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    internal sealed class FulfillmentApiResult<T>
    {
        public T? Value { get; init; }
        public HttpStatusCode? StatusCode { get; init; }
        public string? Message { get; init; }
        public bool IsSuccess => Value != null && StatusCode is >= HttpStatusCode.OK and < HttpStatusCode.MultipleChoices;
        public bool IsConflict => StatusCode == HttpStatusCode.Conflict;
        public bool IsUnauthorized => StatusCode == HttpStatusCode.Unauthorized;
        public bool IsTransportFailure => StatusCode == null;
    }

    internal sealed class FulfillmentClient : IDisposable
    {
        private readonly HttpClient _http;
        private readonly string _bootstrapKey;

        public FulfillmentClient(EpharmConfig config, Action<string>? log)
        {
            var endpoints = config.GetFulfillmentBaseUris();
            _http = new HttpClient(new BackendFailoverHandler(endpoints, log))
            {
                BaseAddress = endpoints[0],
                Timeout = TimeSpan.FromSeconds(12),
            };
            _bootstrapKey = config.DeviceKey;
        }

        public async Task<FulfillmentApiResult<RegisterFulfillmentDeviceResponse>> RegisterAsync(
            string deviceId,
            string pharmacyId,
            CancellationToken ct)
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, "/api/posm/fulfillment/devices/register")
            {
                Content = JsonContent.Create(new RegisterFulfillmentDeviceRequest
                {
                    DeviceId = deviceId,
                    PharmacyId = pharmacyId,
                }, options: EpharmJson.Options),
            };
            request.Headers.TryAddWithoutValidation("X-Posm-Key", _bootstrapKey);
            return await SendAsync<RegisterFulfillmentDeviceResponse>(request, ct).ConfigureAwait(false);
        }

        public Task<FulfillmentApiResult<FulfillmentOrderPage>> ListAsync(
            string token,
            string status,
            int offset,
            int limit,
            CancellationToken ct)
        {
            var path = "/api/posm/fulfillment/orders?status=" + Uri.EscapeDataString(status) +
                "&offset=" + Math.Max(0, offset) + "&limit=" + Math.Clamp(limit, 1, 50);
            return SendWithDeviceAsync<FulfillmentOrderPage>(HttpMethod.Get, path, token, null, ct);
        }

        public Task<FulfillmentApiResult<FulfillmentOrder>> GetAsync(string token, string orderId, CancellationToken ct) =>
            SendWithDeviceAsync<FulfillmentOrder>(
                HttpMethod.Get,
                "/api/posm/fulfillment/orders/" + Uri.EscapeDataString(orderId),
                token,
                null,
                ct);

        public Task<FulfillmentApiResult<FulfillmentOrder>> ActAsync(
            string token,
            string orderId,
            FulfillmentActionRequest action,
            CancellationToken ct) =>
            SendWithDeviceAsync<FulfillmentOrder>(
                HttpMethod.Post,
                "/api/posm/fulfillment/orders/" + Uri.EscapeDataString(orderId) + "/actions",
                token,
                action,
                ct);

        private async Task<FulfillmentApiResult<T>> SendWithDeviceAsync<T>(
            HttpMethod method,
            string path,
            string token,
            object? body,
            CancellationToken ct)
        {
            using var request = new HttpRequestMessage(method, path);
            request.Headers.TryAddWithoutValidation("X-Fulfillment-Device", token);
            if (body != null) request.Content = JsonContent.Create(body, options: EpharmJson.Options);
            return await SendAsync<T>(request, ct).ConfigureAwait(false);
        }

        private async Task<FulfillmentApiResult<T>> SendAsync<T>(HttpRequestMessage request, CancellationToken ct)
        {
            try
            {
                using var response = await _http.SendAsync(request, ct).ConfigureAwait(false);
                if (response.IsSuccessStatusCode)
                {
                    var value = await response.Content.ReadFromJsonAsync<T>(EpharmJson.Options, ct).ConfigureAwait(false);
                    return new FulfillmentApiResult<T> { Value = value, StatusCode = response.StatusCode };
                }

                string? message = null;
                try
                {
                    using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false));
                    if (document.RootElement.TryGetProperty("message", out var field)) message = field.GetString();
                }
                catch { }
                return new FulfillmentApiResult<T>
                {
                    StatusCode = response.StatusCode,
                    Message = message ?? $"HTTP {(int)response.StatusCode}",
                };
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                return new FulfillmentApiResult<T> { Message = ex.GetBaseException().Message };
            }
        }

        public void Dispose() => _http.Dispose();
    }
}
