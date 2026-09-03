using System;
using System.IO;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;

namespace CustomerDisplay.Services
{
    internal sealed class FulfillmentDeviceCredential
    {
        public string DeviceId { get; set; } = "";
        public string PharmacyId { get; set; } = "";
        public string Token { get; set; } = "";
    }

    internal sealed class FulfillmentCredentialStore
    {
        private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("Epharm.POSM.Fulfillment.v1");
        private readonly string _path;

        public FulfillmentCredentialStore(string path) => _path = path;

        public FulfillmentDeviceCredential? Load(string deviceId, string pharmacyId)
        {
            try
            {
                if (!File.Exists(_path)) return null;
                var encrypted = File.ReadAllBytes(_path);
                var plain = ProtectedData.Unprotect(encrypted, Entropy, DataProtectionScope.CurrentUser);
                var credential = JsonSerializer.Deserialize<FulfillmentDeviceCredential>(plain, EpharmJson.Options);
                if (credential == null ||
                    !string.Equals(credential.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase) ||
                    !string.Equals(credential.PharmacyId, pharmacyId, StringComparison.Ordinal) ||
                    credential.Token.Length < 32)
                {
                    return null;
                }
                return credential;
            }
            catch
            {
                return null;
            }
        }

        public void Save(FulfillmentDeviceCredential credential)
        {
            var directory = Path.GetDirectoryName(_path);
            if (string.IsNullOrWhiteSpace(directory)) throw new InvalidOperationException("Credential directory is missing.");
            Directory.CreateDirectory(directory);

            var plain = JsonSerializer.SerializeToUtf8Bytes(credential, EpharmJson.Options);
            var encrypted = ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser);
            var temp = _path + ".tmp";
            File.WriteAllBytes(temp, encrypted);
            File.Move(temp, _path, true);
            RestrictAcl();
        }

        public void Delete()
        {
            try { if (File.Exists(_path)) File.Delete(_path); } catch { }
        }

        private void RestrictAcl()
        {
            var currentUser = WindowsIdentity.GetCurrent().User
                ?? throw new InvalidOperationException("Current Windows identity is unavailable.");
            var system = new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null);
            var acl = new FileSecurity();
            acl.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
            acl.AddAccessRule(new FileSystemAccessRule(currentUser, FileSystemRights.FullControl, AccessControlType.Allow));
            acl.AddAccessRule(new FileSystemAccessRule(system, FileSystemRights.FullControl, AccessControlType.Allow));
            new FileInfo(_path).SetAccessControl(acl);
        }
    }
}
