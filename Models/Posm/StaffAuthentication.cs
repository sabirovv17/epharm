using System;

namespace CustomerDisplay.Models.Posm;

public sealed record StaffCapabilities(bool Enabled, int ProtocolVersion, bool NameFallbackSupported = false);
public sealed record StaffAuthContext(string StandardNUserId, string StandardNSessionId,
    string WorkstationId, string ProcessInstanceId, string? StandardNIin = null,
    string StandardNIinStatus = "unavailable", string? StandardNFullName = null,
    string StandardNFullNameStatus = "unavailable");
public sealed record StaffIdentity(string Id, string Name);
public sealed record StaffAuthResponse(string Status, string? SessionToken, DateTimeOffset? ExpiresAt,
    StaffIdentity Pharmacist, StaffAuthContext Context);
public sealed record StaffOtpResponse(string ChallengeId, int TtlSeconds, string PhoneMasked);
public sealed record StaffRegistrationResponse(string Status, string? SessionToken = null,
    DateTimeOffset? ExpiresAt = null, StaffIdentity? Pharmacist = null, StaffAuthContext? Context = null);
public sealed record StaffPasswordChanged(bool Changed);
