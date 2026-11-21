using System;
using System.IO;
using System.Text.Json;

namespace AirBridge.Net
{
    /// <summary>Persistent device identity (§2.1) stored in %LOCALAPPDATA%\AirBridge\identity.json.</summary>
    public sealed class Identity
    {
        private sealed class Stored
        {
            public string? Id { get; set; }
            public string? Name { get; set; }
            public bool CustomName { get; set; }
        }

        private static readonly string FilePath = System.IO.Path.Combine(Log.Dir, "identity.json");

        public string Id { get; }

        /// <summary>
        /// A fingerprint of this PC that survives reinstalling or resetting AirBridge (a hash of Windows' machine id, never the
        /// id itself). <see cref="Id"/> changes whenever the app's data is cleared, so a device that remembers only the id keeps a
        /// stale entry per reset; with this it can tell "same PC, new install" from "a different PC with the same name".
        /// </summary>
        public static string? Hw { get; } = ComputeHw();

        private static string? ComputeHw()
        {
            try
            {
                using var k = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Cryptography");
                var guid = k?.GetValue("MachineGuid") as string;
                if (string.IsNullOrWhiteSpace(guid)) return null;
                var hash = System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes("airbridge-hw|" + guid));
                return Convert.ToHexString(hash, 0, 16).ToLowerInvariant();
            }
            catch { return null; }
        }
        public string Name { get; private set; }
        private bool _customName;

        private Identity(string id, string name, bool custom)
        {
            Id = id; Name = name; _customName = custom;
        }

        public static Identity LoadOrCreate()
        {
            Stored? s = null;
            try
            {
                if (File.Exists(FilePath)) s = JsonSerializer.Deserialize<Stored>(File.ReadAllText(FilePath));
            }
            catch (Exception ex) { Log.Error("identity.json unreadable, regenerating", ex); }

            bool dirty = false;
            string id = s?.Id ?? "";
            if (!Guid.TryParse(id, out _))
            {
                id = Guid.NewGuid().ToString("D").ToLowerInvariant();
                dirty = true;
            }
            bool custom = s?.CustomName ?? false;
            string name = custom && !string.IsNullOrWhiteSpace(s?.Name) ? s!.Name! : Environment.MachineName;
            if (name != s?.Name) dirty = true;

            var identity = new Identity(id, name, custom);
            if (dirty) identity.Save();
            return identity;
        }

        public void Rename(string name)
        {
            Name = string.IsNullOrWhiteSpace(name) ? Environment.MachineName : name.Trim();
            _customName = !string.IsNullOrWhiteSpace(name);
            Save();
        }

        private void Save()
        {
            try
            {
                Directory.CreateDirectory(Log.Dir);
                File.WriteAllText(FilePath, JsonSerializer.Serialize(new Stored { Id = Id, Name = Name, CustomName = _customName }));
            }
            catch (Exception ex) { Log.Error("Failed to save identity", ex); }
        }
    }
}
