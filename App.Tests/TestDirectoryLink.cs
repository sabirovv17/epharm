using System.Diagnostics;
using Xunit;

namespace CustomerDisplay.Core.Tests;

internal static class TestDirectoryLink
{
    // NTFS junctions exercise the same production ReparsePoint guard without requiring
    // SeCreateSymbolicLinkPrivilege/Developer Mode on a pharmacist's Windows workstation.
    internal static void Create(string link, string target)
    {
        if (!OperatingSystem.IsWindows()) Directory.CreateSymbolicLink(link, target);
        else
        {
            var start = new ProcessStartInfo("cmd.exe", $"/d /c mklink /J \"{link}\" \"{target}\"")
            { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            using var process = Process.Start(start)!;
            Assert.True(process.WaitForExit(5000));
            Assert.True(process.ExitCode == 0, process.StandardError.ReadToEnd());
        }
        Assert.True((File.GetAttributes(link) & FileAttributes.ReparsePoint) != 0);
    }
}
