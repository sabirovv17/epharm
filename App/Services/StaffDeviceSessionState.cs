using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services;

/// <summary>Only real OS process instances establish presence; no synthetic Standard-N user/session IDs.</summary>
public sealed record StaffProcessPresence(string Status, string? Identity = null)
{
    public static StaffProcessPresence FromInstances(IEnumerable<string> instances)
    {
        var values = instances.Distinct(StringComparer.Ordinal).OrderBy(value => value, StringComparer.Ordinal).ToArray();
        return values.Length switch {
            0 => new("no_process"),
            1 => new("resolved", values[0]),
            _ => new("ambiguous_process", "set:" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(string.Join("|", values))))),
        };
    }
}

/// <summary>One empty enumeration is not a confirmed process exit; access failures never mean an exit.</summary>
public sealed class StaffProcessStability
{
    private DateTimeOffset? _missingSince;
    public StaffProcessPresence? Confirm(StaffProcessPresence observed, DateTimeOffset now)
    {
        if (observed.Identity != null) { _missingSince = null; return observed; }
        if (observed.Status != "no_process") { _missingSince = null; return null; }
        _missingSince ??= now;
        return now - _missingSince >= TimeSpan.FromSeconds(2) ? observed : null;
    }
}

/// <summary>Only an explicit server policy enables direct registration; changes invalidate in-flight registration attempts.</summary>
public sealed class StaffRegistrationPolicy
{
    public bool SmsRequired { get; private set; } = true;
    public long Generation { get; private set; }
    public bool SetSmsRequired(bool required)
    {
        if (SmsRequired == required) return false;
        SmsRequired = required; Generation++; return true;
    }
    public bool IsCurrent(long generation) => generation == Generation;
}

/// <summary>Own employee login survives unreadable source data; only a real process or known cashier switch clears it.</summary>
public sealed class StaffDeviceSessionState
{
    private readonly TimeProvider _time;
    private long? _recoveryStarted;
    private string? _pharmacistId;
    public StaffDeviceSessionState(TimeProvider? time = null) => _time = time ?? TimeProvider.System;
    public string? ProcessInstanceId { get; private set; }
    public StaffSessionObservation Observation { get; private set; } = new("initializing");
    public StaffAuthContext? LastKnownSource { get; private set; }
    public long Generation { get; private set; }
    public string? Grant { get; private set; }
    public DateTimeOffset? ExpiresAt { get; private set; }
    public bool VerificationPending { get; private set; }
    public bool RecoveryExpired { get; private set; }
    public DateTimeOffset? RecoveryDeadline { get; private set; }
    public bool Prompted { get; private set; }
    public string? BypassStatus { get; private set; }
    public StaffDeviceContext? Context => ProcessInstanceId == null ? null : new(ProcessInstanceId, CurrentSource);
    public StaffAuthContext? CurrentSource => ProcessInstanceId != null && Observation.Status == "resolved" && Observation.Session is { } observed &&
        observed.ProcessIdentity == ProcessInstanceId ? SourceContext(observed) : null;

    public bool Observe(StaffProcessPresence process, StaffSessionObservation observation)
    {
        var source = process.Identity != null && observation.Status == "resolved" && observation.Session is { } observed &&
            observed.ProcessIdentity == process.Identity ? SourceContext(observed) : null;
        var changed = ProcessInstanceId != process.Identity || source != null && LastKnownSource != null && !SameCashier(source, LastKnownSource);
        if (changed) {
            Invalidate(); LastKnownSource = null; ProcessInstanceId = process.Identity;
        }
        Observation = observation;
        if (source != null) LastKnownSource = source;
        return changed;
    }

    public static StaffAuthContext SourceContext(StaffObservedSession observed) =>
        new(observed.ExternalUserId, observed.ExternalSessionId, observed.WorkstationId, observed.ProcessIdentity,
            observed.StandardNIin, observed.StandardNIinStatus, observed.StandardNFullName, observed.StandardNFullNameStatus);
    private static bool SameCashier(StaffAuthContext first, StaffAuthContext second) =>
        first.StandardNUserId == second.StandardNUserId && first.StandardNSessionId == second.StandardNSessionId &&
        first.WorkstationId == second.WorkstationId && first.ProcessInstanceId == second.ProcessInstanceId;

    public bool Accept(long generation, StaffDeviceContext sent, StaffAuthResponse response, DateTimeOffset now)
    {
        if (generation != Generation || ProcessInstanceId == null || sent.ProcessInstanceId != ProcessInstanceId ||
            response.Status != "authenticated" || response.DeviceContext != sent || response.Pharmacist == null ||
            string.IsNullOrWhiteSpace(response.Pharmacist.Id) || string.IsNullOrWhiteSpace(response.SessionToken) ||
            response.ExpiresAt is not { } expires || expires <= now ||
            sent.StandardNContext != null && (sent.StandardNContext.ProcessInstanceId != ProcessInstanceId ||
                LastKnownSource != null && !SameCashier(sent.StandardNContext, LastKnownSource))) return false;
        Grant = response.SessionToken; ExpiresAt = expires; Prompted = true; BypassStatus = null;
        _pharmacistId = response.Pharmacist.Id;
        VerificationPending = false; RecoveryDeadline = null; _recoveryStarted = null; RecoveryExpired = false;
        return true;
    }
    // A retained token is only evidence to send back to the server, never authentication
    // while a check is pending. Transport retries cannot extend either deadline.
    public bool CanValidate(DateTimeOffset now)
    {
        if (Grant == null) return false;
        if (ExpiresAt <= now) { Invalidate(); return false; }
        if (RecoveryDeadline <= now || _recoveryStarted is { } started && _time.GetElapsedTime(started) >= TimeSpan.FromMinutes(2)) {
            Invalidate();
            // One ordinary, closable prompt after recovery expires; never a fullscreen
            // interruption or an endless prompt loop while the network remains down.
            BypassStatus = "staff_bypass_service_unavailable";
            RecoveryExpired = true;
            return false;
        }
        return true;
    }
    public bool IsAuthenticated(DateTimeOffset now) => CanValidate(now) && !VerificationPending;
    public bool IsRevalidating(DateTimeOffset now) => CanValidate(now) && VerificationPending;
    public bool BeginValidation(DateTimeOffset now)
    {
        if (!CanValidate(now)) return false;
        VerificationPending = true; BypassStatus = "session_revalidating";
        return true;
    }
    public bool MarkTransportFailure(DateTimeOffset now)
    {
        if (!BeginValidation(now)) return false;
        RecoveryDeadline ??= now.AddMinutes(2);
        _recoveryStarted ??= _time.GetTimestamp();
        return true;
    }
    public bool ConfirmValidation(long generation, string token, StaffDeviceContext sent, StaffAuthResponse response, DateTimeOffset now)
    {
        if (Generation != generation || Grant != token || !CanValidate(now) || ProcessInstanceId != sent.ProcessInstanceId ||
            response.Status != "authenticated" || response.DeviceContext != sent || response.Pharmacist == null ||
            string.IsNullOrWhiteSpace(response.Pharmacist.Id) || response.Pharmacist.Id != _pharmacistId ||
            response.ExpiresAt is not { } expires || expires <= now ||
            sent.StandardNContext != null && LastKnownSource != null && !SameCashier(sent.StandardNContext, LastKnownSource)) return false;
        if (expires < ExpiresAt) ExpiresAt = expires; // A check cannot silently extend a grant.
        VerificationPending = false; RecoveryDeadline = null; _recoveryStarted = null; BypassStatus = null;
        return true;
    }
    public void AwaitAssignment()
    {
        Invalidate(); Prompted = true; BypassStatus = "pending_approval";
    }
    public void AllowServiceRecoveryPrompt()
    {
        if (Grant == null && BypassStatus == "staff_bypass_service_unavailable") Prompted = false;
    }
    public bool NeedsPrompt(DateTimeOffset now) => !IsAuthenticated(now) && ProcessInstanceId != null && !Prompted;
    public void MarkPrompted() { Prompted = true; RecoveryExpired = false; }
    public void ClearGrant() { Grant = null; _pharmacistId = null; ExpiresAt = null; VerificationPending = false; RecoveryDeadline = null; _recoveryStarted = null; }
    public void Invalidate() { Generation++; ClearGrant(); Prompted = false; BypassStatus = null; RecoveryExpired = false; }
    public string Bypass(StaffPromptBypassReason reason, bool preservePendingRequest = false)
    {
        // A failed background poll must not cancel an independently pending login/registration response.
        if (!preservePendingRequest || reason != StaffPromptBypassReason.ServiceUnavailable || Grant != null)
            Invalidate();
        Prompted = true;
        return BypassStatus = reason switch {
            StaffPromptBypassReason.ServiceUnavailable => "staff_bypass_service_unavailable",
            StaffPromptBypassReason.SmsUnavailable => "staff_bypass_sms_unavailable",
            StaffPromptBypassReason.SmsWait => "staff_bypass_sms_wait",
            _ => "staff_bypass_identity_unavailable",
        };
    }
}
