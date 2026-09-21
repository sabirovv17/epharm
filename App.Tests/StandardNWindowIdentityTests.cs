using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StandardNWindowIdentityTests
{
    [Fact]
    public void RecognizesTheProductionWindowCaptionFromTheIncident()
    {
        var score = StandardNWindowIdentity.Score(
            "zkassa",
            "Касса v.2.6.84 Автоматизация Стандарт-Н");

        Assert.True(score >= 100);
    }

    [Theory]
    [InlineData("CustomerDisplay", "Epharm POSM")]
    [InlineData("chrome", "Касса интернет-магазина")]
    [InlineData("explorer", "Рабочий стол")]
    public void RejectsUnrelatedWindows(string process, string title)
    {
        Assert.True(StandardNWindowIdentity.Score(process, title) < 60);
    }
}
