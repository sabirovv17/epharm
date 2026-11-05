using System;
using System.Linq;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services;

public enum StaffPromptBypassReason { ServiceUnavailable, SmsUnavailable, SmsWait, IdentityUnavailable }

/// <summary>A cashier-screen prompt policy, not Windows lockdown or financial identity proof.</summary>
public sealed class StaffPromptPolicy
{
    private long? _bypassedGeneration;
    private string? _bypassStatus;

    public string? BypassStatus(long generation) => _bypassedGeneration == generation ? _bypassStatus : null;

    public string Bypass(StaffSessionState session, StaffPromptBypassReason reason)
    {
        // A reply already in flight must not turn an emergency bypass into a confirmed login.
        session.Invalidate();
        _bypassedGeneration = session.Generation;
        return _bypassStatus = reason switch {
            StaffPromptBypassReason.ServiceUnavailable => "staff_bypass_service_unavailable",
            StaffPromptBypassReason.SmsUnavailable => "staff_bypass_sms_unavailable",
            StaffPromptBypassReason.SmsWait => "staff_bypass_sms_wait",
            _ => "staff_bypass_identity_unavailable",
        };
    }

    public bool RequiresSignIn(StaffSessionObservation observation, long generation, bool authenticated) =>
        !authenticated && BypassStatus(generation) == null && HasComparableIdentity(observation);

    public bool NeedsIdentityBypass(StaffSessionObservation observation, long generation, bool authenticated) =>
        !authenticated && observation.Status == "resolved" && !HasComparableIdentity(observation) && BypassStatus(generation) == null;

    public static bool HasExactIin(StaffSessionObservation observation) =>
        observation.Status == "resolved" && HasExactIin(observation.Session?.StandardNIinStatus, observation.Session?.StandardNIin);

    private static bool HasExactIin(string? status, string? iin) => status == "available" &&
        iin is { Length: 12 } && iin.All(character => character is >= '0' and <= '9');

    private static bool HasNameFallback(string? iinStatus, string? nameStatus, string? name) =>
        iinStatus is "missing" or "unavailable" && nameStatus == "available" &&
        StaffSessionResolver.ReadFullName([name]).Status == "available";

    public static bool HasComparableIdentity(StaffSessionObservation observation) =>
        observation.Status == "resolved" && observation.Session is { } session &&
        (HasExactIin(observation) || HasNameFallback(session.StandardNIinStatus, session.StandardNFullNameStatus, session.StandardNFullName));

    public static bool HasComparableIdentity(StaffAuthContext? context) => context != null &&
        (HasExactIin(context.StandardNIinStatus, context.StandardNIin) ||
         HasNameFallback(context.StandardNIinStatus, context.StandardNFullNameStatus, context.StandardNFullName));

    public bool AcceptAuthentication(StaffSessionState session, long generation, string observedKey,
        StaffAuthContext context, StaffAuthResponse response, DateTimeOffset now)
    {
        if (response.Status != "authenticated" || response.Context != context || response.Pharmacist == null ||
            string.IsNullOrWhiteSpace(response.Pharmacist.Id) || string.IsNullOrWhiteSpace(response.SessionToken) ||
            response.ExpiresAt is not { } expires || !session.Accept(generation, observedKey, response.SessionToken, expires, now)) return false;
        _bypassedGeneration = null;
        _bypassStatus = null;
        return true;
    }
}
