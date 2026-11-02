using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using AirBridge.Net.Link;

// Vectors computed independently with Python `cryptography` (HKDF) + hmac + base64 (see docs in LinkCreds.cs).
internal static class Program
{
    private const string H = "11111111-2222-4333-8444-555555555555";
    private const string C = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private const string K0 = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    private static readonly (string K, string Host, string Client, int Gen, string Wk, string Ssid, string Psk)[] Vectors =
    {
        (K0, H, C, 0, "fa16771b7a36d0db9acb7fc9d377b98bc27ff406d79b6e8f3d09b6a34a06c215", "DIRECT-AB-2EY7AJTE", "S2yJ-E17aGlO-LTvtguN79B1S2ExYqEK"),
        (K0, H, C, 1, "5a25d1fdb4c3dabf1aa940fa2ab4e18f062772031d2a8ac2c1771648e62b9a13", "DIRECT-AB-5T2DWFRV", "L63RC0pb6GYB0pXd9HcQKC4qRtYNHAWH"),
        (K0, C, H, 0, "7307d20e5ab5d43470a0293f7c233e65a444bf3fb753dde8f6e7286333cc49ec", "DIRECT-AB-PWZVZYQP", "lND0mX7j4mdeFxuB2rTI3QbIbWD9O0_l"),
        ("db1386667799618d974aa48b62c8b1b1197bdcbcc97300236d594ad05519d72e", H, C, 0,
         "2c1bdf644fb482347abd606e0d9858bda5f47337bdbafd5be121858920a71aa3", "DIRECT-AB-CYP3WH6T", "pY5CtqnG9e-dpOvS-E_kef3jKjWzlrhw"),
        ("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", H, C, 255,
         "922555d8e686885d3c0ac346bd8d3435284ee9d5a9810db2fdc7d8a097c73a49", "DIRECT-AB-TI37QMJJ", "HMc5UhjoHdbpoYYusVoJNfAVEr_cGIfR"),
    };

    private static int _failed, _passed;

    private static void Check(bool ok, string what)
    {
        if (ok) _passed++;
        else { _failed++; Console.WriteLine("FAIL: " + what); }
    }

    private static int Main(string[] args)
    {
        // RFC 4648 §10 base32 vectors (padding stripped).
        foreach (var (inp, outp) in new[] { ("", ""), ("f", "MY"), ("fo", "MZXQ"), ("foo", "MZXW6"), ("foob", "MZXW6YQ"), ("fooba", "MZXW6YTB"), ("foobar", "MZXW6YTBOI") })
            Check(LinkCreds.Base32(System.Text.Encoding.ASCII.GetBytes(inp)) == outp, $"base32({inp})");
        Check(LinkCreds.Base64Url(new byte[] { 0xfb, 0xff }) == "-_8", "base64url");

        foreach (var v in Vectors)
        {
            byte[] k = Convert.FromHexString(v.K);
            string wk = Convert.ToHexString(LinkCreds.WorkKey(k, v.Host, v.Client, v.Gen)).ToLowerInvariant();
            var c = LinkCreds.Wfd(k, v.Host, v.Client, v.Gen);
            Check(wk == v.Wk, $"wk gen={v.Gen} host={v.Host[..4]}: {wk}");
            Check(c.Ssid == v.Ssid, $"ssid gen={v.Gen}: {c.Ssid} != {v.Ssid}");
            Check(c.Psk == v.Psk, $"psk gen={v.Gen}: {c.Psk} != {v.Psk}");
            Check(c.Ssid.Length == 18 && c.Psk.Length == 32, "lengths");
        }

        // link_offer shape (§10.1).
        var offer = LinkWire.Offer(7, new WfdCreds("DIRECT-AB-7KQ2M9XA", "x", 0), new[] { "192.168.137.1", "fe80::1" }, 47632);
        string json = offer.ToJsonString();
        Check(json == "{\"t\":\"link_offer\",\"lid\":7,\"type\":\"wfd_legacy\",\"cred\":\"derived\",\"gen\":0,\"ssid\":\"DIRECT-AB-7KQ2M9XA\",\"sec\":\"wpa2\",\"band\":\"unknown\",\"addr\":[\"192.168.137.1\",\"fe80::1\"],\"port\":47632,\"ttl\":120}", "offer json: " + json);
        Check(!json.Contains("\"x\""), "psk never sent");
        var req = JsonNode.Parse("{\"t\":\"link_request\",\"lid\":7.0,\"why\":\"xfer\",\"bytes\":734003200,\"types\":[\"wfd_legacy\",\"wfd_go\"]}")!.AsObject();
        Check(LinkWire.Lid(req) == 7 && LinkWire.Types(req).Contains("wfd_legacy") && LinkWire.Long(req, "bytes") == 734003200, "parse request");
        Check(LinkWire.Types(JsonNode.Parse("{\"lid\":1}")!.AsObject()).SequenceEqual(new[] { "wfd_legacy" }), "types default");

        // §6.4 log format.
        string line = LinkWire.FormatEvent("7", "go_started", ("peer", "abcdef12"), ("type", "wfd_legacy"), ("ms", 830L), ("ip", "192.168.137.1"),
                                           ("adapter", "Intel AX211"), ("drv", "23.60.1"), ("skip", null));
        Check(line == "AirBridge.Link lid=7 peer=abcdef12 type=wfd_legacy ev=go_started ms=830 ip=192.168.137.1 adapter=\"Intel AX211\" drv=23.60.1", line);
        Check(LinkWire.FormatEvent("7", "xfer", ("MBps", 18.4)) == "AirBridge.Link lid=7 ev=xfer MBps=18.4", "MBps format");
        Check(LinkWire.ChannelToMhz(36) == 5180 && LinkWire.ChannelToMhz(6) == 2437 && LinkWire.BandOf(5180) == "5", "channel map");
        var netsh = LinkWire.ParseNetshInterfaces("\r\nThere is 1 interface on the system:\r\n\r\n    Name                   : Wi-Fi\r\n    Description            : Intel(R) Wi-Fi 6E AX211 160MHz\r\n    State                  : connected\r\n    Band                   : 5 GHz\r\n    Channel                : 36\r\n");
        Check(netsh.Count == 1 && netsh[0]["channel"] == "36" && netsh[0]["band"] == "5 GHz", "netsh parse");

        // Optional cross-check with the Android agent's shared vectors.
        string android = args.Length > 0 ? args[0] : "docs/test-vectors/wfd-creds.json";
        if (File.Exists(android)) CrossCheck(android);
        else Console.WriteLine($"(no Android vectors at {android}; skipped)");

        Console.WriteLine($"{_passed} passed, {_failed} failed");
        return _failed == 0 ? 0 : 1;
    }

    /// <summary>Lenient reader: a top-level array, or an object with an array property; per vector the key as
    /// hex or base64 (k/K/key/k_hex/k_b64/ikm) plus hostId/clientId/gen/ssid/psk (snake_case accepted).</summary>
    private static void CrossCheck(string path)
    {
        JsonNode? root = JsonNode.Parse(File.ReadAllText(path));
        JsonArray? arr = root as JsonArray;
        if (arr == null && root is JsonObject ro)
            arr = ro.Select(kv => kv.Value).OfType<JsonArray>().FirstOrDefault();
        if (arr == null) { Check(false, "android vectors: no array found"); return; }
        int n = 0;
        foreach (var node in arr.OfType<JsonObject>())
        {
            string? S(params string[] keys) { foreach (var k in keys) if (node[k] is JsonValue v && v.TryGetValue(out string? s)) return s; return null; }
            int? I(params string[] keys) { foreach (var k in keys) if (node[k] is JsonValue v && v.TryGetValue(out int i)) return i; return null; }
            string? kh = S("k", "K", "key", "k_hex", "kHex", "ikm", "pairingKey");
            string? kb = S("k_b64", "kB64", "key_b64");
            byte[]? key = null;
            try { key = kh != null && kh.Length == 64 ? Convert.FromHexString(kh) : kb != null ? Convert.FromBase64String(kb) : kh != null ? Convert.FromBase64String(kh) : null; }
            catch { }
            string? host = S("hostId", "host_id", "host"), client = S("clientId", "client_id", "client");
            int gen = I("gen") ?? 0;
            string? ssid = S("ssid"), psk = S("psk");
            if (key == null || host == null || client == null || ssid == null || psk == null)
            {
                Check(false, $"android vector #{n}: unrecognised fields ({string.Join(",", node.Select(kv => kv.Key))})");
                n++;
                continue;
            }
            var c = LinkCreds.Wfd(key, host, client, gen);
            Check(c.Ssid == ssid, $"android vector #{n} ssid: ours {c.Ssid} theirs {ssid}");
            Check(c.Psk == psk, $"android vector #{n} psk: ours {c.Psk} theirs {psk}");
            if (S("wk") is string wkTheirs)
                Check(Convert.ToHexString(LinkCreds.WorkKey(key, host, client, gen)).Equals(wkTheirs, StringComparison.OrdinalIgnoreCase), $"android vector #{n} wk");
            n++;
        }
        Console.WriteLine($"cross-checked {n} Android vector(s) from {path}");
    }
}
