// Private synthetic recovery fixture only. No credentials, external requests or application seams.
using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

public sealed class EpharmRecoveryLoopback : IDisposable
{
    const string Origin = "http://127.0.0.1:18761/";
    readonly HttpListener listener = new HttpListener();
    readonly Thread thread;
    readonly string mockRoot;
    readonly string identity = Guid.NewGuid().ToString("N");
    public readonly ConcurrentQueue<string> Trace = new ConcurrentQueue<string>();

    public EpharmRecoveryLoopback(string root)
    {
        mockRoot = Path.Combine(root, "mock");
        listener.Prefixes.Add(Origin);
        thread = new Thread(Run);
        thread.IsBackground = true;
        try {
            listener.Start();
            thread.Start();
            var request = Request("__fixture/ready");
            using (var response = request.GetResponse())
            using (var reader = new StreamReader(response.GetResponseStream()))
                if (reader.ReadToEnd() != identity) throw new InvalidOperationException("Loopback readiness identity mismatch");
        } catch {
            listener.Close();
            if (thread.IsAlive && !thread.Join(2000)) throw new InvalidOperationException("Failed loopback startup left a running thread");
            throw;
        }
    }

    static HttpWebRequest Request(string path)
    {
        var request = (HttpWebRequest)WebRequest.Create(Origin + path);
        request.Proxy = null;
        request.AllowAutoRedirect = false;
        request.KeepAlive = false;
        request.Timeout = 1000;
        request.ReadWriteTimeout = 1000;
        return request;
    }

    void Run()
    {
        while (listener.IsListening) {
            HttpListenerContext context;
            try { context = listener.GetContext(); }
            catch (HttpListenerException) { break; }
            catch (ObjectDisposedException) { break; }
            var response = context.Response;
            try {
                string path = context.Request.Url.AbsolutePath;
                Trace.Enqueue(DateTime.UtcNow.ToString("o") + " " + context.Request.HttpMethod + " " + path);
                response.KeepAlive = false;
                response.ContentType = "application/json; charset=utf-8";
                byte[] body;
                if (context.Request.HttpMethod != "GET" && context.Request.HttpMethod != "HEAD") {
                    response.StatusCode = 503; body = Encoding.UTF8.GetBytes("{\"syntheticOffline\":true}");
                } else if (path == "/__fixture/ready") body = Encoding.UTF8.GetBytes(identity);
                else if (path == "/api/posm/app/version") body = File.ReadAllBytes(Path.Combine(mockRoot, "manifest.current.json"));
                else if (path == "/candidate.zip") {
                    response.ContentType = "application/zip"; body = File.ReadAllBytes(Path.Combine(mockRoot, "candidate.zip"));
                } else { response.StatusCode = 503; body = Encoding.UTF8.GetBytes("{\"syntheticOffline\":true}"); }
                response.ContentLength64 = body.Length;
                if (context.Request.HttpMethod != "HEAD") response.OutputStream.Write(body, 0, body.Length);
            } catch (Exception error) { Trace.Enqueue("response-error:" + error.GetType().Name); }
            finally { try { response.Close(); } catch (Exception error) { Trace.Enqueue("close-error:" + error.GetType().Name); } }
        }
    }

    public void Dispose()
    {
        listener.Close();
        if (!thread.Join(2000)) throw new InvalidOperationException("Owned loopback thread failed to stop");
    }

    public static void AssertOffline()
    {
        var deadline = Stopwatch.StartNew();
        string last = "unprobed";
        while (deadline.ElapsedMilliseconds < 5000) {
            try { using (var response = Request("api/posm/app/version").GetResponse()) last = "HTTP response still available"; }
            catch (WebException error) {
                var socket = error.GetBaseException() as SocketException;
                if (socket != null && socket.SocketErrorCode == SocketError.ConnectionRefused) return;
                last = "WebException:" + error.Status + ";base=" + error.GetBaseException().GetType().Name;
            }
            Thread.Sleep(100);
        }
        throw new InvalidOperationException("Offline loopback endpoint did not refuse HTTP: " + last);
    }
}
