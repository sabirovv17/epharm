using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Выполняет POSM API-запросы через активный backend origin и безопасно переходит к резервному,
    /// когда публичный ingress возвращает ошибку маршрутизации. HTTPS остаётся предпочтительным;
    /// пока активен fallback, основной origin перепроверяется раз в пять минут.
    /// </summary>
    internal sealed class BackendFailoverHandler : DelegatingHandler
    {
        private static readonly TimeSpan PrimaryProbeInterval = TimeSpan.FromMinutes(5);
        private static readonly TimeSpan EndpointAttemptTimeout = TimeSpan.FromSeconds(2);

        private readonly IReadOnlyList<Uri> _endpoints;
        private readonly Action<string>? _log;
        private int _activeIndex;
        private long _nextPrimaryProbeUtcTicks;

        public BackendFailoverHandler(IReadOnlyList<Uri> endpoints, Action<string>? log = null)
            : base(new HttpClientHandler())
        {
            if (endpoints.Count == 0) throw new ArgumentException("At least one backend endpoint is required.", nameof(endpoints));
            _endpoints = endpoints;
            _log = log;
        }

        public Uri ActiveEndpoint => _endpoints[Volatile.Read(ref _activeIndex)];

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var buffered = await BufferedRequest.CreateAsync(request, cancellationToken).ConfigureAwait(false);
            Exception? lastNetworkError = null;
            var candidates = CandidateIndexes();

            for (var position = 0; position < candidates.Count; position++)
            {
                var endpointIndex = candidates[position];
                using var attempt = buffered.Create(_endpoints[endpointIndex]);
                var hasFallback = position < candidates.Count - 1;
                using var attemptCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                if (hasFallback) attemptCts.CancelAfter(EndpointAttemptTimeout);
                try
                {
                    var response = await base.SendAsync(attempt, attemptCts.Token).ConfigureAwait(false);
                    if (!hasFallback || !ShouldTryFallback(response.StatusCode))
                    {
                        SetActiveEndpoint(endpointIndex);
                        return response;
                    }

                    if (endpointIndex == 0) DelayPrimaryProbe();
                    response.Dispose();
                }
                catch (HttpRequestException ex) when (hasFallback)
                {
                    if (endpointIndex == 0) DelayPrimaryProbe();
                    lastNetworkError = ex;
                }
                catch (OperationCanceledException ex) when (
                    !cancellationToken.IsCancellationRequested && hasFallback)
                {
                    if (endpointIndex == 0) DelayPrimaryProbe();
                    lastNetworkError = ex;
                }
            }

            throw lastNetworkError ?? new HttpRequestException("No POSM backend endpoint returned a response.");
        }

        private List<int> CandidateIndexes()
        {
            var active = Volatile.Read(ref _activeIndex);
            var candidates = new List<int>(_endpoints.Count);
            var probePrimary = active != 0 && DateTime.UtcNow.Ticks >= Volatile.Read(ref _nextPrimaryProbeUtcTicks);

            if (active == 0 || probePrimary) candidates.Add(0);
            if (active != 0) candidates.Add(active);
            for (var index = 0; index < _endpoints.Count; index++)
            {
                if (!candidates.Contains(index)) candidates.Add(index);
            }

            return candidates;
        }

        private void DelayPrimaryProbe()
            => Volatile.Write(ref _nextPrimaryProbeUtcTicks, DateTime.UtcNow.Add(PrimaryProbeInterval).Ticks);

        private void SetActiveEndpoint(int endpointIndex)
        {
            var previousIndex = Interlocked.Exchange(ref _activeIndex, endpointIndex);
            if (previousIndex != endpointIndex)
            {
                _log?.Invoke($"POSM backend переключён: {_endpoints[previousIndex]} -> {_endpoints[endpointIndex]}");
            }
        }

        private static bool ShouldTryFallback(HttpStatusCode statusCode) =>
            statusCode is HttpStatusCode.NotFound or HttpStatusCode.BadGateway or HttpStatusCode.ServiceUnavailable or HttpStatusCode.GatewayTimeout;

        private sealed class BufferedRequest
        {
            private readonly HttpMethod _method;
            private readonly string _pathAndQuery;
            private readonly Version _version;
            private readonly HttpVersionPolicy _versionPolicy;
            private readonly List<KeyValuePair<string, IEnumerable<string>>> _headers;
            private readonly byte[]? _content;
            private readonly List<KeyValuePair<string, IEnumerable<string>>> _contentHeaders;

            private BufferedRequest(
                HttpMethod method,
                string pathAndQuery,
                Version version,
                HttpVersionPolicy versionPolicy,
                List<KeyValuePair<string, IEnumerable<string>>> headers,
                byte[]? content,
                List<KeyValuePair<string, IEnumerable<string>>> contentHeaders)
            {
                _method = method;
                _pathAndQuery = pathAndQuery;
                _version = version;
                _versionPolicy = versionPolicy;
                _headers = headers;
                _content = content;
                _contentHeaders = contentHeaders;
            }

            public static async Task<BufferedRequest> CreateAsync(HttpRequestMessage source, CancellationToken cancellationToken)
            {
                if (source.RequestUri == null) throw new InvalidOperationException("POSM request URI is missing.");

                var headers = CopyHeaders(source.Headers);
                byte[]? content = null;
                var contentHeaders = new List<KeyValuePair<string, IEnumerable<string>>>();
                if (source.Content != null)
                {
                    content = await source.Content.ReadAsByteArrayAsync(cancellationToken).ConfigureAwait(false);
                    contentHeaders = CopyHeaders(source.Content.Headers);
                }

                return new BufferedRequest(
                    source.Method,
                    source.RequestUri.PathAndQuery,
                    source.Version,
                    source.VersionPolicy,
                    headers,
                    content,
                    contentHeaders);
            }

            public HttpRequestMessage Create(Uri endpoint)
            {
                var queryIndex = _pathAndQuery.IndexOf('?');
                var target = new UriBuilder(endpoint)
                {
                    Path = queryIndex >= 0 ? _pathAndQuery[..queryIndex] : _pathAndQuery,
                    Query = queryIndex >= 0 ? _pathAndQuery[(queryIndex + 1)..] : string.Empty,
                    Fragment = string.Empty,
                }.Uri;
                var request = new HttpRequestMessage(_method, target)
                {
                    Version = _version,
                    VersionPolicy = _versionPolicy,
                };
                foreach (var header in _headers)
                    request.Headers.TryAddWithoutValidation(header.Key, header.Value);

                if (_content != null)
                {
                    request.Content = new ByteArrayContent(_content);
                    foreach (var header in _contentHeaders)
                        request.Content.Headers.TryAddWithoutValidation(header.Key, header.Value);
                }

                return request;
            }

            private static List<KeyValuePair<string, IEnumerable<string>>> CopyHeaders(System.Net.Http.Headers.HttpHeaders headers)
                => new(headers);
        }
    }
}
