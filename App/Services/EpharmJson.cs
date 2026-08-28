using System.Text.Json;

namespace CustomerDisplay.Services
{
    /// <summary>Единые JSON-опции POSM: camelCase и обратная совместимость при чтении.</summary>
    public static class EpharmJson
    {
        public static readonly JsonSerializerOptions Options = new()
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = true,
        };
    }
}
