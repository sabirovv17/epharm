using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StaffPromptPolicyTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-11-03T10:00:00Z");
    private static StaffSessionObservation Observation(string? iin = "000101500011", string iinStatus = "available",
        string session = "session-1") => new("resolved", new("44:1000", "user-1", session, "ws-1", iin, iinStatus));
    private static StaffSessionState State(StaffSessionObservation? observation = null)
    {
        var state = new StaffSessionState();
        state.Observe(observation ?? Observation());
        return state;
    }
    private static StaffAuthContext Context(StaffObservedSession session) => new(session.ExternalUserId,
        session.ExternalSessionId, session.WorkstationId, session.ProcessIdentity, session.StandardNIin, session.StandardNIinStatus, session.StandardNFullName, session.StandardNFullNameStatus);
    private static StaffAuthResponse Response(StaffAuthContext context) => new("authenticated", "opaque-grant",
        Now.AddHours(8), new("person-1", "Test Pharmacist"), context);

    [Fact]
    public void AnExactUnauthenticatedCashierWithAvailableIinRequiresPresentation()
    {
        var policy = new StaffPromptPolicy();
        var state = State();
        Assert.True(policy.RequiresSignIn(state.Observation, state.Generation, false));
        Assert.False(policy.RequiresSignIn(state.Observation, state.Generation, true));
        foreach (var status in new[] { "no_process", "no_session", "database_unavailable", "ambiguous_session", "stale_session" })
            Assert.False(policy.RequiresSignIn(new(status), state.Generation, false));
    }

    [Theory]
    [InlineData(null, "unavailable")]
    [InlineData(null, "missing")]
    [InlineData(null, "invalid")]
    [InlineData(null, "ambiguous")]
    [InlineData(null, "available")]
    [InlineData("00010150001", "available")]
    [InlineData("０００１０１５０００１１", "available")]
    [InlineData("000101500011", "unavailable")]
    public void UnknownOrMalformedIinWithoutAUsableNameNeverRequiresPresentation(string? iin, string status)
    {
        var policy = new StaffPromptPolicy();
        var state = State(Observation(iin, status));
        Assert.False(policy.RequiresSignIn(state.Observation, state.Generation, false));
    }

    [Theory]
    [InlineData("missing", true)]
    [InlineData("unavailable", true)]
    [InlineData("invalid", false)]
    [InlineData("ambiguous", false)]
    [InlineData("available", false)]
    public void OnlyMissingOrUnavailableIinAllowsTheFullNamePresentationFallback(string iinStatus, bool allowed)
    {
        var observation = Observation(null, iinStatus);
        observation = observation with { Session = observation.Session! with { StandardNFullName = "Иванов Иван", StandardNFullNameStatus = "available" } };
        var policy = new StaffPromptPolicy(); var state = State(observation);
        Assert.Equal(allowed, policy.RequiresSignIn(observation, state.Generation, false));
        Assert.Equal(allowed, StaffPromptPolicy.HasComparableIdentity(Context(observation.Session)));
        Assert.Equal(!allowed, policy.NeedsIdentityBypass(observation, state.Generation, false));
        if (allowed) {
            var context = Context(observation.Session);
            Assert.True(policy.AcceptAuthentication(state, state.Generation, observation.Session.Key, context, Response(context), Now));
            Assert.False(policy.RequiresSignIn(observation, state.Generation, true));
        }
    }

    [Theory]
    [InlineData("available", "Кассир")]
    [InlineData("missing", null)]
    [InlineData("ambiguous", null)]
    [InlineData("invalid", null)]
    [InlineData("unavailable", null)]
    public void AnUnusableFullNameWithNoIinKeepsWorkAvailable(string status, string? fullName)
    {
        var observation = Observation(null, "missing");
        observation = observation with { Session = observation.Session! with { StandardNFullName = fullName, StandardNFullNameStatus = status } };
        var policy = new StaffPromptPolicy(); var state = State(observation);
        Assert.False(policy.RequiresSignIn(observation, state.Generation, false));
        Assert.True(policy.NeedsIdentityBypass(observation, state.Generation, false));
    }

    [Fact]
    public void ANameInTheWrongResponseCannotAuthorizeTheCurrentObservedAccount()
    {
        var observation = Observation(null, "missing");
        observation = observation with { Session = observation.Session! with { StandardNFullName = "Иванов Иван", StandardNFullNameStatus = "available" } };
        var state = State(observation); var policy = new StaffPromptPolicy(); var context = Context(observation.Session);
        var response = Response(context with { StandardNFullName = "Петров Петр" });
        Assert.False(policy.AcceptAuthentication(state, state.Generation, observation.Session.Key, context, response, Now));
        Assert.False(state.IsAuthenticated(Now));
    }

    [Theory]
    [InlineData(StaffPromptBypassReason.ServiceUnavailable, "staff_bypass_service_unavailable")]
    [InlineData(StaffPromptBypassReason.SmsUnavailable, "staff_bypass_sms_unavailable")]
    [InlineData(StaffPromptBypassReason.SmsWait, "staff_bypass_sms_wait")]
    [InlineData(StaffPromptBypassReason.IdentityUnavailable, "staff_bypass_identity_unavailable")]
    public void FailOpenSuppressesOnlyCurrentGenerationAndNeverCreatesAuthentication(StaffPromptBypassReason reason, string status)
    {
        var policy = new StaffPromptPolicy();
        var state = State();
        var oldGeneration = state.Generation;
        var oldKey = state.Observation.Session!.Key;
        var context = Context(state.Observation.Session);
        Assert.Equal(status, policy.Bypass(state, reason));
        Assert.Equal(status, policy.BypassStatus(state.Generation));
        Assert.False(state.IsAuthenticated(Now));
        Assert.False(policy.RequiresSignIn(state.Observation, state.Generation, false));
        Assert.False(policy.AcceptAuthentication(state, oldGeneration, oldKey, context, Response(context), Now));
        Assert.False(state.Observe(state.Observation));
        Assert.False(policy.RequiresSignIn(state.Observation, state.Generation, false));
        state.Observe(Observation(session: "session-2"));
        Assert.Null(policy.BypassStatus(state.Generation));
        Assert.True(policy.RequiresSignIn(state.Observation, state.Generation, false));
    }

    [Fact]
    public void ACurrentManualLoginAfterBypassClearsTheUnconfirmedStatus()
    {
        var policy = new StaffPromptPolicy();
        var state = State();
        policy.Bypass(state, StaffPromptBypassReason.ServiceUnavailable);
        var context = Context(state.Observation.Session!);
        Assert.True(policy.AcceptAuthentication(state, state.Generation, state.Observation.Session!.Key, context, Response(context), Now));
        Assert.True(state.IsAuthenticated(Now));
        Assert.Null(policy.BypassStatus(state.Generation));
        Assert.False(policy.RequiresSignIn(state.Observation, state.Generation, true));
    }

    [Fact]
    public void ManualLoginWithAnUnknownSourceIinIsNotRevokedOnEveryObservation()
    {
        var policy = new StaffPromptPolicy();
        var state = State(Observation(null, "unavailable"));
        Assert.True(policy.NeedsIdentityBypass(state.Observation, state.Generation, false));
        policy.Bypass(state, StaffPromptBypassReason.IdentityUnavailable);
        Assert.False(policy.NeedsIdentityBypass(state.Observation, state.Generation, false));
        var context = Context(state.Observation.Session!);
        Assert.True(policy.AcceptAuthentication(state, state.Generation, state.Observation.Session!.Key, context, Response(context), Now));
        Assert.False(policy.NeedsIdentityBypass(state.Observation, state.Generation, state.IsAuthenticated(Now)));
        Assert.False(policy.RequiresSignIn(state.Observation, state.Generation, state.IsAuthenticated(Now)));
    }

    [Fact]
    public void ARegistrationGrantReleasesPresentationOnlyForTheStillCurrentCashier()
    {
        var policy = new StaffPromptPolicy();
        var state = State();
        var context = Context(state.Observation.Session!);
        var registration = new StaffRegistrationResponse("authenticated", "new-grant", Now.AddHours(8),
            new("person-1", "Test Pharmacist"), context);
        var response = new StaffAuthResponse(registration.Status, registration.SessionToken,
            registration.ExpiresAt, registration.Pharmacist!, registration.Context!);
        var generation = state.Generation;
        var key = state.Observation.Session!.Key;
        Assert.True(policy.AcceptAuthentication(state, generation, key, context, response, Now));
        Assert.False(policy.RequiresSignIn(state.Observation, state.Generation, state.IsAuthenticated(Now)));
        state.Observe(Observation(iin: "951212500015"));
        Assert.False(policy.AcceptAuthentication(state, generation, key, context, response, Now));
        Assert.True(policy.RequiresSignIn(state.Observation, state.Generation, state.IsAuthenticated(Now)));
    }

    [Fact]
    public void MissingExpiredOrMismatchedRegistrationProofDoesNotReleasePresentation()
    {
        var policy = new StaffPromptPolicy();
        var state = State();
        var context = Context(state.Observation.Session!);
        var valid = Response(context);
        foreach (var response in new[] {
            valid with { Status = "pending" }, valid with { SessionToken = null }, valid with { SessionToken = " " },
            valid with { ExpiresAt = Now }, valid with { Context = context with { StandardNUserId = "other" } },
            valid with { Pharmacist = new("", "Test Pharmacist") },
        })
            Assert.False(policy.AcceptAuthentication(state, state.Generation, state.Observation.Session!.Key, context, response, Now));
        Assert.False(state.IsAuthenticated(Now));
        Assert.True(policy.RequiresSignIn(state.Observation, state.Generation, false));
    }
}
