using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class RecommendationPopupPlacementTests
{
    [Theory]
    [InlineData(1.00, 1920, 1040, 500, 900)]
    [InlineData(1.25, 1920, 1040, 500, 800)]
    [InlineData(1.50, 1366, 728, 500, 458.6666666667)]
    [InlineData(2.00, 800, 560, 380, 260)]
    public void ConstraintsKeepPopupInsideScaledWorkArea(
        double scale,
        int physicalWidth,
        int physicalHeight,
        double expectedWidthDip,
        double expectedMaxHeightDip)
    {
        var constraints = PopupWindowPlacement.ConstrainDipSize(
            new PixelRect(0, 0, physicalWidth, physicalHeight),
            scale,
            scale,
            desiredWidthDip: 500,
            desiredMaxHeightDip: 900);

        Assert.Equal(expectedWidthDip, constraints.Width, precision: 6);
        Assert.Equal(expectedMaxHeightDip, constraints.MaxHeight, precision: 6);
        Assert.True(constraints.Width * scale + 40 <= physicalWidth + 0.001);
        Assert.True(constraints.MaxHeight * scale + 40 <= physicalHeight + 0.001);
    }

    [Fact]
    public void BottomRightPlacementUsesPhysicalPixelsAndSupportsNegativeMonitorOrigins()
    {
        var workArea = new PixelRect(-1920, 0, 1920, 1040);
        var point = PopupWindowPlacement.BottomRight(workArea, 588, 900);
        var popup = new PixelRect(point.X, point.Y, 588, 900);

        Assert.Equal(-608, point.X);
        Assert.Equal(120, point.Y);
        Assert.True(PopupWindowPlacement.IsFullyVisible(popup, workArea));
    }

    [Fact]
    public void TopRightPlacementUsesPhysicalPixelsAndSupportsNegativeMonitorOrigins()
    {
        var workArea = new PixelRect(-1920, 0, 1920, 1040);
        var point = PopupWindowPlacement.TopRight(workArea, 750, 900);
        var popup = new PixelRect(point.X, point.Y, 750, 900);

        Assert.Equal(-770, point.X);
        Assert.Equal(20, point.Y);
        Assert.True(PopupWindowPlacement.IsFullyVisible(popup, workArea));
    }

    [Fact]
    public void TopRightPlacementClampsWhenWorkAreaIsTooSmall()
    {
        var workArea = new PixelRect(100, 50, 640, 480);
        var point = PopupWindowPlacement.TopRight(workArea, 700, 520);

        Assert.Equal(workArea.Left, point.X);
        Assert.Equal(workArea.Top, point.Y);
    }

    [Fact]
    public void PlacementClampsToWorkAreaWhenPopupIsLargerThanAvailableSpace()
    {
        var workArea = new PixelRect(100, 50, 640, 480);
        var point = PopupWindowPlacement.BottomRight(workArea, 700, 520);

        Assert.Equal(workArea.Left, point.X);
        Assert.Equal(workArea.Top, point.Y);
    }
}
