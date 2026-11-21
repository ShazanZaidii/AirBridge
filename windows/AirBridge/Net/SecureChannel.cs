using System;
using System.Buffers.Binary;
using System.Numerics;
using System.Security.Cryptography;
using System.Text;

namespace AirBridge.Net
{
    /// <summary>
    /// Handshake cryptography (§3.1–§3.3, §3.6): ephemeral P-256 keys, the transcript T, confirm digits and
    /// key derivations. Everything here is pure (no I/O) so it can be checked against other implementations.
    /// </summary>
    public static class HandshakeCrypto
    {
        public const int PublicKeyLength = 65;

        // NIST P-256 domain parameters (SEC 2 / FIPS 186-4), used for an explicit on-curve check of peer points.
        private static readonly BigInteger P = BigInteger.Parse("0FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", System.Globalization.NumberStyles.HexNumber);
        private static readonly BigInteger B = BigInteger.Parse("05AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", System.Globalization.NumberStyles.HexNumber);

        /// <summary>A fresh ephemeral key pair for one connection.</summary>
        public static ECDiffieHellman NewEphemeral() => ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);

        /// <summary>X9.63 uncompressed encoding 0x04‖X‖Y (65 bytes).</summary>
        public static byte[] EncodePublicKey(ECDiffieHellman key)
        {
            var q = key.ExportParameters(false).Q;
            var pk = new byte[PublicKeyLength];
            pk[0] = 0x04;
            q.X!.AsSpan().CopyTo(pk.AsSpan(1 + 32 - q.X!.Length, q.X.Length));
            q.Y!.AsSpan().CopyTo(pk.AsSpan(33 + 32 - q.Y!.Length, q.Y.Length));
            return pk;
        }

        /// <summary>Imports and validates a peer point. Returns null if it is not a valid P-256 point.</summary>
        public static ECDiffieHellman? ImportPeerKey(byte[]? pk)
        {
            if (pk == null || pk.Length != PublicKeyLength || pk[0] != 0x04) return null;
            byte[] x = pk[1..33], y = pk[33..65];
            if (!IsOnCurve(x, y)) return null;
            try
            {
                var peer = ECDiffieHellman.Create();
                peer.ImportParameters(new ECParameters { Curve = ECCurve.NamedCurves.nistP256, Q = new ECPoint { X = x, Y = y } });
                return peer;
            }
            catch (CryptographicException) { return null; }
        }

        /// <summary>y² = x³ − 3x + b (mod p), coordinates in range. The import also validates; this makes the
        /// check independent of the platform crypto provider.</summary>
        private static bool IsOnCurve(byte[] xb, byte[] yb)
        {
            var x = new BigInteger(xb, isUnsigned: true, isBigEndian: true);
            var y = new BigInteger(yb, isUnsigned: true, isBigEndian: true);
            if (x >= P || y >= P) return false;
            var lhs = BigInteger.ModPow(y, 2, P);
            var rhs = (BigInteger.ModPow(x, 3, P) - 3 * x + B) % P;
            if (rhs < 0) rhs += P;
            return lhs == rhs;
        }

        private static volatile bool _reverseZ;

        /// <summary>Z = x-coordinate of ECDH(own private, peer public), 32 bytes big-endian (raw, not hashed).</summary>
        public static byte[] SharedSecret(ECDiffieHellman own, ECDiffieHellman peer)
        {
            byte[] z = own.DeriveRawSecretAgreement(peer.PublicKey);
            if (_reverseZ) Array.Reverse(z);
            return z;
        }

        /// <summary>
        /// Known-answer test for ECDH (vector generated with Python `cryptography`). Guards against a provider
        /// returning the raw secret in the wrong byte order (CNG's raw secret is little-endian internally): if the
        /// result is exactly reversed we compensate, otherwise we log loudly. Returns true when Z matches.
        /// </summary>
        public static bool SelfTest()
        {
            try
            {
                using var own = ECDiffieHellman.Create();
                var q1 = Convert.FromHexString("045e247613ba8ed01ca47ffe036046edfa596517db67d04e7889e2bd3b39787dda9087d626af7f071353a7fb7219688d3b259b01693f322e87dfe580dee83f0027");
                own.ImportParameters(new ECParameters
                {
                    Curve = ECCurve.NamedCurves.nistP256,
                    D = Convert.FromHexString("1f2e3d4c5b6a79880123456789abcdeffedcba98765432100f1e2d3c4b5a6978"),
                    Q = new ECPoint { X = q1[1..33], Y = q1[33..] },
                });
                using var peer = ImportPeerKey(Convert.FromHexString("04fd982da4ec190b21d44f9e1a4975a6cca39e42c479decb5894dcc41ad2ac65b34086a6181f3814b497f6b275dc79f139a57fb7e665c2fdeff8ada1e0b02bfdd1"))
                                 ?? throw new CryptographicException("KAT peer point rejected");
                byte[] expected = Convert.FromHexString("1d4d3e373be4d4a182ef406ed1d44fb1c2feb4da522f9746ff535b614005e728");
                byte[] z = own.DeriveRawSecretAgreement(peer.PublicKey);
                if (z.AsSpan().SequenceEqual(expected)) { _reverseZ = false; return true; }
                Array.Reverse(z);
                if (z.AsSpan().SequenceEqual(expected))
                {
                    _reverseZ = true;
                    Log.Warn("ECDH raw secret came back little-endian; compensating");
                    return true;
                }
                Log.Error("ECDH known-answer test FAILED: sessions with other platforms will not work");
                return false;
            }
            catch (Exception ex)
            {
                Log.Error("ECDH self-test could not run", ex);
                return false;
            }
        }

        /// <summary>T = cNonce | "|" | hNonce | "|" | clientId | "|" | hostId | "|" | cPk | "|" | hPk</summary>
        public static byte[] Transcript(byte[] cNonce, byte[] hNonce, string clientId, string hostId, byte[] cPk, byte[] hPk) =>
            Protocol.Cat(cNonce, "|", hNonce, "|", clientId, "|", hostId, "|", cPk, "|", hPk);

        /// <summary>6 digits = u32be(HMAC(Z, "airbridge/v2/confirm|" | T)[0..4]) mod 1 000 000.</summary>
        public static string ConfirmDigits(byte[] z, byte[] t)
        {
            byte[] h = Protocol.Hmac(z, Protocol.Cat("airbridge/v2/confirm|", t));
            uint v = BinaryPrimitives.ReadUInt32BigEndian(h);
            return (v % 1_000_000).ToString("D6");
        }

        /// <summary>Mode confirm: K = HKDF(Z, salt = cNonce|hNonce, info = "airbridge/v2/pairkey|" | clientId | "|" | hostId, 32).</summary>
        public static byte[] ConfirmPairKey(byte[] z, byte[] cNonce, byte[] hNonce, string clientId, string hostId) =>
            HKDF.DeriveKey(HashAlgorithmName.SHA256, z, 32, Protocol.Cat(cNonce, hNonce),
                           Protocol.Cat("airbridge/v2/pairkey|", clientId, "|", hostId));

        /// <summary>Mode qr: K = HMAC(S, "airbridge/v2/pair|" | clientId | "|" | hostId).</summary>
        public static byte[] QrPairKey(byte[] secret, string clientId, string hostId) =>
            Protocol.Hmac(secret, Protocol.Cat("airbridge/v2/pair|", clientId, "|", hostId));

        /// <summary>keys = HKDF(Z, salt = cNonce|hNonce, info = "airbridge/v2/session|" | T, 64); c2h = [0,32), h2c = [32,64).</summary>
        public static (byte[] C2H, byte[] H2C) SessionKeys(byte[] z, byte[] cNonce, byte[] hNonce, byte[] t)
        {
            byte[] keys = HKDF.DeriveKey(HashAlgorithmName.SHA256, z, 64, Protocol.Cat(cNonce, hNonce),
                                         Protocol.Cat("airbridge/v2/session|", t));
            var r = (keys[..32], keys[32..]);
            CryptographicOperations.ZeroMemory(keys);
            return r;
        }

        public static byte[] AuthMac(byte[] k, byte[] t) => Protocol.Hmac(k, Protocol.Cat("auth|", t));
        public static byte[] AuthAckMac(byte[] k, byte[] t) => Protocol.Hmac(k, Protocol.Cat("auth_ack|", t));
        public static byte[] PairProof(byte[] k, byte[] t) => Protocol.Hmac(k, Protocol.Cat("pair|", t));
        public static byte[] PairAckProof(byte[] k, byte[] t) => Protocol.Hmac(k, Protocol.Cat("pair_ack|", t));
    }

    /// <summary>
    /// The §3.6 envelope for one session: AES-256-GCM per direction, nonce = 0⁴‖seq, AAD = the 9 header bytes.
    /// <see cref="Seal"/> is called only from the session's single send loop and <see cref="Open"/> only from its
    /// receive loop, so each direction's sequence counter is single-threaded.
    /// </summary>
    public sealed class SecureChannel : IDisposable
    {
        public const byte KindEnvelope = 0x02;
        public const int HeaderLength = 9;
        public const int TagLength = 16;
        public const byte InnerJson = 0;
        public const byte InnerBinary = 1;
        /// <summary>Envelope overhead around a payload: header + inner kind + tag.</summary>
        public const int Overhead = HeaderLength + 1 + TagLength;

        private readonly AesGcm _send;
        private readonly AesGcm _recv;
        private ulong _sendSeq;
        private ulong _recvSeq;

        public SecureChannel(byte[] sendKey, byte[] recvKey)
        {
            _send = new AesGcm(sendKey, TagLength);
            _recv = new AesGcm(recvKey, TagLength);
            CryptographicOperations.ZeroMemory(sendKey);
            CryptographicOperations.ZeroMemory(recvKey);
        }

        /// <summary>Writes a complete envelope frame into <paramref name="dest"/> (≥ payload + <see cref="Overhead"/>)
        /// and returns its length.</summary>
        public int Seal(byte innerKind, ReadOnlySpan<byte> payload, byte[] dest)
        {
            int ptLen = 1 + payload.Length;
            ulong seq = _sendSeq++;
            dest[0] = KindEnvelope;
            BinaryPrimitives.WriteUInt64BigEndian(dest.AsSpan(1, 8), seq);
            dest[HeaderLength] = innerKind;
            payload.CopyTo(dest.AsSpan(HeaderLength + 1));
            Span<byte> nonce = stackalloc byte[12];
            nonce.Clear();
            BinaryPrimitives.WriteUInt64BigEndian(nonce[4..], seq);
            var pt = dest.AsSpan(HeaderLength, ptLen);
            _send.Encrypt(nonce, pt, pt, dest.AsSpan(HeaderLength + ptLen, TagLength), dest.AsSpan(0, HeaderLength));
            return HeaderLength + ptLen + TagLength;
        }

        public enum OpenResult { Ok, Malformed, BadSeq, BadTag }

        /// <summary>Decrypts an envelope in place. On success the inner kind is at data[9] and the payload is
        /// data[10 .. 10+payloadLength).</summary>
        public OpenResult Open(byte[] data, int n, out byte innerKind, out int payloadLength)
        {
            innerKind = 0; payloadLength = 0;
            if (n < Overhead || data[0] != KindEnvelope) return OpenResult.Malformed;
            ulong seq = BinaryPrimitives.ReadUInt64BigEndian(data.AsSpan(1, 8));
            if (seq != _recvSeq) return OpenResult.BadSeq;
            Span<byte> nonce = stackalloc byte[12];
            nonce.Clear();
            BinaryPrimitives.WriteUInt64BigEndian(nonce[4..], seq);
            int ctLen = n - HeaderLength - TagLength;
            var ct = data.AsSpan(HeaderLength, ctLen);
            try { _recv.Decrypt(nonce, ct, data.AsSpan(n - TagLength, TagLength), ct, data.AsSpan(0, HeaderLength)); }
            catch (CryptographicException) { return OpenResult.BadTag; }
            _recvSeq++;
            innerKind = data[HeaderLength];
            payloadLength = ctLen - 1;
            return OpenResult.Ok;
        }

        public void Dispose()
        {
            _send.Dispose();
            _recv.Dispose();
        }
    }
}
