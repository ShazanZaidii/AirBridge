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
