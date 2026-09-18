using System;

namespace CustomerDisplay.Services
{
    internal static class PosmDeviceCredentialResolver
    {
        internal static string Resolve(
            string configuredKey,
            string expectedDeviceId,
            string expectedPharmacyId,
            string? storedDeviceId,
            string? storedPharmacyId,
            string? storedToken)
        {
            if (!string.IsNullOrWhiteSpace(storedToken) && storedToken.Trim().Length >= 32 &&
                string.Equals(storedDeviceId?.Trim(), expectedDeviceId.Trim(), StringComparison.OrdinalIgnoreCase) &&
                string.Equals(storedPharmacyId?.Trim(), expectedPharmacyId.Trim(), StringComparison.Ordinal))
            {
                return storedToken.Trim();
            }

            return configuredKey.Trim();
        }
    }
}
