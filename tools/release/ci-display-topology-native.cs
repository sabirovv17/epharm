// Ephemeral Windows CI only. No certificate, boot, session or security policy changes.
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;

namespace EpharmCiDisplay {
    public static class Native {
        const string Hardware = "usbmmIdd";
        static readonly Guid DisplayClass = new Guid("4d36e968-e325-11ce-bfc1-08002be10318");
        [StructLayout(LayoutKind.Sequential)] struct Device { public uint Size; public Guid Class; public uint Node; public IntPtr Reserved; }
        [StructLayout(LayoutKind.Sequential)] struct RemoveParams { public uint Size, Function, Scope, Profile; }
        [StructLayout(LayoutKind.Sequential)] struct Rect { public int Left, Top, Right, Bottom; }
        [DllImport("setupapi.dll", SetLastError=true)] static extern IntPtr SetupDiCreateDeviceInfoList(ref Guid cls, IntPtr parent);
        [DllImport("setupapi.dll", EntryPoint="SetupDiCreateDeviceInfoList", SetLastError=true)] static extern IntPtr SetupDiCreateUnclassifiedDeviceInfoList(IntPtr cls, IntPtr parent);
        [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool SetupDiCreateDeviceInfo(IntPtr set, string name, ref Guid cls, string desc, IntPtr parent, uint flags, ref Device dev);
        [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool SetupDiOpenDeviceInfo(IntPtr set, string id, IntPtr parent, uint flags, ref Device dev);
        [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool SetupDiSetDeviceRegistryProperty(IntPtr set, ref Device dev, uint property, byte[] value, uint length);
        [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool SetupDiGetDeviceRegistryProperty(IntPtr set, ref Device dev, uint property, out uint type, byte[] value, uint length, out uint needed);
        [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool SetupDiGetDeviceInstanceId(IntPtr set, ref Device dev, StringBuilder id, uint length, out uint needed);
        [DllImport("setupapi.dll", SetLastError=true)] static extern bool SetupDiSetClassInstallParams(IntPtr set, ref Device dev, ref RemoveParams data, uint size);
        [DllImport("setupapi.dll", SetLastError=true)] static extern bool SetupDiCallClassInstaller(uint function, IntPtr set, ref Device dev);
        [DllImport("setupapi.dll")] static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
        [DllImport("newdev.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool UpdateDriverForPlugAndPlayDevices(IntPtr parent, string hardware, string inf, uint flags, out bool reboot);
        [DllImport("user32.dll")] static extern IntPtr GetProcessWindowStation();
        [DllImport("user32.dll")] static extern IntPtr GetThreadDesktop(uint thread);
        [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
        [DllImport("user32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool GetUserObjectInformation(IntPtr obj, int index, StringBuilder name, uint size, out uint needed);
        [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
        [DllImport("user32.dll")] static extern int SetDisplayConfig(uint paths, IntPtr pathArray, uint modes, IntPtr modeArray, uint flags);
        [DllImport("kernel32.dll")] public static extern uint WTSGetActiveConsoleSessionId();
        static Device NewDevice() { var d = new Device(); d.Size = (uint)Marshal.SizeOf(typeof(Device)); return d; }
        static void Check(bool ok) { if (!ok) throw new Win32Exception(Marshal.GetLastWin32Error()); }
        static IntPtr NewSet() { Guid g = DisplayClass; var h = SetupDiCreateDeviceInfoList(ref g, IntPtr.Zero); if (h == new IntPtr(-1)) throw new Win32Exception(); return h; }
        static IntPtr NewUnclassifiedSet() { var h = SetupDiCreateUnclassifiedDeviceInfoList(IntPtr.Zero, IntPtr.Zero); if (h == new IntPtr(-1)) throw new Win32Exception(); return h; }
        static void SetText(IntPtr set, ref Device dev, uint key, string value) { byte[] b = Encoding.Unicode.GetBytes(value + "\0\0"); Check(SetupDiSetDeviceRegistryProperty(set, ref dev, key, b, (uint)b.Length)); }
        static string GetText(IntPtr set, ref Device dev, uint key) { uint type, needed; byte[] b = new byte[4096]; Check(SetupDiGetDeviceRegistryProperty(set, ref dev, key, out type, b, (uint)b.Length, out needed)); return Encoding.Unicode.GetString(b, 0, (int)needed).TrimEnd('\0'); }
        static void Delete(IntPtr set, ref Device dev) { var p = new RemoveParams { Size=8, Function=5, Scope=1, Profile=0 }; Check(SetupDiSetClassInstallParams(set, ref dev, ref p, (uint)Marshal.SizeOf(typeof(RemoveParams)))); Check(SetupDiCallClassInstaller(5, set, ref dev)); }
        public static string Create(string owner) {
            if (!owner.StartsWith("EpharmCiDisplay-", StringComparison.Ordinal)) throw new ArgumentException("owner");
            IntPtr set = NewSet(); Device dev = NewDevice(); bool registered=false, returned=false;
            try {
                Guid cls=DisplayClass; Check(SetupDiCreateDeviceInfo(set, "Display", ref cls, owner, IntPtr.Zero, 1, ref dev));
                SetText(set, ref dev, 1, Hardware); SetText(set, ref dev, 12, owner);
                Check(SetupDiCallClassInstaller(0x19, set, ref dev)); registered=true;
                var id = new StringBuilder(1024); uint needed; Check(SetupDiGetDeviceInstanceId(set, ref dev, id, (uint)id.Capacity, out needed));
                returned=true; return id.ToString();
            } finally { try { if (registered && !returned) Delete(set, ref dev); } finally { SetupDiDestroyDeviceInfoList(set); } }
        }
        public static void Mark(string id, string owner) {
            IntPtr set=NewSet(); Device dev=NewDevice();
            try { Check(SetupDiOpenDeviceInfo(set,id,IntPtr.Zero,0,ref dev)); if (!String.Equals(GetText(set,ref dev,1).Split('\0')[0],Hardware,StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("hardware"); SetText(set,ref dev,12,owner); }
            finally { SetupDiDestroyDeviceInfoList(set); }
        }
        public static void Remove(string id, string owner) {
            if (!id.StartsWith(@"ROOT\DISPLAY\", StringComparison.OrdinalIgnoreCase) || !owner.StartsWith("EpharmCiDisplay-", StringComparison.Ordinal)) throw new ArgumentException("owner");
            // A failed install can change the devnode class; identity still requires both owned markers.
            IntPtr set=NewUnclassifiedSet(); Device dev=NewDevice();
            try { Check(SetupDiOpenDeviceInfo(set,id,IntPtr.Zero,0,ref dev)); if (!String.Equals(GetText(set,ref dev,1).Split('\0')[0],Hardware,StringComparison.OrdinalIgnoreCase) || GetText(set,ref dev,12) != owner) throw new InvalidOperationException("ownership"); Delete(set,ref dev); }
            finally { SetupDiDestroyDeviceInfoList(set); }
        }
        static string ObjectName(IntPtr h) { uint needed; var b=new StringBuilder(256); Check(GetUserObjectInformation(h,2,b,512,out needed)); return b.ToString(); }
        public static string Station() { return ObjectName(GetProcessWindowStation()); }
        public static string Desktop() { return ObjectName(GetThreadDesktop(GetCurrentThreadId())); }
        public static int Extend() { return SetDisplayConfig(0,IntPtr.Zero,0,IntPtr.Zero,0x84); }
        public static bool Sentinel(IntPtr hwnd, string path, int screenX, int screenY, int screenWidth, int screenHeight) {
            Rect r; Check(GetWindowRect(hwnd,out r)); int w=r.Right-r.Left, h=r.Bottom-r.Top;
            if (w<60 || h<40 || w>2000 || h>1000) throw new InvalidOperationException("sentinel bounds");
            if (r.Left<screenX || r.Top<screenY || r.Right>screenX+screenWidth || r.Bottom>screenY+screenHeight) throw new InvalidOperationException("sentinel screen");
            using (var b=new Bitmap(w,h,PixelFormat.Format32bppArgb)) {
                using (var g=Graphics.FromImage(b)) g.CopyFromScreen(r.Left,r.Top,0,0,new Size(w,h),CopyPixelOperation.SourceCopy);
                b.Save(path,ImageFormat.Png);
                Color[] expected={Color.FromArgb(225,29,72),Color.FromArgb(34,197,94),Color.FromArgb(37,99,235)};
                for (int i=0;i<3;i++) { Color c=b.GetPixel((2*i+1)*w/6,h/2), e=expected[i]; if (Math.Abs(c.R-e.R)>12 || Math.Abs(c.G-e.G)>12 || Math.Abs(c.B-e.B)>12) return false; }
            }
            return true;
        }
        [STAThread] public static int Main(string[] args) {
            try {
                if (args.Length==1 && args[0]=="screens") {
                    var parts=new System.Collections.Generic.List<string>();
                    foreach (var s in Screen.AllScreens) { var r=s.Bounds; parts.Add("{\"x\":"+r.X+",\"y\":"+r.Y+",\"width\":"+r.Width+",\"height\":"+r.Height+",\"primary\":"+s.Primary.ToString().ToLowerInvariant()+"}"); }
                    Console.WriteLine("["+String.Join(",",parts.ToArray())+"]"); return 0;
                }
                if (args.Length==2 && args[0]=="install") { bool reboot; Check(UpdateDriverForPlugAndPlayDevices(IntPtr.Zero,Hardware,System.IO.Path.GetFullPath(args[1]),4,out reboot)); return reboot ? 3010 : 0; }
                return 2;
            } catch (Exception error) {
                var native = error as Win32Exception;
                Console.Error.WriteLine("CI_DISPLAY_NATIVE_FAILURE type=" + error.GetType().FullName + " code=" + (native == null ? error.HResult : native.NativeErrorCode));
                return 1;
            }
        }
    }
}
