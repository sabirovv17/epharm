using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests
{
    public sealed class PlaylistPlaybackTests
    {
        [Fact]
        public async Task RemovingFinalVideoStopsPlaybackAndPreventsOfflineCacheResurrection()
        {
            await using var server = new PlaylistServer(new ActivePlaylist { PlaylistId = "pl_broadcast" });
            using var window = new MainWindow();
            window.ConfigureScreenTest(server.Origin);
            try
            {
                var old = window.CacheScreenVideo("https://synthetic.invalid/old.mp4");
                window.StartScreenTest([old]);
                await window.PollScreenTest();
                Assert.Empty(window.PlayingScreenSources);
                Assert.Equal(1, window.ScreenStopCount);
                window.RestartScreenCacheTest();
                Assert.Empty(window.PlayingScreenSources);
                Assert.Equal(1, window.ScreenPlayCount);
                var manifest = JsonDocument.Parse(File.ReadAllText(Path.Combine(window.StorageRoot, "media-cache", "playlist-manifest.json")));
                Assert.Equal(0, manifest.RootElement.GetProperty("urls").GetArrayLength());
            }
            finally { window.DisposeScreenTest(); }
        }

        [Fact]
        public async Task NewPlaylistImmediatelyExcludesRemovedCachedVideoWhileReplacementWarms()
        {
            const string keptUrl = "https://synthetic.invalid/kept.mp4";
            await using var server = new PlaylistServer(new ActivePlaylist
            {
                PlaylistId = "pl_broadcast",
                Slides = [new() { Kind = "video", Url = keptUrl }, new() { Kind = "video", Url = "file:///not-downloaded.mp4" }],
            });
            using var window = new MainWindow();
            window.ConfigureScreenTest(server.Origin);
            try
            {
                var removed = window.CacheScreenVideo("https://synthetic.invalid/removed.mp4");
                var kept = window.CacheScreenVideo(keptUrl);
                window.StartScreenTest([removed, kept]);
                await window.PollScreenTest();
                Assert.Equal([kept], window.PlayingScreenSources);
                Assert.DoesNotContain(removed, window.PlayingScreenSources);
                window.RestartScreenCacheTest();
                Assert.Equal([kept], window.PlayingScreenSources);
            }
            finally { window.DisposeScreenTest(); }
        }

        [Fact]
        public async Task NetworkFailureKeepsCurrentCachedPlaylist()
        {
            await using var server = new PlaylistServer(null, status: 503);
            using var window = new MainWindow();
            window.ConfigureScreenTest(server.Origin);
            try
            {
                var old = window.CacheScreenVideo("https://synthetic.invalid/old.mp4");
                window.StartScreenTest([old]);
                await window.PollScreenTest();
                Assert.Equal([old], window.PlayingScreenSources);
                Assert.Equal(0, window.ScreenStopCount);
                Assert.Equal(1, window.ScreenPlayCount);
            }
            finally { window.DisposeScreenTest(); }
        }

        [Fact]
        public async Task IdenticalPlaylistDoesNotRestartVideo()
        {
            const string url = "https://synthetic.invalid/kept.mp4";
            await using var server = new PlaylistServer(new ActivePlaylist
            {
                PlaylistId = "pl_broadcast", Slides = [new() { Kind = "video", Url = url }],
            }, requests: 2);
            using var window = new MainWindow();
            window.ConfigureScreenTest(server.Origin);
            try
            {
                window.CacheScreenVideo(url);
                await window.PollScreenTest();
                await window.PollScreenTest();
                Assert.Equal(1, window.ScreenPlayCount);
                Assert.Equal(0, window.ScreenStopCount);
            }
            finally { window.DisposeScreenTest(); }
        }

        private sealed class PlaylistServer : IAsyncDisposable
        {
            private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
            private readonly Task _run;
            public string Origin { get; }
            public PlaylistServer(ActivePlaylist? playlist, int status = 200, int requests = 1)
            {
                _listener.Start();
                Origin = $"http://127.0.0.1:{((IPEndPoint)_listener.LocalEndpoint).Port}";
                var payload = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(playlist, EpharmJson.Options));
                _run = Task.Run(async () =>
                {
                    for (var i = 0; i < requests; i++)
                    {
                        using var client = await _listener.AcceptTcpClientAsync();
                        await using var stream = client.GetStream();
                        using var reader = new StreamReader(stream, leaveOpen: true);
                        var request = await reader.ReadLineAsync();
                        Assert.StartsWith("GET /api/posm/playlists/active?", request);
                        while (await reader.ReadLineAsync() is { Length: > 0 }) { }
                        var header = Encoding.ASCII.GetBytes($"HTTP/1.1 {status} Synthetic\r\nContent-Type: application/json\r\nContent-Length: {payload.Length}\r\nConnection: close\r\n\r\n");
                        await stream.WriteAsync(header);
                        await stream.WriteAsync(payload);
                    }
                });
            }
            public async ValueTask DisposeAsync()
            {
                _listener.Stop();
                await _run.WaitAsync(TimeSpan.FromSeconds(10));
            }
        }
    }
}

namespace CustomerDisplay
{
    // Compile the real playlist partial; only dispatcher, monitor topology and VLC are fakes.
    public partial class MainWindow
    {
        private EpharmApiClient? _epharm;
        private LibVLCSharp.Shared.LibVLC? _libVLC;
        private LibVLCSharp.Shared.MediaPlayer? _mediaPlayer;
        private LibVLCSharp.Shared.Media? _currentMedia;
        internal IReadOnlyList<string> PlayingScreenSources => _videoSources;
        internal int ScreenStopCount => _mediaPlayer!.StopCount;
        internal int ScreenPlayCount => _mediaPlayer!.PlayCount;
        internal void ConfigureScreenTest(string origin)
        {
            _posmConfig.BackendBaseUrl = origin;
            _posmConfig.BackendFallbackBaseUrls = [];
            _posmConfig.MediaCacheDir = Path.Combine(StorageRoot, "media-cache");
            _epharm = new EpharmApiClient(_posmConfig);
            _libVLC = new();
            _mediaPlayer = new();
            EnsureMediaCache();
        }
        internal string CacheScreenVideo(string url)
        {
            var name = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(url))).ToLowerInvariant() + ".mp4";
            File.WriteAllBytes(Path.Combine(_posmConfig.MediaCacheDir, name), [1, 2, 3]);
            return _mediaCache!.PreferredSource(url);
        }
        internal void StartScreenTest(List<string> sources) => SwitchVideoSources("old", sources, "synthetic");
        internal Task PollScreenTest() => LoadBackendPlaylistAsync();
        internal void RestartScreenCacheTest() => TryStartCachedPlaylist();
        internal void DisposeScreenTest()
        {
            _mediaWarmupCts?.Cancel();
            _epharm?.Dispose();
            _currentMedia?.Dispose();
            _mediaCache?.Dispose();
        }
    }
}

namespace LibVLCSharp.Shared
{
    public sealed class LibVLC { }
    public sealed class Media : IDisposable
    {
        public Media(LibVLC instance, Uri source) { }
        public void AddOption(string option) { }
        public void Dispose() { }
    }
    public sealed class MediaPlayer
    {
        public bool IsPlaying { get; private set; }
        public long Time => 0;
        public int PlayCount { get; private set; }
        public int StopCount { get; private set; }
        public bool Play(Media media) { PlayCount++; IsPlaying = true; return true; }
        public void Stop() { StopCount++; IsPlaying = false; }
    }
}
namespace System.Windows.Threading
{
    public sealed class DispatcherTimer
    {
        public TimeSpan Interval { get; set; }
        public event EventHandler? Tick { add { } remove { } }
        public void Start() { }
    }
}
namespace System.Windows.Forms
{
    public sealed class Screen { public static Screen[] AllScreens => [new()]; }
}
namespace CustomerDisplay.Services
{
    public static class AppUpdater { public static Version CurrentVersion() => new(1, 0, 72); }
}
