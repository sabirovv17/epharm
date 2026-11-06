using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StaffDeviceSessionStateTests
{
    private const string Process = "42:639295055776695955";
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-11-06T10:00:00Z");
    private static StaffSessionObservation Source(string user = "user-1", string session = "session-1", string workstation = "ws-1", string process = Process) =>
        new("resolved", new(process, user, session, workstation, "000101500011", "available"));
    private static StaffDeviceSessionState State(StaffSessionObservation? observation = null)
    {
        var state = new StaffDeviceSessionState();
        state.Observe(new("resolved", Process), observation ?? new("database_unavailable"));
        return state;
    }
    private static StaffAuthResponse Response(StaffDeviceContext context) => new("authenticated", "opaque-grant", Now.AddHours(8), new("p-1", "Synthetic Employee"), DeviceContext: context);
    private static void Login(StaffDeviceSessionState state)
    {
        var context = state.Context!;
        Assert.True(state.Accept(state.Generation, context, Response(context), Now));
    }

    [Fact]
    public void RegistrationPolicyDefaultsToSmsAndOnlyExplicitChangesInvalidateAttempts()
    {
        var policy = new StaffRegistrationPolicy();
        Assert.True(policy.SmsRequired); var original = policy.Generation;
        Assert.False(policy.SetSmsRequired(true)); Assert.True(policy.IsCurrent(original));
        Assert.True(policy.SetSmsRequired(false)); Assert.False(policy.SmsRequired); Assert.False(policy.IsCurrent(original));
        var directAttempt = policy.Generation;
        Assert.True(policy.SetSmsRequired(true)); Assert.True(policy.SmsRequired); Assert.False(policy.IsCurrent(directAttempt));
        policy.SetSmsRequired(false);
        Assert.False(policy.IsCurrent(directAttempt)); // Same boolean after rollback is not the same request generation.
    }

    [Fact]
    public void TemporaryProcessErrorsAndOneEmptyEnumerationCannotConfirmAnExit()
    {
        var stability = new StaffProcessStability();
        var live = new StaffProcessPresence("resolved", Process);
        Assert.Equal(live, stability.Confirm(live, Now));
        Assert.Null(stability.Confirm(new("no_process"), Now));
        Assert.Null(stability.Confirm(new("no_process"), Now.AddMilliseconds(1999)));
        Assert.Null(stability.Confirm(new("process_unavailable"), Now.AddSeconds(2)));
        Assert.Null(stability.Confirm(new("process_changing"), Now.AddSeconds(3)));
        Assert.Null(stability.Confirm(new("no_process"), Now.AddSeconds(4)));
        Assert.Equal(live, stability.Confirm(live, Now.AddSeconds(5)));
        Assert.Null(stability.Confirm(new("no_process"), Now.AddSeconds(6)));
        Assert.Equal(new("no_process"), stability.Confirm(new("no_process"), Now.AddSeconds(8)));
    }

    [Fact]
    public void ARealProcessPromptsWithoutAnyDatabaseOrInventedCashierIdentifiers()
    {
        var state = State();
        Assert.True(state.NeedsPrompt(Now)); Assert.Null(state.Context!.StandardNContext);
        Assert.Equal(Process, state.Context.ProcessInstanceId);
        state.MarkPrompted();
        Assert.False(state.NeedsPrompt(Now));
        Login(state); Assert.True(state.IsAuthenticated(Now));
    }

    [Fact]
    public void NoProcessNeverPromptsAndRestartWithTheSamePidStartsANewGeneration()
    {
        var state = State(); Login(state); var generation = state.Generation;
        state.Observe(new("no_process"), new("no_process"));
        Assert.False(state.NeedsPrompt(Now)); Assert.Null(state.Context); Assert.Null(state.Grant);
        state.Observe(new("resolved", "42:639295055776695956"), new("database_disabled"));
        Assert.True(state.Generation > generation); Assert.True(state.NeedsPrompt(Now));
    }

    [Fact]
    public void MultiProcessPresenceUsesAStableBoundedFingerprintAndNeverInventsASourceSession()
    {
        var one = StaffProcessPresence.FromInstances([Process, "43:639295055776695956"]);
        var reordered = StaffProcessPresence.FromInstances(["43:639295055776695956", Process, Process]);
        Assert.Equal(one, reordered); Assert.Equal("ambiguous_process", one.Status);
        Assert.Equal(68, one.Identity!.Length);
        var state = new StaffDeviceSessionState(); state.Observe(one, new("ambiguous_process"));
        Assert.True(state.NeedsPrompt(Now)); Assert.Null(state.Context!.StandardNContext);
        Assert.NotEqual(one, StaffProcessPresence.FromInstances([Process]));
    }

    [Fact]
    public void SourceStatusFlapsPreserveTheOwnEmployeeGrantAndLastReliableBinding()
    {
        var state = State(Source()); Login(state); var generation = state.Generation;
        foreach (var status in new[] { "database_unavailable", "database_disabled", "ambiguous_session", "stale_session", "no_session" }) {
            Assert.False(state.Observe(new("resolved", Process), new(status)));
            Assert.True(state.IsAuthenticated(Now)); Assert.Equal(generation, state.Generation);
            Assert.Null(state.Context!.StandardNContext); Assert.Equal("user-1", state.LastKnownSource!.StandardNUserId);
            Assert.False(state.NeedsPrompt(Now));
        }
        Assert.False(state.Observe(new("resolved", Process), Source())); Assert.True(state.IsAuthenticated(Now));
    }

    [Fact]
    public void FirstLateResolvedSourceAdoptsTheBindingWithoutInvalidatingOwnLoginOrOpeningAnotherWindow()
    {
        var state = State(); Login(state); var generation = state.Generation;
        Assert.False(state.Observe(new("resolved", Process), Source()));
        Assert.Equal(generation, state.Generation); Assert.True(state.IsAuthenticated(Now));
        Assert.Equal("user-1", state.Context!.StandardNContext!.StandardNUserId);
        Assert.False(state.NeedsPrompt(Now));
    }

    [Theory]
    [InlineData("other-user", "session-1", "ws-1")]
    [InlineData("user-1", "other-session", "ws-1")]
    [InlineData("user-1", "session-1", "other-ws")]
    public void ATrueCashierSwitchAfterAnUnknownIntervalRevokesAndPrompts(string user, string session, string workstation)
    {
        var state = State(Source()); Login(state); var generation = state.Generation;
        state.Observe(new("resolved", Process), new("database_unavailable"));
        Assert.True(state.Observe(new("resolved", Process), Source(user, session, workstation)));
        Assert.True(state.Generation > generation); Assert.Null(state.Grant); Assert.True(state.NeedsPrompt(Now));
    }

    [Fact]
    public void IinOrNameDiagnosticChangesDoNotTransferTheOwnEmployeeIdentity()
    {
        var state = State(Source()); Login(state); var generation = state.Generation;
        var next = Source(); next = next with { Session = next.Session! with { StandardNIin = null, StandardNIinStatus = "missing", StandardNFullName = "Иванов Иван", StandardNFullNameStatus = "available" } };
        Assert.False(state.Observe(new("resolved", Process), next));
        Assert.Equal(generation, state.Generation); Assert.Equal("opaque-grant", state.Grant); Assert.False(state.NeedsPrompt(Now));
    }

    [Fact]
    public void AResponseSentBeforeTheFirstSourceResolutionStillAuthenticatesOnlyTheSameRealProcess()
    {
        var state = State(); var context = state.Context!; var generation = state.Generation;
        state.Observe(new("resolved", Process), Source());
        Assert.True(state.Accept(generation, context, Response(context), Now));
        Assert.NotNull(state.Context!.StandardNContext); Assert.True(state.IsAuthenticated(Now));
    }

    [Fact]
    public void LateRepliesFromAChangedProcessOrCashierAreRejectedIncludingSwitchAwayAndBack()
    {
        var state = State(Source()); var context = state.Context!; var generation = state.Generation;
        state.Observe(new("resolved", Process), Source("other"));
        state.Observe(new("resolved", Process), Source());
        Assert.False(state.Accept(generation, context, Response(context), Now));
        generation = state.Generation;
        state.Observe(new("resolved", "99:639295055776695956"), new("database_unavailable"));
        Assert.False(state.Accept(generation, context, Response(context), Now)); Assert.Null(state.Grant);
    }

    [Theory]
    [InlineData(StaffPromptBypassReason.IdentityUnavailable)]
    [InlineData(StaffPromptBypassReason.ServiceUnavailable)]
    [InlineData(StaffPromptBypassReason.SmsWait)]
    [InlineData(StaffPromptBypassReason.SmsUnavailable)]
    public void ClosingOrBypassingUnknownSourceNeverSpamsAndRejectsAnInFlightReply(StaffPromptBypassReason reason)
    {
        var state = State(); var context = state.Context!; var generation = state.Generation;
        state.Bypass(reason);
        Assert.False(state.Accept(generation, context, Response(context), Now));
        state.Observe(new("resolved", Process), new("ambiguous_session"));
        state.Observe(new("resolved", Process), Source());
        Assert.False(state.NeedsPrompt(Now)); Assert.Null(state.Grant); Assert.NotNull(state.BypassStatus);
        var retry = state.Context!; Assert.True(state.Accept(state.Generation, retry, Response(retry), Now));
        Assert.Null(state.BypassStatus);
    }

    [Fact]
    public void MalformedForeignOrExpiredServerProofCannotAuthenticate()
    {
        var state = State(); var context = state.Context!; var valid = Response(context);
        foreach (var response in new[] { valid with { Status = "pending" }, valid with { SessionToken = null },
            valid with { SessionToken = " " }, valid with { ExpiresAt = Now }, valid with { Pharmacist = new("", "Synthetic") },
            valid with { DeviceContext = null }, valid with { DeviceContext = new("foreign-process") },
            valid with { DeviceContext = context with { StandardNContext = StaffDeviceSessionState.SourceContext(Source().Session!) } } })
            Assert.False(state.Accept(state.Generation, context, response, Now));
        Assert.False(state.IsAuthenticated(Now));
    }

    [Fact]
    public void ExpirationStartsANewPromptGenerationWithoutAcceptingAnOldResponse()
    {
        var state = State(); var context = state.Context!; var generation = state.Generation; Login(state);
        Assert.False(state.IsAuthenticated(Now.AddHours(9))); Assert.True(state.NeedsPrompt(Now.AddHours(9)));
        Assert.False(state.Accept(generation, context, Response(context) with { ExpiresAt = Now.AddHours(10) }, Now.AddHours(9)));
    }
}
