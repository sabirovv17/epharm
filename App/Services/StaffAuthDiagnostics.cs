using System.Collections.Generic;
using System.Net;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services;

public sealed record StaffCapabilityCheck(bool Enabled, string Status, bool RegistrationSmsRequired = true);

/// <summary>Only fixed operational codes may leave the employee-auth channel; never identifiers or responses.</summary>
public static class StaffAuthDiagnostics
{
    private static readonly HashSet<string> Statuses = new(System.StringComparer.Ordinal) {
        "initializing", "local_disabled", "device_auth_required", "forbidden", "server_disabled",
        "unsupported_protocol", "endpoint_unavailable", "network_unavailable", "enabled",
        "no_process", "process_unavailable", "ambiguous_process", "process_changing",
        "database_disabled", "database_unavailable", "no_session", "ambiguous_session", "unknown_workstation",
        "unsupported_session_time", "future_session_time", "stale_session", "invalid_session_time",
        "unsupported_process_time", "session_predates_process", "login_required", "login_visible", "authenticated",
        "session_revalidating", "pending_approval",
        "staff_bypass_service_unavailable", "staff_bypass_sms_unavailable", "staff_bypass_sms_wait", "staff_bypass_identity_unavailable",
    };

    public static bool IsKnown(string? status) => status != null && Statuses.Contains(status);
    public static string Normalize(string? status) => IsKnown(status) ? status! : "initializing";

    // An unavailable endpoint is not evidence that the server changed its registration policy.
    public static bool? RegistrationPolicy(StaffCapabilityCheck capability) =>
        capability.Enabled && capability.Status == "enabled" ? capability.RegistrationSmsRequired : null;

    public static StaffCapabilityCheck CapabilityFailure(HttpStatusCode status) => new(false, status switch {
        HttpStatusCode.Unauthorized => "device_auth_required",
        HttpStatusCode.Forbidden => "forbidden",
        _ => "endpoint_unavailable",
    });

    // A rollback must disable the whole process-login flow, never replay it through legacy source-bound routes.
    public static StaffCapabilityCheck Capability(StaffCapabilities? capabilities) => capabilities?.ProtocolVersion != 2 || !capabilities.NameFallbackSupported || capabilities.DeviceEmployeeAuthVersion != 1
        ? new(false, "unsupported_protocol")
        : capabilities.Enabled ? new(true, "enabled", capabilities.RegistrationSmsRequired) : new(false, "server_disabled");

    public static string Observation(string status, bool authenticated, bool loginVisible = false) => status == "resolved"
        ? authenticated ? "authenticated" : loginVisible ? "login_visible" : "login_required"
        : Normalize(status);

    // Wrong password and unknown IIN deliberately have the same guidance, never an existence probe.
    public static bool OfferRecovery(string errorCode) => errorCode == "INVALID_CREDENTIALS";
}
