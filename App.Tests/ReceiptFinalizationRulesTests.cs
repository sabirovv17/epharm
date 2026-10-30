using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class ReceiptFinalizationRulesTests
{
    [Fact]
    public void OnlyTheExistingExactPrintSignalConfirmsFinalization() =>
        Assert.True(ReceiptFinalizationRules.IsConfirmedPrint("standardn-print-log"));

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("standardn-document-switch")]
    [InlineData("standardn-firebird-close")]
    [InlineData("standardn-active")]
    [InlineData("cash-log-active")]
    [InlineData("STANDARDN-PRINT-LOG")]
    [InlineData("standardn-print-log-unverified")]
    public void UnconfirmedOrLookalikeSignalsCannotFinalize(string? source) =>
        Assert.False(ReceiptFinalizationRules.IsConfirmedPrint(source));
}
