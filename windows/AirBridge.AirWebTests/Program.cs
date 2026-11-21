using System.IO.Compression;
using System.Net;
using System.Net.Sockets;
using System.Text;
using AirBridge.AirWeb;

// Self-check and live test host for AirWeb on Windows.
string page = File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "../../../../AirBridge/AirWeb/airweb.html"));
string save = Path.Combine(Path.GetTempPath(), "airweb-win-test-" + Guid.NewGuid().ToString("N")[..8]);
var session = new AirWebSession(() => new[] { "127.0.0.1", "192.168.50.7" }, () => "Test PC", () => page, save);

if (args.Length > 0 && args[0] == "serve")
{
    string share = Path.Combine(save, "shared");
    Directory.CreateDirectory(share);
    int count = args.Length > 1 ? int.Parse(args[1]) : 6;
    var paths = new List<string>();
    for (int i = 1; i <= count; i++)
    {
        string p = Path.Combine(share, $"sample-{i}.txt");
        File.WriteAllText(p, $"sample file {i}\n" + new string('x', 1000 * i));
        paths.Add(p);
    }
    session.Start();
    session.Add(paths);
    Console.WriteLine($"LINK {AirWebServer.Url("127.0.0.1", session.Port, session.Token)}");
    Console.Out.Flush();
    await Task.Delay(Timeout.Infinite);
    return 0;
}

int failed = 0;
void Check(bool ok, string what) { Console.WriteLine((ok ? "  ok   " : "  FAIL ") + what); if (!ok) failed++; }

// ---- the short link, with a fake clock
{
    long now = 1_000_000;
    var l = new AirWebShortLink(clock: () => now);
    var a = l.Issue();
    Check(a.Code.Length == 6 && a.Code.All(c => AirWebShortLink.Alphabet.Contains(c)) && !a.Code.Any(c => "01OIL".Contains(c)), "short code is six clear characters");
    Check(a.SecondsLeft == 600, "ten minutes");
    now += 125_000;
    Check(l.Issue().Code == a.Code && l.Issue().SecondsLeft == 475, "same code and the time left");
    Check(l.Check(a.Code) == AirWebShortLink.Outcome.Match && l.Check(a.Code.ToLowerInvariant()) == AirWebShortLink.Outcome.Match, "right code matches in any case");
    Check(l.Check(a.Code + "2") == AirWebShortLink.Outcome.Miss && l.Check(null) == AirWebShortLink.Outcome.Miss, "wrong code misses");
    now += 500_000;
    Check(l.Check(a.Code) == AirWebShortLink.Outcome.Miss, "expires after ten minutes");
    var l2 = new AirWebShortLink(clock: () => now);
    l2.Issue();
    for (int i = 0; i < 8; i++) l2.Check("ZZZZZZ");
    Check(l2.Check(l2.Issue().Code) == AirWebShortLink.Outcome.Locked, "eight misses lock out even the right code");
    now += 61_000;
    Check(l2.Check(l2.Issue().Code) == AirWebShortLink.Outcome.Match, "the lock ends with the minute");
}

// ---- the server over real sockets
session.Start();
int port = session.Port;
string tok = session.Token;
var http = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(30) };
string B(string path) => $"http://127.0.0.1:{port}{path}";
async Task<(int Code, string Body, HttpResponseMessage R)> Get(string path, HttpMethod? m = null)
{
    var r = await http.SendAsync(new HttpRequestMessage(m ?? HttpMethod.Get, B(path)));
    return ((int)r.StatusCode, await r.Content.ReadAsStringAsync(), r);
}

Check(port != 0, "server started on a port");
Check((await Get("/")).Code == 403 && (await Get("/api/state?t=nope")).Code == 403, "no token, no entry");
var home = await Get($"/?t={tok}");
Check(home.Code == 200 && home.Body.Contains("Shared by you") && home.Body.Contains("Test PC") && !home.Body.Contains("{{NAME}}"), "page served with the device name");
Check(home.R.Headers.Contains("X-Frame-Options") && home.R.Headers.Contains("Cache-Control"), "page headers present");

string c1 = "clientaaaa1", c2 = "clientbbbb2";
var st = await Get($"/api/state?t={tok}&c={c1}&w=Chrome%20on%20Windows");
Check(st.Code == 200 && st.Body.Contains("\"count\":1") && st.Body.Contains("Chrome on Windows") && st.Body.Contains("\"alts\":[\"http://127.0.0.1:") && st.Body.Contains("192.168.50.7"), "state lists the browser and other addresses");

// an upload from browser 1
byte[] data = new byte[300_000]; new Random(1).NextBytes(data);
async Task<(int, string)> Upload(string name, byte[] body, string client)
{
    var req = new HttpRequestMessage(HttpMethod.Post, B($"/u?t={tok}&n={Uri.EscapeDataString(name)}&c={client}&w=Firefox")) { Content = new ByteArrayContent(body) };
    var r = await http.SendAsync(req);
    return ((int)r.StatusCode, await r.Content.ReadAsStringAsync());
}
var up = await Upload("photo.jpg", data, c1);
Check(up.Item1 == 200 && up.Item2.Contains("photo.jpg"), "upload accepted");
var up2 = await Upload("photo.jpg", data, c2);
Check(up2.Item1 == 200 && up2.Item2.Contains("photo (2).jpg"), "same name gets a number, nothing overwritten");
var bad = await Upload("..\\..\\evil:name?.txt", new byte[10], c1);
Check(bad.Item1 == 200 && Directory.GetFiles(save).All(f => Path.GetFullPath(f).StartsWith(Path.GetFullPath(save))), "hostile name stays inside the save folder");
Check(File.ReadAllBytes(Path.Combine(save, "photo.jpg")).SequenceEqual(data), "saved bytes are exact");

var pool = await Get($"/api/state?t={tok}&c={c1}");
Check(pool.Body.Contains("\"own\":true") && pool.Body.Contains("photo.jpg"), "the uploader sees its file as its own");
var pool2 = await Get($"/api/state?t={tok}&c={c2}");
Check(pool2.Body.Contains("\"id\":1000001"), "ids of uploads start at 1000001");
string v = System.Text.RegularExpressions.Regex.Match(pool.Body, "\"v\":\"([0-9a-f]+)\"").Groups[1].Value;
Check((await Get($"/api/state?t={tok}&c={c1}&v={v}")).Body.Contains("\"same\":true"), "unchanged poll is the short answer");

// a file from this PC, downloaded by browser 2
string mine = Path.Combine(save, "mine.bin"); File.WriteAllBytes(mine, data[..5000]);
session.Add(new[] { mine });
var all = await Get($"/api/state?t={tok}&c={c2}");
int mineId = int.Parse(System.Text.RegularExpressions.Regex.Match(all.Body, "\"id\":(\\d+),\"name\":\"mine.bin\"").Groups[1].Value);
var dl = await http.GetAsync(B($"/d/{mineId}/mine.bin?t={tok}&c={c2}"));
var got = await dl.Content.ReadAsByteArrayAsync();
Check(dl.StatusCode == HttpStatusCode.OK && got.SequenceEqual(data[..5000]) && dl.Content.Headers.ContentLength == 5000, "download is exact with a length");
Check(dl.Content.Headers.ContentDisposition?.FileName?.Contains("mine.bin") == true, "download has a filename");
Check((await Get($"/api/state?t={tok}&c={c2}")).Body.Contains($"\"got\":[{mineId}]"), "the page's download list remembers it");
Check((await Get($"/d/{mineId}?t={tok}", HttpMethod.Head)).Code == 200, "HEAD works");
Check((await Get($"/d/99999?t={tok}")).Code == 404, "unknown file is 404");

// zip of everything
var zr = await http.GetAsync(B($"/z?t={tok}&c={c2}"));
using (var zs = new ZipArchive(new MemoryStream(await zr.Content.ReadAsByteArrayAsync())))
{
    Check(zr.StatusCode == HttpStatusCode.OK && zs.Entries.Count == 4, $"zip has every file ({zs.Entries.Count})");
    var e = zs.Entries.First(x => x.Name == "mine.bin");
    using var ms = new MemoryStream(); e.Open().CopyTo(ms);
    Check(ms.ToArray().SequenceEqual(data[..5000]), "zip entry bytes are exact");
    Check(zs.Entries.Select(x => x.Name).Distinct(StringComparer.OrdinalIgnoreCase).Count() == zs.Entries.Count, "zip names are unique");
}
var zr2 = await http.GetAsync(B($"/z?t={tok}&ids=1,1000001"));
using (var zs2 = new ZipArchive(new MemoryStream(await zr2.Content.ReadAsByteArrayAsync()))) Check(zs2.Entries.Count == 2, "zip of chosen ids");

// qr
var qr = await Get($"/qr.svg?t={tok}");
Check(qr.Code == 200 && qr.Body.StartsWith("<svg") && qr.R.Content.Headers.ContentType?.MediaType == "image/svg+xml", "qr.svg is drawn");

// short link
Check((await Get($"/api/short?t={tok}")).Code == 405, "short link needs POST");
var sr = await http.PostAsync(B($"/api/short?t={tok}"), null);
string sb = await sr.Content.ReadAsStringAsync();
string code = System.Text.RegularExpressions.Regex.Match(sb, "\"code\":\"([A-Z0-9]+)\"").Groups[1].Value;
Check(sr.StatusCode == HttpStatusCode.OK && code.Length == 6 && sb.Contains("\"ttl\":"), "short code made on request");
var jump = await Get($"/j/{code}");
Check(jump.Code == 302 && jump.R.Headers.Location?.OriginalString == $"/?t={tok}", "short code redirects to the real link without a token");
var miss = await Get("/j/AAAAAA");
Check(miss.Code == 404 && !miss.Body.Contains(tok), "wrong code: 404 without the token");
for (int i = 0; i < 9; i++) await Get("/j/BBBBBB");
Check((await Get($"/j/{code}")).Code == 429 && (await Get($"/?t={tok}")).Code == 200, "lock-out refuses even the right code; the real link still works");

// a new link
string oldTok = tok;
string newTok = session.Token;
session.NewLink();
newTok = session.Token;
Check(newTok != oldTok, "new link has a new token");
var moved = await Get($"/api/state?t={oldTok}&c={c1}");
Check(moved.Code == 200 && moved.Body.Contains($"\"moved\":\"/?t={newTok}\""), "old poll is told where the link went");
var reload = await Get($"/?t={oldTok}");
Check(reload.Code == 302 && reload.R.Headers.Location?.OriginalString == $"/?t={newTok}", "reloading the old page lands on the new one");
Check((await Get($"/z?t={oldTok}")).Code == 403, "an old link gets nothing else");
tok = newTok;

// stop sharing
var uploaded = session.ReceivedFiles.First(r => r.Name == "photo.jpg");
session.StopSharing(uploaded.Id);
Check(!(await Get($"/api/state?t={tok}&c={c1}")).Body.Contains("photo.jpg"), "a stopped file leaves the pool");

// many browsers at once
var tasks = Enumerable.Range(0, 60).Select(i => Get($"/api/state?t={tok}&c=crowd{i:D6}&w=B{i}")).ToArray();
var res = await Task.WhenAll(tasks);
Check(res.All(r => r.Code is 200 or 503) && res.Count(r => r.Code == 200) >= 20, "60 browsers at once: served, or told to retry past the per-address cap");
Check((await Get($"/api/state?t={tok}&c=crowd000001")).Body.Contains("\"count\":"), "crowd is counted");

// garbage and slow clients never hurt it
using (var raw = new TcpClient()) { await raw.ConnectAsync("127.0.0.1", port); await raw.GetStream().WriteAsync(Encoding.ASCII.GetBytes("NOT HTTP AT ALL\r\n\r\n")); var buf = new byte[200]; int n = await raw.GetStream().ReadAsync(buf); Check(Encoding.ASCII.GetString(buf, 0, n).StartsWith("HTTP/1.1 400"), "garbage gets 400"); }
Check((await Get($"/api/state?t={tok}")).Code == 200, "still serving after garbage");

session.Stop();
try { Directory.Delete(save, true); } catch { }
Console.WriteLine(failed == 0 ? "ALL OK" : $"{failed} FAILED");
return failed == 0 ? 0 : 1;
