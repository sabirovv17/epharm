// Historical test snapshot; see provenance.json. Only namespace/using prefixes are relocated.
using System.Text.Json;

namespace CustomerDisplay.Core.Tests.Legacy.V1066.Services
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
