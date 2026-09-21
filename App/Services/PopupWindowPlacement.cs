using System;

namespace CustomerDisplay.Services;

internal readonly record struct PixelPoint(int X, int Y);

internal readonly record struct PixelRect(int Left, int Top, int Width, int Height)
{
    public int Right => Left + Width;
    public int Bottom => Top + Height;
}

internal readonly record struct PopupDipConstraints(double Width, double MaxHeight);

/// <summary>
/// Pure placement calculations for a WPF popup positioned through Win32. Screen.WorkingArea and
/// GetWindowRect are both physical pixels, which avoids mixing them with WPF device-independent
/// coordinates on 125-200% scaled cash-desk displays.
/// </summary>
internal static class PopupWindowPlacement
{
    public const int DefaultMarginPixels = 20;

    public static PopupDipConstraints ConstrainDipSize(
        PixelRect workArea,
        double dpiScaleX,
        double dpiScaleY,
        double desiredWidthDip,
        double desiredMaxHeightDip,
        int marginPixels = DefaultMarginPixels)
    {
        dpiScaleX = dpiScaleX > 0 ? dpiScaleX : 1;
        dpiScaleY = dpiScaleY > 0 ? dpiScaleY : 1;
        marginPixels = Math.Max(0, marginPixels);

        var availableWidthPixels = Math.Max(1, workArea.Width - marginPixels * 2);
        var availableHeightPixels = Math.Max(1, workArea.Height - marginPixels * 2);

        return new PopupDipConstraints(
            Math.Max(1, Math.Min(desiredWidthDip, availableWidthPixels / dpiScaleX)),
            Math.Max(1, Math.Min(desiredMaxHeightDip, availableHeightPixels / dpiScaleY)));
    }

    public static PixelPoint BottomRight(
        PixelRect workArea,
        int windowWidth,
        int windowHeight,
        int marginPixels = DefaultMarginPixels)
    {
        marginPixels = Math.Max(0, marginPixels);
        windowWidth = Math.Max(1, windowWidth);
        windowHeight = Math.Max(1, windowHeight);

        var x = workArea.Right - windowWidth - marginPixels;
        var y = workArea.Bottom - windowHeight - marginPixels;
        return new PixelPoint(Math.Max(workArea.Left, x), Math.Max(workArea.Top, y));
    }

    public static bool IsFullyVisible(PixelRect window, PixelRect workArea) =>
        window.Width > 0 &&
        window.Height > 0 &&
        window.Left >= workArea.Left &&
        window.Top >= workArea.Top &&
        window.Right <= workArea.Right &&
        window.Bottom <= workArea.Bottom;
}
