using System.Diagnostics;
using System.IO;
using System.IO.Compression;

namespace AirBridge.Input
{
    /// <summary>
    /// Sends the items of a carried drag to the device they were dropped on (protocol.md §8.8) as one normal transfer
    /// (§6). Files go as they are; folders are zipped first (<c>Photos</c> → <c>Photos.zip</c>) into a temporary folder
    /// that is deleted when the transfer ends. Never blocks the caller.
    /// </summary>
    internal static class DragSend
    {
        public static void Start(string peerId, IReadOnlyList<string> items)
        {
            if (items.Count == 0) return;
            var list = items.ToArray();
            _ = Task.Run(async () =>
            {
                string? temp = null;
                try
                {
                    var paths = new List<string>();
                    foreach (var item in list)
                    {
                        if (File.Exists(item)) { paths.Add(item); continue; }
                        if (!Directory.Exists(item)) continue;
                        temp ??= Directory.CreateDirectory(Path.Combine(Path.GetTempPath(), "AirBridge-drag-" + Guid.NewGuid().ToString("N"))).FullName;
                        if (Zip(item, temp) is { } zip) paths.Add(zip);
                    }
                    if (paths.Count == 0) return;
                    var svc = App.Peers;
                    if (svc == null) return;
                    var result = await svc.SendFilesAsync(peerId, paths, null, CancellationToken.None).ConfigureAwait(false);
                    if (!result.Ok) Debug.WriteLine($"drag send to {peerId} failed: {result.Error}");
                }
                catch (Exception ex) { Debug.WriteLine($"drag send: {ex}"); }
                finally
                {
                    if (temp != null) { try { Directory.Delete(temp, recursive: true); } catch { } }
                }
            });
        }

        private static string? Zip(string folder, string temp)
        {
            try
            {
                string name = Path.GetFileName(folder.TrimEnd('\\', '/'));
                if (name.Length == 0) name = "Folder";
                string dst = Path.Combine(temp, name + ".zip");
                for (int n = 2; File.Exists(dst); n++) dst = Path.Combine(temp, $"{name} ({n}).zip");
                ZipFile.CreateFromDirectory(folder, dst, CompressionLevel.Fastest, includeBaseDirectory: true);
                return dst;
            }
            catch (Exception ex) { Debug.WriteLine($"drag zip {folder}: {ex.Message}"); return null; }
        }
    }
}
