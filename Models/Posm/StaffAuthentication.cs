using System;

namespace CustomerDisplay.Models.Posm;

public sealed record StaffCapabilities(bool Enabled, int ProtocolVersion, bool NameFallbackSupported = false, int DeviceEmployeeAuthVersion = 0);
public sealed record StaffAuthContext(string StandardNUserId, string StandardNSessionId,
    string WorkstationId, string ProcessInstanceId, string? StandardNIin = null,
    string StandardNIinStatus = "unavailable", string? StandardNFullName = null,
    string StandardNFullNameStatus = "unavailable");
public sealed record StaffDeviceContext(string ProcessInstanceId, StaffAuthContext? StandardNContext = null);
public sealed record StaffIdentity(string Id, string Name);
public sealed record StaffAuthResponse(string Status, string? SessionToken, DateTimeOffset? ExpiresAt,
    StaffIdentity Pharmacist, StaffAuthContext? Context = null, StaffDeviceContext? DeviceContext = null);
public sealed record StaffOtpResponse(string ChallengeId, int TtlSeconds, string PhoneMasked);
public sealed record StaffRegistrationResponse(string Status, string? SessionToken = null,
    DateTimeOffset? ExpiresAt = null, StaffIdentity? Pharmacist = null, StaffAuthContext? Context = null, StaffDeviceContext? DeviceContext = null);
public sealed record StaffPasswordChanged(bool Changed);
