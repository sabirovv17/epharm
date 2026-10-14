using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace CustomerDisplay.Services;

/// <summary>Only an explicit, valid negative reply retires a durable shown receipt.</summary>
public static class MerchTaskDeliveryReply
{
    public static async Task<bool> ReadAsync(HttpResponseMessage response, CancellationToken cancellation = default)
    {
        response.EnsureSuccessStatusCode();
        using var stream = await response.Content.ReadAsStreamAsync(cancellation).ConfigureAwait(false);
        using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellation).ConfigureAwait(false);
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("accepted", out var accepted) ||
            accepted.ValueKind is not (JsonValueKind.True or JsonValueKind.False))
            throw new HttpRequestException("Invalid merchandising delivery response.");
        if (root.TryGetProperty("available", out var available))
        {
            if (available.ValueKind is not (JsonValueKind.True or JsonValueKind.False))
                throw new HttpRequestException("Invalid merchandising availability response.");
            if (!available.GetBoolean()) throw new MerchTaskUnavailableException();
        }
        return accepted.GetBoolean();
    }
}
