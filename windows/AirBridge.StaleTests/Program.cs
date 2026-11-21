using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json.Nodes;
using AirBridge.Net;

internal static class Program
{
    private static int _passed, _failed;

    private static void Check(string name, bool ok)
    {
        if (ok) _passed++;
        else { _failed++; Console.WriteLine("FAIL: " + name); }
    }

    private static StaleDevices.Entry E(string id, string name = "V2404", string plat = "android", string? hw = null) => new(id, name, plat, hw);
    private static HashSet<string> Ids(IEnumerable<StaleDevices.Entry> e) => e.Select(x => x.Id).ToHashSet();
    private static HashSet<string> None() => new();

    public static int Main()
    {
        // superseded: the same physical device under an older install
        var paired = new List<StaleDevices.Entry> { E("old1", hw: "H"), E("old2", hw: "H"), E("new", hw: "H"), E("other", hw: "OTHER"), E("legacy") };
        Check("same fingerprint, other ids are superseded", Ids(StaleDevices.Superseded("new", "H", paired, None())).SetEquals(new[] { "old1", "old2" }));
        Check("a different fingerprint is kept even with the same name", !Ids(StaleDevices.Superseded("new", "H", paired, None())).Contains("other"));
        Check("no fingerprint is never guessed at", !Ids(StaleDevices.Superseded("new", "H", paired, None())).Contains("legacy"));
        Check("nothing without a fingerprint from the device", StaleDevices.Superseded("new", null, paired, None()).Count == 0);
        Check("an entry connected right now is left alone", Ids(StaleDevices.Superseded("new", "H", paired, new HashSet<string> { "old1" })).SetEquals(new[] { "old2" }));
        var twins = new List<StaleDevices.Entry> { E("a", "My phone", hw: "H1"), E("b", "My phone", hw: "H2") };
        Check("two devices with one name are both kept", StaleDevices.Superseded("b", "H2", twins, None()).Count == 0);

        // candidates: older entries without a fingerprint, only to ask about
        var me = E("new", hw: "H");
        var legacy = new List<StaleDevices.Entry> { E("l1"), E("l2"), E("lava", "LAVA LXX516"), E("mac", "V2404", "macos"), E("withhw", hw: "X"), E("live") };
        var live = new HashSet<string> { "live" };
        Check("same kind and name, no fingerprint: offered", Ids(StaleDevices.Candidates(me, legacy, live, None())).SetEquals(new[] { "l1", "l2" }));
        Check("a different name is not offered", !Ids(StaleDevices.Candidates(me, legacy, None(), None())).Contains("lava"));
        Check("a different kind of device is not offered", !Ids(StaleDevices.Candidates(me, legacy, None(), None())).Contains("mac"));
        Check("an entry with a fingerprint is not offered", !Ids(StaleDevices.Candidates(me, legacy, None(), None())).Contains("withhw"));
        Check("a connected entry is not offered", !Ids(StaleDevices.Candidates(me, legacy, live, None())).Contains("live"));
        Check("kept entries are not offered again", Ids(StaleDevices.Candidates(me, legacy, live, new HashSet<string> { "l1" })).SetEquals(new[] { "l2" }));

        // the battery wire (§14)
        var msg = DeviceBatteryWire.Message(new BatteryLevel(87, true));
        Check("message type", (string?)msg["t"] == "dev_battery");
        Check("message round-trips", DeviceBatteryWire.Parse(msg) == new BatteryLevel(87, true));
        Check("not charging by default", DeviceBatteryWire.Parse(new JsonObject { ["pct"] = 40 }) == new BatteryLevel(40, false));
        Check("a level above 100 is refused", DeviceBatteryWire.Parse(new JsonObject { ["pct"] = 400 }) is null);
        Check("a negative level is refused", DeviceBatteryWire.Parse(new JsonObject { ["pct"] = -1 }) is null);
        Check("a message without a level is refused", DeviceBatteryWire.Parse(new JsonObject { ["charging"] = true }) is null);

        // Windows' power status → a level (null on a desktop)
        Check("a desktop (flag 128) sends nothing", PcBatteryStatus.From(128, 100) is null);
        Check("an unknown percentage sends nothing", PcBatteryStatus.From(0, 255) is null);
        Check("discharging at 63%", PcBatteryStatus.From(0, 63) == new BatteryLevel(63, false));
        Check("charging (flag 8) at 42%", PcBatteryStatus.From(8, 42) == new BatteryLevel(42, true));
        Check("high + charging flags combine", PcBatteryStatus.From(1 | 8, 99) == new BatteryLevel(99, true));

        Console.WriteLine($"{_passed} passed, {_failed} failed");
        return _failed == 0 ? 0 : 1;
    }
}
