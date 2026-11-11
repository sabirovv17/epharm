// Private synthetic recovery fixture only. No credentials, external requests or application seams.
using System;
using System.Collections.Concurrent;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
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
    public bool ListenerClosed { get; private set; }
    public bool ThreadJoined { get; private set; }

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
        ListenerClosed = true;
        if (!thread.Join(2000)) throw new InvalidOperationException("Owned loopback thread failed to stop");
        ThreadJoined = true;
    }

    public EpharmRecoveryOfflineProof AssertOffline()
    {
        if (!ListenerClosed || !ThreadJoined || thread.IsAlive) throw new InvalidOperationException("Owned listener and thread must stop before proving HTTP unavailable");
        var deadline = Stopwatch.StartNew();
        try {
            using (var response = Request("api/posm/app/version").GetResponse())
                throw new InvalidOperationException("Stopped mock endpoint still returned an HTTP response");
        } catch (WebException error) {
            if (error.Response != null) {
                error.Response.Close();
                throw new InvalidOperationException("Stopped mock endpoint still returned an HTTP error response");
            }
            var socket = error.GetBaseException() as SocketException;
            bool refused = socket != null && socket.SocketErrorCode == SocketError.ConnectionRefused;
            if (!refused && error.Status != WebExceptionStatus.ConnectFailure && error.Status != WebExceptionStatus.Timeout)
                throw new InvalidOperationException("Unexpected offline probe failure: " + error.Status);
            return new EpharmRecoveryOfflineProof {
                ListenerClosed = true, ThreadJoined = true, HttpUnavailable = true,
                FailureKind = error.Status.ToString(), SocketFailureKind = socket == null ? null : socket.SocketErrorCode.ToString(),
                HttpConnectionRefused = refused, ProbeMilliseconds = deadline.ElapsedMilliseconds
            };
        }
    }
}

public sealed class EpharmRecoveryOfflineProof
{
    public bool ListenerClosed, ThreadJoined, HttpUnavailable, HttpConnectionRefused;
    public string FailureKind, SocketFailureKind;
    public long ProbeMilliseconds;
}

public sealed class EpharmRecoveryWindow
{
    public long Handle;
    public int ProcessId, Left, Top, Right, Bottom;
    public string Title;
    public bool Visible, Responding;
}

public static class EpharmRecoveryWindows
{
    [StructLayout(LayoutKind.Sequential)] struct Rect { public int Left, Top, Right, Bottom; }
    delegate bool EnumWindow(IntPtr window, IntPtr unused);
    [DllImport("user32.dll", SetLastError = true)] static extern bool EnumWindows(EnumWindow callback, IntPtr unused);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr window);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr window, StringBuilder text, int length);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr window, out Rect bounds);
    [DllImport("user32.dll", SetLastError = true)] static extern IntPtr SendMessageTimeout(IntPtr window, uint message, UIntPtr wParam, IntPtr lParam, uint flags, uint timeout, out UIntPtr result);
    [DllImport("user32.dll", SetLastError = true)] static extern bool PostMessage(IntPtr window, uint message, UIntPtr wParam, IntPtr lParam);

    static EpharmRecoveryWindow ReadWindow(IntPtr window, int owner)
    {
        uint processId; GetWindowThreadProcessId(window, out processId);
        if (processId != (uint)owner || !IsWindowVisible(window)) return null;
        var title = new StringBuilder(256); GetWindowText(window, title, title.Capacity);
        string value = title.ToString();
        // The immutable source changes this exact title in ScreenMode=dev.
        if (value != "Customer Display" && value != "Epharm POSM \u2014 DEV (\u043e\u043a\u043d\u043e \u0441\u043b\u0435\u0432\u0430)") return null;
        Rect bounds;
        if (!GetWindowRect(window, out bounds) || bounds.Right <= bounds.Left || bounds.Bottom <= bounds.Top) return null;
        return new EpharmRecoveryWindow { Handle = window.ToInt64(), ProcessId = owner, Title = value, Visible = true,
            Left = bounds.Left, Top = bounds.Top, Right = bounds.Right, Bottom = bounds.Bottom };
    }

    public static EpharmRecoveryWindow Find(int owner)
    {
        EpharmRecoveryWindow found = null;
        EnumWindow callback = delegate(IntPtr window, IntPtr unused) {
            if (found != null) return true;
            var candidate = ReadWindow(window, owner);
            if (candidate == null) return true;
            UIntPtr result;
            candidate.Responding = SendMessageTimeout(window, 0, UIntPtr.Zero, IntPtr.Zero, 3, 500, out result) != IntPtr.Zero;
            uint currentOwner; GetWindowThreadProcessId(window, out currentOwner);
            if (currentOwner == (uint)owner) found = candidate;
            return true;
        };
        if (!EnumWindows(callback, IntPtr.Zero)) throw new Win32Exception(Marshal.GetLastWin32Error());
        return found;
    }

    public static void RequestClose(long handle, int owner)
    {
        var window = new IntPtr(handle);
        if (ReadWindow(window, owner) == null) throw new InvalidOperationException("Owned visible app HWND changed before WM_CLOSE");
        if (!PostMessage(window, 0x0010, UIntPtr.Zero, IntPtr.Zero)) throw new Win32Exception(Marshal.GetLastWin32Error());
    }
}
