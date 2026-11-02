using System.Diagnostics;
using System.Reflection;

namespace AirBridge.Input
{
    /// <summary>
    /// Calls into the network half/UI without a compile-time dependency, so both halves merge trivially.
    /// <c>App.SetControlStatus(string? text)</c> (static, thread-safe) is provided by the network half; until it
    /// exists this is a no-op. Once merged this can be replaced by a direct call.
    /// </summary>
    internal static class NetHooks
    {
        private static readonly Action<string?>? SetStatus = Resolve();

        private static Action<string?>? Resolve()
        {
            try
            {
                var m = typeof(App).GetMethod("SetControlStatus", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static,
                    null, new[] { typeof(string) }, null);
                return m == null ? null : (Action<string?>)Delegate.CreateDelegate(typeof(Action<string?>), m);
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"App.SetControlStatus unavailable: {ex.Message}");
                return null;
            }
        }

        public static void SetControlStatus(string? text)
        {
            try { SetStatus?.Invoke(text); }
            catch (Exception ex) { Debug.WriteLine($"SetControlStatus: {ex.Message}"); }
        }
    }
}
