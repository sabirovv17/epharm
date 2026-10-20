using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class ReceiptObservationGateTests
{
    [Theory]
    [InlineData("CASH-1", "cash-1", true)]
    [InlineData("DOMAIN\\CASH-1", "cash-1", true)]
    [InlineData("CASH-1.inkar.local", "cash-1", true)]
    [InlineData("CASH-10", "cash-1", false)]
    [InlineData("CASH-2", "cash-1", false)]
    [InlineData(null, "cash-1", false)]
    public void OnlyCanonicalExactLocalSessionCanConfirmEmptyCart(
        string? selected, string machine, bool expected)
    {
        Assert.Equal(expected,
            ReceiptObservationGate.CanConfirmEmptyReceipt(new[] { selected }, selected, machine));
    }

    [Fact]
    public void TwoLocalSessionsAreAmbiguousEvenWhenOneWasSelectedByTimestamp()
    {
        Assert.False(ReceiptObservationGate.CanConfirmEmptyReceipt(
            new[] { "CASH-1", "DOMAIN\\CASH-1" }, "CASH-1", "CASH-1"));
    }

    [Fact]
    public void LogOnlyCartIsClearedAfterTwoAuthoritativeEmptyReads()
    {
        var gate = new ReceiptObservationGate();
        gate.LogChanged();

        Assert.True(gate.Admit(gate.Revision, hasReceipt: false));
        Assert.False(gate.ShouldClearMissingReceipt(hasDocument: false, hasItems: true));
        Assert.True(gate.Admit(gate.Revision, hasReceipt: false));
        Assert.True(gate.ShouldClearMissingReceipt(hasDocument: false, hasItems: true));
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void ReadStartedBeforePrintOrNewScanCannotRepopulateOrClearCart(bool hasReceipt)
    {
        var gate = new ReceiptObservationGate();
        var beforeLog = gate.Revision;
        gate.LogChanged();

        Assert.False(gate.Admit(beforeLog, hasReceipt));
        Assert.False(gate.ShouldClearMissingReceipt(hasDocument: true, hasItems: true));
    }

    [Fact]
    public void ActiveReceiptBreaksTheEmptyReadSequence()
    {
        var gate = new ReceiptObservationGate();
        gate.Admit(gate.Revision, hasReceipt: false);
        gate.Admit(gate.Revision, hasReceipt: true);
        gate.Admit(gate.Revision, hasReceipt: false);

        Assert.False(gate.ShouldClearMissingReceipt(hasDocument: true, hasItems: true));
    }

    [Fact]
    public void NewLogActivityRestartsEmptyConfirmation()
    {
        var gate = new ReceiptObservationGate();
        gate.Admit(gate.Revision, hasReceipt: false);
        gate.LogChanged();
        gate.Admit(gate.Revision, hasReceipt: false);

        Assert.False(gate.ShouldClearMissingReceipt(hasDocument: false, hasItems: true));
    }

    [Fact]
    public void DatabaseErrorsAreNotEmptyObservations()
    {
        var gate = new ReceiptObservationGate();
        gate.Admit(gate.Revision, hasReceipt: false);
        gate.ReadFailed();
        gate.Admit(gate.Revision, hasReceipt: false);

        Assert.False(gate.ShouldClearMissingReceipt(hasDocument: true, hasItems: true));
    }

    [Fact]
    public void RepeatedEmptyReadDoesNotResetAnAlreadyEmptyCheckout()
    {
        var gate = new ReceiptObservationGate();
        gate.Admit(gate.Revision, hasReceipt: false);
        gate.Admit(gate.Revision, hasReceipt: false);

        Assert.False(gate.ShouldClearMissingReceipt(hasDocument: false, hasItems: false));
    }
}
