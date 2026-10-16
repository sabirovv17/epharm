using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StaffPasswordValidationTests
{
    [Theory]
    [InlineData("")]
    [InlineData("short")]
    [InlineData("1234567")]
    public void TooShortNewPasswordHasSpecificError(string password) =>
        Assert.Equal("PASSWORD_LENGTH", StaffPasswordValidation.Error(password, password));

    [Fact]
    public void OverCharacterLimitIsRejected() =>
        Assert.Equal("PASSWORD_LENGTH", StaffPasswordValidation.Error(new string('a', 129), new string('a', 129)));

    [Fact]
    public void MatchingConfirmationIsMandatory() =>
        Assert.Equal("PASSWORD_MISMATCH", StaffPasswordValidation.Error("valid-password", "different-password"));

    [Fact]
    public void ExactByteBoundaryIsAcceptedButNeverTruncated()
    {
        Assert.Null(StaffPasswordValidation.Error(new string('a', 72), new string('a', 72)));
        Assert.Equal("PASSWORD_BYTES", StaffPasswordValidation.Error(new string('a', 73), new string('a', 73)));
    }

    [Fact]
    public void MultibyteCharactersUseUtf8BytesRatherThanCharacterCount()
    {
        Assert.Null(StaffPasswordValidation.Error(new string('я', 36), new string('я', 36)));
        Assert.Equal("PASSWORD_BYTES", StaffPasswordValidation.Error(new string('я', 37), new string('я', 37)));
        var emoji = string.Concat(Enumerable.Repeat("🙂", 18));
        Assert.Null(StaffPasswordValidation.Error(emoji, emoji));
        Assert.Equal("PASSWORD_BYTES", StaffPasswordValidation.Error(emoji + "🙂", emoji + "🙂"));
    }

    [Fact]
    public void ComparisonDoesNotTrimOrNormalizePasswords()
    {
        Assert.Null(StaffPasswordValidation.Error("  password  ", "  password  "));
        Assert.Equal("PASSWORD_MISMATCH", StaffPasswordValidation.Error("  password  ", "password"));
    }
}
