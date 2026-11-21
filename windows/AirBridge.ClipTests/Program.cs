using System;
using System.Collections.Generic;
using System.IO;
using AirBridge.Net;

int passed = 0, failed = 0;
void Check(string name, bool ok) { if (ok) passed++; else { failed++; Console.WriteLine("FAIL: " + name); } }

const long MB = 1024 * 1024;
Check("an image up to 25 MB is allowed", ClipboardMedia.Allowed(new List<long> { 25 * MB }, image: true));
Check("an image over 25 MB is not", !ClipboardMedia.Allowed(new List<long> { 25 * MB + 1 }, image: true));
Check("files up to 100 MB are allowed", ClipboardMedia.Allowed(new List<long> { 60 * MB, 40 * MB }, image: false));
Check("files over 100 MB are not", !ClipboardMedia.Allowed(new List<long> { 60 * MB, 40 * MB + 1 }, image: false));
Check("20 files are allowed", ClipboardMedia.Allowed(new List<long>(new long[20]), image: false));
Check("21 files are not", !ClipboardMedia.Allowed(new List<long>(new long[21]), image: false));
Check("an image has the lower limit even where files may be bigger", !ClipboardMedia.Allowed(new List<long> { 30 * MB }, true) && ClipboardMedia.Allowed(new List<long> { 30 * MB }, false));
Check("nothing is not sent", !ClipboardMedia.Allowed(new List<long>(), false));
Check("an unknown size is not sent", !ClipboardMedia.Allowed(new List<long> { -1 }, true));

string dir = Path.Combine(Path.GetTempPath(), "clipcheck-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(dir);
try
{
    byte[] png = { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0 };
    byte[] jpg = { 0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0 };
    string P(string n, byte[] b) { string p = Path.Combine(dir, n); File.WriteAllBytes(p, b); return p; }
    Check("a .png is an image", ClipboardMedia.IsImageFile(P("a.png", png)));
    Check("the extension case does not matter", ClipboardMedia.IsImageFile(P("b.JPG", jpg)));
    Check("a name with no extension is recognised by its bytes (png)", ClipboardMedia.IsImageFile(P("image", png)));
    Check("a name with no extension is recognised by its bytes (jpeg)", ClipboardMedia.IsImageFile(P("photo", jpg)));
    Check("a text file is not an image", !ClipboardMedia.IsImageFile(P("notes.txt", new byte[] { 104, 105, 0, 0, 0, 0, 0, 0 })));
    Check("a missing file is not an image", !ClipboardMedia.IsImageFile(Path.Combine(dir, "nope")));
    Check("an empty file is not an image", !ClipboardMedia.IsImageFile(P("empty", Array.Empty<byte>())));
    Check("webp is recognised by its bytes", ClipboardMedia.LooksLikeImage(new byte[] { (byte)'R', (byte)'I', (byte)'F', (byte)'F', 0, 0, 0, 0, (byte)'W', (byte)'E', (byte)'B', (byte)'P' }));
    Check("gif is recognised by its bytes", ClipboardMedia.LooksLikeImage(new byte[] { (byte)'G', (byte)'I', (byte)'F', (byte)'8', (byte)'9', (byte)'a' }));

    string outRoot = Path.Combine(dir, "out");
    string? w = ClipboardInbox.WriteOutFile("clipboard.png", png, outRoot);
    Check("an outbox file is written with its bytes", w != null && File.ReadAllBytes(w).Length == png.Length && Path.GetFileName(w) == "clipboard.png");
    string? w2 = ClipboardInbox.WriteOutFile("clipboard.png", png, outRoot);
    Check("two outbox files never share a folder", w != null && w2 != null && Path.GetDirectoryName(w) != Path.GetDirectoryName(w2));
    ClipboardInbox.Discard(w!); ClipboardInbox.Discard(w2!);
    Check("discard removes the file and its folder", !Directory.Exists(Path.GetDirectoryName(w)!));

    string root = Path.Combine(dir, "cache");
    Directory.CreateDirectory(Path.Combine(root, "in", "old")); Directory.CreateDirectory(Path.Combine(root, "in", "new"));
    File.WriteAllBytes(Path.Combine(root, "in", "old", "a.png"), png); File.WriteAllBytes(Path.Combine(root, "in", "new", "b.png"), png);
    Directory.SetLastWriteTimeUtc(Path.Combine(root, "in", "old"), DateTime.UtcNow.AddDays(-2));
    ClipboardInbox.PurgeOld(root: root);
    Check("purge removes data older than a day", !Directory.Exists(Path.Combine(root, "in", "old")));
    Check("purge keeps recent data", File.Exists(Path.Combine(root, "in", "new", "b.png")));
    Check("purging with no cache folder is harmless", Run(() => ClipboardInbox.PurgeOld(root: Path.Combine(dir, "nothing"))));
    Check("the inbox is under the local app data folder, not Downloads", ClipboardInbox.In.Contains("AirBridge") && !ClipboardInbox.In.Contains("Downloads"));
}
finally { try { Directory.Delete(dir, true); } catch { } }

Console.WriteLine($"{passed} passed, {failed} failed");
return failed == 0 ? 0 : 1;

static bool Run(Action a) { try { a(); return true; } catch { return false; } }
