using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace AirBridge.AirWeb
{
    /// <summary>One parsed request: no body bytes are read here.</summary>
    internal sealed class HttpRequest
    {
        public string Method = "";
        /// <summary>Decoded path without the query.</summary>
        public string Path = "";
        public Dictionary<string, string> Query = new();
        /// <summary>Lower-case names.</summary>
        public Dictionary<string, string> Headers = new();

        /// <summary>Null when absent; -1 when malformed.</summary>
        public long? ContentLength
        {
            get
            {
                if (!Headers.TryGetValue("content-length", out var v)) return null;
                if (v.Length == 0 || v.Length > 18 || v.Any(c => c < '0' || c > '9')) return -1;
                return long.Parse(v);
            }
        }

        public string Q(string name) => Query.TryGetValue(name, out var v) ? v : "";
    }

    /// <summary>Reads from a socket with a small carry-over buffer: the bytes after the head belong to the body.</summary>
    internal sealed class HttpReader
    {
        private readonly Stream _s;
        private readonly byte[] _buf = new byte[16 * 1024];
        private int _pos, _len;
        public HttpReader(Stream s) { _s = s; }

        public async ValueTask<int> ReadAsync(byte[] dst, int off, int count, CancellationToken ct)
        {
            if (_pos < _len)
            {
                int n = Math.Min(count, _len - _pos);
                Buffer.BlockCopy(_buf, _pos, dst, off, n);
                _pos += n;
                return n;
            }
            return await _s.ReadAsync(dst.AsMemory(off, count), ct).ConfigureAwait(false);
        }

        /// <summary>Request line + headers, or null if malformed / too large / not HTTP.</summary>
        public async Task<HttpRequest?> ReadHeadAsync(CancellationToken ct)
        {
            const int MaxHead = 16 * 1024, MaxHeaders = 100;
            var lines = new List<string>();
            var line = new StringBuilder();
            int total = 0;
            bool done = false;
            while (!done)
            {
                if (_pos >= _len)
                {
                    _len = await _s.ReadAsync(_buf.AsMemory(0, _buf.Length), ct).ConfigureAwait(false);
                    _pos = 0;
                    if (_len <= 0) return null;
                }
                while (_pos < _len)
                {
                    int c = _buf[_pos++];
                    if (++total > MaxHead) return null;
                    if (c == '\n')
                    {
                        string l = line.ToString().TrimEnd('\r');
                        line.Clear();
                        if (l.Length == 0) { if (lines.Count == 0) continue; done = true; break; } // tolerate a stray CRLF first
                        lines.Add(l);
                        if (lines.Count > MaxHeaders + 1) return null;
                    }
                    else line.Append((char)c); // ISO-8859-1: one byte, one char
                }
            }
            var parts = lines[0].Split(' ');
            if (parts.Length != 3 || !parts[2].StartsWith("HTTP/1.", StringComparison.Ordinal)) return null;
            var req = new HttpRequest { Method = parts[0] };
            if (req.Method.Length == 0 || req.Method.Any(ch => ch < 'A' || ch > 'Z')) return null;
            string target = parts[1];
            if (!target.StartsWith('/')) return null;
            int qi = target.IndexOf('?');
            string rawPath = qi < 0 ? target : target[..qi];
            req.Path = Decode(rawPath, plusIsSpace: false);
            foreach (var pair in (qi < 0 ? "" : target[(qi + 1)..]).Split('&', StringSplitOptions.RemoveEmptyEntries))
            {
                int eq = pair.IndexOf('=');
                string k = Decode(eq < 0 ? pair : pair[..eq], true), v = eq < 0 ? "" : Decode(pair[(eq + 1)..], true);
                req.Query.TryAdd(k, v); // first wins
            }
            for (int i = 1; i < lines.Count; i++)
            {
                int c = lines[i].IndexOf(':');
                if (c <= 0) return null;
                string name = lines[i][..c].Trim().ToLowerInvariant(), value = lines[i][(c + 1)..].Trim();
                if (name == "content-length" && req.Headers.TryGetValue(name, out var prev) && prev != value) return null; // smuggling guard
                req.Headers[name] = value;
            }
            return req;
        }

        private static string Decode(string s, bool plusIsSpace)
        {
            // The head is Latin-1 on the wire; the browser's %XX escapes carry UTF-8.
            try { return Uri.UnescapeDataString(plusIsSpace ? s.Replace('+', ' ') : s); } catch { return s; }
        }
    }

    internal static class Http
    {
        public const string PageHeaders =
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
            "connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n" +
            "X-Frame-Options: DENY\r\n";

        public static string Head(int code, string reason, string type, long? length, string extra = "")
        {
            var sb = new StringBuilder(256);
            sb.Append("HTTP/1.1 ").Append(code).Append(' ').Append(reason).Append("\r\n");
            sb.Append("Content-Type: ").Append(type).Append("\r\n");
            if (length != null) sb.Append("Content-Length: ").Append(length.Value).Append("\r\n");
            sb.Append("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n");
            sb.Append(extra);
            sb.Append("Connection: close\r\n\r\n");
            return sb.ToString();
        }

        public static bool TokenMatches(string? given, string expected)
        {
            if (string.IsNullOrEmpty(given) || expected.Length == 0) return false;
            return CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(given), Encoding.UTF8.GetBytes(expected));
        }

        /// <summary>The page's random client id, or null when absent or not 8 to 40 of A-Za-z0-9_-.</summary>
        public static string? CleanClientId(string? raw) =>
            raw != null && raw.Length is >= 8 and <= 40 && raw.All(c => c is (>= 'A' and <= 'Z') or (>= 'a' and <= 'z') or (>= '0' and <= '9') or '_' or '-') ? raw : null;

        /// <summary>A device label for "who is here": no control characters, collapsed spaces, 40 characters.</summary>
        public static string CleanLabel(string? raw)
        {
            var t = new string((raw ?? "").Where(c => c >= ' ' && c != '\u007f' && c != '\u2028' && c != '\u2029').ToArray());
            t = System.Text.RegularExpressions.Regex.Replace(t, @"\s+", " ").Trim();
            if (t.Length > 40) t = t[..40].TrimEnd();
            return t.Length == 0 ? "Browser" : t;
        }

        /// <summary>A short fingerprint of the pool: the page sends it back to skip an unchanged list.</summary>
        public static string Signature(IReadOnlyList<WebFile> pool)
        {
            unchecked
            {
                long h = (long)0xcbf29ce484222325UL; // FNV-1a offset basis
                void Mix(long x) { h = (h ^ x) * 0x100000001b3L; }
                void MixS(string? s) { if (s == null) Mix(-1L); else { foreach (char c in s) Mix(c); Mix(0x1f); } }
                foreach (var f in pool) { Mix(f.Id); MixS(f.Name); Mix(f.Size); Mix(f.Added); MixS(f.By); MixS(f.Owner); }
                Mix(pool.Count);
                return ((ulong)h).ToString("x");
            }
        }

        /// <summary>`1-40,1000001,1000003-1000010` → ids in that order, or null when blank (everything).</summary>
        public static List<int>? ParseIds(string? raw, int limit)
        {
            if (string.IsNullOrWhiteSpace(raw)) return null;
            var seen = new HashSet<int>(); var order = new List<int>();
            foreach (var part in raw.Split(','))
            {
                var t = part.Trim();
                if (t.Length == 0 || order.Count >= limit) continue;
                int dash = t.IndexOf('-');
                if (!int.TryParse(dash < 0 ? t : t[..dash], out int a)) continue;
                int b = a;
                if (dash >= 0 && !int.TryParse(t[(dash + 1)..], out b)) continue;
                if (a < 0 || b < a) continue;
                for (long id = a; id <= b && order.Count < limit; id++) if (seen.Add((int)id)) order.Add((int)id);
            }
            return order;
        }

        public static string BaseName(string raw)
        {
            int i = Math.Max(raw.LastIndexOf('/'), raw.LastIndexOf('\\'));
            return (i < 0 ? raw : raw[(i + 1)..]).Trim();
        }

        public static string ContentDisposition(string name)
        {
            var clean = new string(name.Where(c => c >= ' ' && c != '\u007f').ToArray());
            if (clean.Length == 0) clean = "file";
            var ascii = new string(clean.Select(c => c >= 0x20 && c <= 0x7e && c != '"' && c != '\\' ? c : '_').ToArray());
            var enc = new StringBuilder();
            foreach (byte b in Encoding.UTF8.GetBytes(clean))
            {
                char ch = (char)b;
                if (ch is (>= 'a' and <= 'z') or (>= 'A' and <= 'Z') or (>= '0' and <= '9') || "!#$&+-.^_`|~".IndexOf(ch) >= 0) enc.Append(ch);
                else enc.Append('%').Append("0123456789ABCDEF"[b >> 4]).Append("0123456789ABCDEF"[b & 15]);
            }
            return $"attachment; filename=\"{ascii}\"; filename*=UTF-8''{enc}";
        }

        public static string EscapeHtml(string s) => s.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;").Replace("\"", "&quot;").Replace("'", "&#39;");

        public static string JsonString(string s)
        {
            var sb = new StringBuilder(s.Length + 2).Append('"');
            foreach (char c in s)
            {
                if (c == '"') sb.Append("\\\"");
                else if (c == '\\') sb.Append("\\\\");
                else if (c == '\n') sb.Append("\\n");
                else if (c == '\r') sb.Append("\\r");
                else if (c == '\t') sb.Append("\\t");
                else if (c < ' ' || c == '\u2028' || c == '\u2029' || c == '<') sb.Append("\\u").Append(((int)c).ToString("x4"));
                else sb.Append(c);
            }
            return sb.Append('"').ToString();
        }

        /// <summary>The matrix (true = dark, tight to the symbol) as an SVG with the 4-module quiet zone, black on white.</summary>
        public static string QrSvg(bool[][] m)
        {
            int n = m.Length, q = 4;
            var sb = new StringBuilder(2048);
            sb.Append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").Append(n + 2 * q).Append(' ').Append(n + 2 * q)
              .Append("\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\"");
            for (int y = 0; y < n; y++)
            {
                int x = 0;
                while (x < n)
                {
                    if (!m[y][x]) { x++; continue; }
                    int e = x;
                    while (e < n && m[y][e]) e++;
                    sb.Append('M').Append(x + q).Append(' ').Append(y + q).Append('h').Append(e - x).Append("v1h-").Append(e - x).Append('z');
                    x = e;
                }
            }
            return sb.Append("\"/></svg>").ToString();
        }

        public static string NoticePage(string title, string detail) =>
            "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>AirWeb</title>" +
            "<style>body{font-family:-apple-system,system-ui,\"Segoe UI\",Roboto,sans-serif;background:#f4f4f3;color:#1b1b1b;margin:0;padding:24px;line-height:1.5}" +
            "@media (prefers-color-scheme:dark){body{background:#161616;color:#ececec}}main{max-width:32rem;margin:10vh auto}</style></head>" +
            $"<body><main><h1 style=\"font-size:1.4rem\">{EscapeHtml(title)}</h1><p>{EscapeHtml(detail)}</p></main></body></html>";

        public static string ExpiredPage() => NoticePage("This link has stopped working", "Scan the code on the computer again to get a new link.");
    }
}
