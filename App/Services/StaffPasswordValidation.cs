using System;
using System.Text;

namespace CustomerDisplay.Services;

public static class StaffPasswordValidation
{
    public static string? Error(string password, string confirmation)
    {
        if (string.IsNullOrWhiteSpace(password) || password.Length is < 4 or > 128) return "PASSWORD_LENGTH";
        if (Encoding.UTF8.GetByteCount(password) > 72) return "PASSWORD_BYTES";
        if (!string.Equals(password, confirmation, StringComparison.Ordinal)) return "PASSWORD_MISMATCH";
        return null;
    }
}
