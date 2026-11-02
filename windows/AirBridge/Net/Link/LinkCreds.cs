using System;
using System.Security.Cryptography;
using System.Text;

namespace AirBridge.Net.Link
{
    /// <summary>Wi-Fi Direct legacy-group credentials for one pairing (docs/protocol.md §10.1).</summary>
    public sealed record WfdCreds(string Ssid, string Psk, int Gen);

    /// <summary>
    /// Credential derivation for link upgrades (PROTOCOL §10.1), fixed per pairing (never rotated by time):
    /// <code>
    /// wk   = HKDF-SHA256(ikm = K, salt = "airbridge/v2/wfd", info = hostId | "|" | clientId | "|" | u8(gen), 32 bytes)
    /// ssid = "DIRECT-AB-" + base32(HMAC-SHA256(wk, "ssid")[0..5))   // RFC 4648, 8 chars, no padding
    /// psk  = base64url(HMAC-SHA256(wk, "psk")[0..24))               // 32 ASCII chars, no padding
    /// </code>
    /// hostId = the WebSocket host's id (this PC for <c>wfd_legacy</c>), clientId = the dialing peer's id.
    /// Pure logic: no dependency on the rest of the app, so AirBridge.LinkTests can compile it on any OS.
    /// </summary>
    public static class LinkCreds
    {
        public const string SsidPrefix = "DIRECT-AB-";
        private static readonly byte[] Salt = Encoding.UTF8.GetBytes("airbridge/v2/wfd");
        private const string Base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

        public static WfdCreds Wfd(byte[] k, string hostId, string clientId, int gen)
        {
            if (k == null || k.Length == 0) throw new ArgumentException("pairing key required", nameof(k));
            if (gen is < 0 or > 255) throw new ArgumentOutOfRangeException(nameof(gen), "gen is a u8");
            byte[] wk = WorkKey(k, hostId, clientId, gen);
            try
            {
                using var h = new HMACSHA256(wk);
                byte[] s = h.ComputeHash(Encoding.UTF8.GetBytes("ssid"));
                byte[] p = h.ComputeHash(Encoding.UTF8.GetBytes("psk"));
                string ssid = SsidPrefix + Base32(s.AsSpan(0, 5));
                string psk = Base64Url(p.AsSpan(0, 24));
                CryptographicOperations.ZeroMemory(s);
                CryptographicOperations.ZeroMemory(p);
                return new WfdCreds(ssid, psk, gen);
            }
            finally { CryptographicOperations.ZeroMemory(wk); }
        }

        /// <summary>wk (exposed for the test vectors).</summary>
        public static byte[] WorkKey(byte[] k, string hostId, string clientId, int gen)
        {
            byte[] idPart = Encoding.UTF8.GetBytes(hostId + "|" + clientId + "|");
            byte[] info = new byte[idPart.Length + 1];
            Buffer.BlockCopy(idPart, 0, info, 0, idPart.Length);
            info[^1] = (byte)gen;
            return HKDF.DeriveKey(HashAlgorithmName.SHA256, k, 32, Salt, info);
        }

        /// <summary>RFC 4648 base32 (upper case), no padding.</summary>
        public static string Base32(ReadOnlySpan<byte> data)
        {
            var sb = new StringBuilder((data.Length * 8 + 4) / 5);
            int buffer = 0, bits = 0;
            foreach (byte b in data)
            {
                buffer = (buffer << 8) | b;
                bits += 8;
                while (bits >= 5)
                {
                    sb.Append(Base32Alphabet[(buffer >> (bits - 5)) & 31]);
                    bits -= 5;
                }
                buffer &= (1 << bits) - 1;
            }
            if (bits > 0) sb.Append(Base32Alphabet[(buffer << (5 - bits)) & 31]);
            return sb.ToString();
        }

        /// <summary>RFC 4648 §5 base64url, no padding.</summary>
        public static string Base64Url(ReadOnlySpan<byte> data) =>
            Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }
}
