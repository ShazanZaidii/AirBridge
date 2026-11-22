using System;
using System.Linq;
using System.Text.Json.Nodes;
using AirBridge.Net;

int passed = 0, failed = 0;
void Check(string name, bool ok) { if (ok) passed++; else { failed++; Console.WriteLine("FAIL: " + name); } }
DateTime T(int d, int h, int m = 0, int s = 0) => new DateTime(2026, 11, d, h, m, s, DateTimeKind.Local);

var a = new ScreenTimeAccumulator();
a.Add("chrome.exe", "Google Chrome", T(22, 9, 50), T(22, 10, 20));
Check("30 minutes are counted", Math.Round(a.Days["2026-11-22"].Seconds) == 1800);
Check("split at the hour", Math.Round(a.Days["2026-11-22"].Hours[9]) == 600 && Math.Round(a.Days["2026-11-22"].Hours[10]) == 1200);
a.Add("chrome.exe", "Google Chrome", T(22, 23, 50), T(23, 0, 15));
Check("over midnight goes to both days", Math.Round(a.Days["2026-11-22"].Hours[23]) == 600 && Math.Round(a.Days["2026-11-23"].Hours[0]) == 900);
a.Add("code.exe", "Visual Studio Code", T(22, 14), T(22, 15));
Check("apps are kept apart", Math.Round(a.Days["2026-11-22"].Apps["code.exe"].Seconds) == 3600 && Math.Round(a.Days["2026-11-22"].Apps["chrome.exe"].Seconds) == 2400);
var gap = new ScreenTimeAccumulator(); gap.Add("x.exe", "X", T(22, 0), T(22, 12));
Check("one stretch counts at most 4 hours", Math.Round(gap.Days["2026-11-22"].Seconds) == 4 * 3600);
var none = new ScreenTimeAccumulator(); none.Add("x.exe", "X", T(22, 10), T(22, 9)); none.Add("", "", T(22, 9), T(22, 10));
Check("backwards time and an empty id count nothing", none.Days.Count == 0);
var old = new ScreenTimeAccumulator(); for (int d = 1; d <= 20; d++) old.Add("x.exe", "X", T(d, 10), T(d, 11));
old.Prune(7);
Check("prune keeps the newest 7 days", old.Days.Count == 7 && old.Days.Keys.Max() == "2026-11-20");

var msg = a.Message(T(22, 20));
Check("message type", (string)msg["t"]! == "screen_time");
var days = (JsonArray)msg["days"]!;
var today = days.Select(x => (JsonObject)x!).First(o => (string)o["day"]! == "2026-11-22");
Check("today has 24 hours", ((JsonArray)today["hours"]!).Count == 24 && (long)today["hours"]![9]! == 600);
var apps = (JsonArray)today["apps"]!;
Check("today's apps, most used first, with names", (string)apps[0]!["id"]! == "code.exe" && (string)apps[0]!["name"]! == "Visual Studio Code" && (long)apps[0]!["s"]! == 3600);
var tomorrow = days.Select(x => (JsonObject)x!).First(o => (string)o["day"]! == "2026-11-23");
Check("other days carry only a total", tomorrow["hours"] == null && tomorrow["apps"] == null && (long)tomorrow["s"]! == 900);
var lots = new ScreenTimeAccumulator(); for (int i = 0; i < 30; i++) lots.Add($"app{i}.exe", $"A{i}", T(22, 12, i), T(22, 12, i, 30 + i));
var lm = (JsonObject)((JsonArray)lots.Message(T(22, 13))["days"]!)[0]!;
Check("at most 20 apps", ((JsonArray)lm["apps"]!).Count == 20);
Check("top app ids for icons", string.Join(",", lots.TopAppIds(T(22, 13), 2)) == "app29.exe,app28.exe");
var many = new ScreenTimeAccumulator(); for (int d = 1; d <= 20; d++) many.Add("x.exe", "X", T(d, 10), T(d, 11));
Check("at most 14 days are sent", ((JsonArray)many.Message(T(20, 12))["days"]!).Count == 14);

var back = ScreenTimeAccumulator.FromJson(a.ToJson());
Check("saving and loading keeps every day and app", back.Days.Count == a.Days.Count && Math.Round(back.Days["2026-11-22"].Apps["code.exe"].Seconds) == 3600 && back.Days["2026-11-22"].Hours.Length == 24);
Check("a broken file loads as empty", ScreenTimeAccumulator.FromJson("{not json").Days.Count == 0 && ScreenTimeAccumulator.FromJson(null).Days.Count == 0);

Check("app id is the lower-case file name", ScreenTimeWire.AppId(@"C:\Program Files\Google\Chrome\Application\Chrome.EXE") == "chrome.exe");
Check("the lock screen is not an app", ScreenTimeWire.Ignored.Contains("LockApp.exe"));
var png = new byte[] { 0x89, 0x50, 0x4E, 0x47, 1, 2 };
Check("icon message", (string)ScreenTimeWire.IconMessage("chrome.exe", png)!["png"]! == Convert.ToBase64String(png));
Check("an oversized or empty icon is not sent", ScreenTimeWire.IconMessage("x", new byte[100 * 1024]) == null && ScreenTimeWire.IconMessage("x", Array.Empty<byte>()) == null);

Console.WriteLine($"{passed} passed, {failed} failed");
return failed == 0 ? 0 : 1;
