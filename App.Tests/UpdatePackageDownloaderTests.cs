using System.Net;
using System.Net.Http.Headers;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class UpdatePackageDownloaderTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "epharm-update-download-tests-" + Guid.NewGuid().ToString("N"));
    public UpdatePackageDownloaderTests() => Directory.CreateDirectory(_root);
    private string Destination => Path.Combine(_root, "package.zip");

    [Fact]
    public async Task HeadersThenStalledBodyHasIdleDeadlineAndNextCheckCanProceed()
    {
        var requests = 0;
        using var http = new HttpClient(new Handler(request =>
        {
            Assert.False(request.Headers.Contains("X-Posm-Key"));
            return ++requests == 1
                ? new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(new StallStream()) }
                : new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent([1, 2, 3]) };
        }));
        var downloader = new UpdatePackageDownloader(http, _ => { }, TimeSpan.FromSeconds(2), TimeSpan.FromMilliseconds(50), TimeSpan.Zero, 1);
        Assert.False(await downloader.DownloadAsync("https://artifact.invalid/package.zip", Destination, TestContext.Current.CancellationToken).WaitAsync(TimeSpan.FromSeconds(3), TestContext.Current.CancellationToken));
        Assert.True(await downloader.DownloadAsync("https://artifact.invalid/package.zip", Destination, TestContext.Current.CancellationToken));
        Assert.Equal(new byte[] { 1, 2, 3 }, File.ReadAllBytes(Destination));
    }

    [Fact]
    public async Task TotalDeadlineBoundsTrickleBodyAndRetryBudget()
    {
        using var http = new HttpClient(new Handler(_ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(new TrickleStream()) }));
        var downloader = new UpdatePackageDownloader(http, _ => { }, TimeSpan.FromMilliseconds(150), TimeSpan.FromSeconds(1), TimeSpan.Zero, 4);
        Assert.False(await downloader.DownloadAsync("https://artifact.invalid/package.zip", Destination, TestContext.Current.CancellationToken).WaitAsync(TimeSpan.FromSeconds(3), TestContext.Current.CancellationToken));
        Assert.False(File.Exists(Destination));
        Assert.True(File.Exists(Destination + ".part"));
    }

    [Fact]
    public async Task ResumesExactRangeWithoutDeviceCredentials()
    {
        File.WriteAllBytes(Destination + ".part", [1, 2, 3]);
        using var http = new HttpClient(new Handler(request =>
        {
            Assert.Equal("bytes=3-", request.Headers.Range?.ToString());
            Assert.False(request.Headers.Contains("X-Posm-Key"));
            var content = new ByteArrayContent([4, 5, 6]);
            content.Headers.ContentRange = new ContentRangeHeaderValue(3, 5, 6);
            return new HttpResponseMessage(HttpStatusCode.PartialContent) { Content = content };
        }));
        Assert.True(await new UpdatePackageDownloader(http, _ => { }).DownloadAsync("https://artifact.invalid/package.zip", Destination, TestContext.Current.CancellationToken));
        Assert.Equal(new byte[] { 1, 2, 3, 4, 5, 6 }, File.ReadAllBytes(Destination));
    }

    [Fact]
    public async Task WrongResumeOffsetNeverAppendsCorruptBytes()
    {
        File.WriteAllBytes(Destination + ".part", [1, 2, 3]);
        using var http = new HttpClient(new Handler(_ =>
        {
            var content = new ByteArrayContent([8, 9]);
            content.Headers.ContentRange = new ContentRangeHeaderValue(1, 2, 6);
            return new HttpResponseMessage(HttpStatusCode.PartialContent) { Content = content };
        }));
        Assert.False(await new UpdatePackageDownloader(http, _ => { }, attempts: 1).DownloadAsync("https://artifact.invalid/package.zip", Destination, TestContext.Current.CancellationToken));
        Assert.Equal(new byte[] { 1, 2, 3 }, File.ReadAllBytes(Destination + ".part"));
        Assert.False(File.Exists(Destination));
    }

    [Fact]
    public async Task CallerCancellationIsNotSwallowed()
    {
        using var http = new HttpClient(new Handler(_ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(new StallStream()) }));
        using var cancellation = new CancellationTokenSource(TimeSpan.FromMilliseconds(50));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => new UpdatePackageDownloader(http, _ => { })
            .DownloadAsync("https://artifact.invalid/package.zip", Destination, cancellation.Token));
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(true, false)]
    [InlineData(false, true)]
    [InlineData(true, true)]
    public async Task DownloadRejectsDestinationOrPartialReparseBeforeWriting(bool partial, bool afterHeaders)
    {
        var foreign = Path.Combine(_root, "foreign-target");
        Directory.CreateDirectory(foreign);
        var sentinel = Path.Combine(foreign, "do-not-touch.txt");
        File.WriteAllText(sentinel, "foreign-data");
        var link = partial ? Destination + ".part" : Destination;
        var requests = 0;
        using var http = new HttpClient(new Handler(_ =>
        {
            requests++;
            if (afterHeaders) TestDirectoryLink.Create(link, foreign);
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent([1, 2, 3]) };
        }));
        if (!afterHeaders) TestDirectoryLink.Create(link, foreign);
        try
        {
            Assert.False(await new UpdatePackageDownloader(http, _ => { }, attempts: 1)
                .DownloadAsync("https://artifact.invalid/package.zip", Destination, TestContext.Current.CancellationToken));
            Assert.Equal(afterHeaders ? 1 : 0, requests);
            Assert.Equal("foreign-data", File.ReadAllText(sentinel));
            Assert.Equal(new[] { sentinel }, Directory.GetFiles(foreign));
            Assert.True((File.GetAttributes(link) & FileAttributes.ReparsePoint) != 0);
        }
        finally { if (Directory.Exists(link)) Directory.Delete(link, recursive: false); }
    }

    [Fact]
    public async Task DownloadRechecksDestinationAfterBodyBeforePublishing()
    {
        var foreign = Path.Combine(_root, "foreign-target");
        Directory.CreateDirectory(foreign);
        var sentinel = Path.Combine(foreign, "do-not-touch.txt");
        File.WriteAllText(sentinel, "foreign-data");
        using var http = new HttpClient(new Handler(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StreamContent(new EndCallbackStream(() => TestDirectoryLink.Create(Destination, foreign)))
        }));
        try
        {
            Assert.False(await new UpdatePackageDownloader(http, _ => { }, attempts: 1)
                .DownloadAsync("https://artifact.invalid/package.zip", Destination, TestContext.Current.CancellationToken));
            Assert.Equal("foreign-data", File.ReadAllText(sentinel));
            Assert.True((File.GetAttributes(Destination) & FileAttributes.ReparsePoint) != 0);
            Assert.Equal(new byte[] { 1, 2, 3 }, File.ReadAllBytes(Destination + ".part"));
        }
        finally { if (Directory.Exists(Destination)) Directory.Delete(Destination, recursive: false); }
    }

    public void Dispose() => Directory.Delete(_root, true);
    private sealed class Handler(Func<HttpRequestMessage, HttpResponseMessage> handler) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => Task.FromResult(handler(request));
    }
    private sealed class EndCallbackStream(Action completed) : MemoryStream(new byte[] { 1, 2, 3 })
    {
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            var read = await base.ReadAsync(buffer, cancellationToken);
            if (read == 0) completed();
            return read;
        }
    }
    private class StallStream : Stream
    {
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => 0; set => throw new NotSupportedException(); }
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) => new(new TaskCompletionSource<int>().Task);
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
    private sealed class TrickleStream : StallStream
    {
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            await Task.Delay(20, cancellationToken);
            buffer.Span[0] = 1;
            return 1;
        }
    }
}
