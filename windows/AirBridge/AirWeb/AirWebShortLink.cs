using System;
using System.Security.Cryptography;
using System.Text;

namespace AirBridge.AirWeb
{
    /// <summary>
    /// A short, typeable stand-in for the session link, for a computer where the code can't be scanned
    /// (<c>http://10.0.0.5:47650/j/K3X9QP</c> instead of <c>…/?t=7ky5Rro0b…</c>). The full link stays the real one;
    /// this only redirects to it. Same rules as on the phone and the Mac:
    /// nothing exists until a connected page asks, it lasts 10 minutes, six characters from an alphabet without
    /// look-alikes, and 8 wrong guesses in a minute refuse every try (right or wrong) until the minute is over.
    /// </summary>
    public sealed class AirWebShortLink
    {
        public enum Outcome { Match, Miss, Locked }

        public const string Alphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
        public const int Length = 6;

        private readonly long _ttlMs, _windowMs;
        private readonly int _maxMisses;
        private readonly Func<long> _clock;
        private readonly object _gate = new();
        private string? _code;
        private long _until;
        private long _windowStart = long.MinValue;
        private int _misses;

        public AirWebShortLink(long ttlMs = 600_000, int maxMisses = 8, long windowMs = 60_000, Func<long>? clock = null)
        {
            _ttlMs = ttlMs; _maxMisses = maxMisses; _windowMs = windowMs;
            _clock = clock ?? (() => Environment.TickCount64);
        }

        /// <summary>The live code (made now if there is none or it ran out) and how long it still has.</summary>
        public (string Code, long SecondsLeft) Issue()
        {
            lock (_gate)
            {
                long now = _clock();
                if (_code == null || now >= _until)
                {
                    var sb = new StringBuilder(Length);
                    for (int i = 0; i < Length; i++) sb.Append(Alphabet[RandomNumberGenerator.GetInt32(Alphabet.Length)]);
                    _code = sb.ToString();
                    _until = now + _ttlMs;
                }
                return (_code, (_until - now + 999) / 1000);
            }
        }

        /// <summary>Checks what someone typed after <c>/j/</c>. Letters in any case are accepted.</summary>
        public Outcome Check(string? given)
        {
            lock (_gate)
            {
                long now = _clock();
                if (_windowStart == long.MinValue || now - _windowStart >= _windowMs) { _windowStart = now; _misses = 0; }
                if (_misses >= _maxMisses) return Outcome.Locked;
                bool ok = _code != null && now < _until && given != null && given.Length == Length &&
                    CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(given.ToUpperInvariant()), Encoding.UTF8.GetBytes(_code));
                if (ok) return Outcome.Match;
                _misses++;
                return Outcome.Miss;
            }
        }
    }
}
