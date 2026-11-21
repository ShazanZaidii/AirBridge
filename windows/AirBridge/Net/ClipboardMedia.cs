using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace AirBridge.Net
{
    /// <summary>
    /// Which clipboard images and files are worth sending without being asked, and what counts as a picture (docs/protocol.md 5.1).
    /// Pure (System.IO only), so it is checked by AirBridge.ClipTests on any OS.
    /// </summary>
    public static class ClipboardMedia
    {
        public const long ImageMaxBytes = 25L * 1024 * 1024;
        public const long FilesMaxBytes = 100L * 1024 * 1024;
        public const int FilesMaxCount = 20;

        private static readonly HashSet<string> ImageExtensions = new(StringComparer.OrdinalIgnoreCase)
            { ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".tif", ".tiff", ".heic", ".heif" };

        /// <summary>Whether items of these sizes may go over the clipboard. An image has a lower limit than files.</summary>
        public static bool Allowed(IReadOnlyList<long> sizes, bool image)
        {
            if (sizes.Count == 0 || sizes.Any(s => s < 0)) return false;
            long total = sizes.Sum();
            return image ? total <= ImageMaxBytes : sizes.Count <= FilesMaxCount && total <= FilesMaxBytes;
        }

        /// <summary>A picture file: by extension, or by its first bytes when the name has none (a phone may send "image").</summary>
        public static bool IsImageFile(string path)
        {
            if (ImageExtensions.Contains(Path.GetExtension(path))) return true;
            try
            {
                using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                var h = new byte[12];
                int n = fs.Read(h, 0, h.Length);
                return LooksLikeImage(h.AsSpan(0, n));
            }
            catch { return false; }
        }

        public static bool LooksLikeImage(ReadOnlySpan<byte> h)
        {
            if (h.Length >= 8 && h[0] == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47) return true;           // PNG
            if (h.Length >= 3 && h[0] == 0xFF && h[1] == 0xD8 && h[2] == 0xFF) return true;                            // JPEG
            if (h.Length >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') return true;               // GIF
            if (h.Length >= 2 && h[0] == 'B' && h[1] == 'M') return true;                                              // BMP
            if (h.Length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E') return true; // WebP
            return false;
        }
    }

    /// <summary>Where clipboard transfers live: a private cache folder, never Downloads.</summary>
    public static class ClipboardInbox
    {
        public static string Root => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AirBridge", "Clipboard");
        /// <summary>Clipboard data that came in from another device.</summary>
        public static string In => Path.Combine(Root, "in");
        /// <summary>Clipboard data waiting to be sent (an image is written here first).</summary>
        public static string Out => Path.Combine(Root, "out");

        /// <summary>Writes <paramref name="data"/> to a fresh file called <paramref name="name"/> in a new folder under <paramref name="outDir"/>.</summary>
        public static string? WriteOutFile(string name, byte[] data, string? outDir = null)
        {
            try
            {
                string dir = Path.Combine(outDir ?? Out, Guid.NewGuid().ToString("N"));
                Directory.CreateDirectory(dir);
                string path = Path.Combine(dir, name);
                File.WriteAllBytes(path, data);
                return path;
            }
            catch { return null; }
        }

        /// <summary>Removes a file written by <see cref="WriteOutFile"/> together with the folder made for it.</summary>
        public static void Discard(string path)
        {
            try { Directory.Delete(Path.GetDirectoryName(path)!, recursive: true); } catch { }
        }

        /// <summary>Deletes clipboard data older than <paramref name="age"/> (a day by default). <paramref name="root"/> is for testing.</summary>
        public static void PurgeOld(TimeSpan? age = null, string? root = null)
        {
            var cutoff = DateTime.UtcNow - (age ?? TimeSpan.FromDays(1));
            foreach (var sub in new[] { Path.Combine(root ?? Root, "in"), Path.Combine(root ?? Root, "out") })
            {
                try
                {
                    if (!Directory.Exists(sub)) continue;
                    foreach (var e in new DirectoryInfo(sub).EnumerateFileSystemInfos())
                        if (e.LastWriteTimeUtc < cutoff) { try { if (e is DirectoryInfo d) d.Delete(true); else e.Delete(); } catch { } }
                }
                catch { }
            }
        }
    }
}
