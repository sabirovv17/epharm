using System;

namespace CustomerDisplay.Models.Posm;

public sealed record StaffCapabilities(bool Enabled, int ProtocolVersion);
public sealed record StaffAuthContext(string StandardNUserId, string StandardNSessionId,
    string WorkstationId, string ProcessInstanceId);
public sealed record StaffIdentity(string Id, string Name);
public sealed record StaffAuthResponse(string Status, string? SessionToken, DateTimeOffset? ExpiresAt,
    StaffIdentity Pharmacist, StaffAuthContext Context);
public sealed record StaffOtpResponse(string ChallengeId, int TtlSeconds, string PhoneMasked);
public sealed record StaffRegistrationResponse(string Status);
public sealed record StaffPasswordChanged(bool Changed);
