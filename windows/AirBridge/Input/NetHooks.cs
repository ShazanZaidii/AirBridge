using System.Diagnostics;

namespace AirBridge.Input
{
    /// <summary>Thin bridge from the input half to the app's status line (see App.SetControlStatus).</summary>
    internal static class NetHooks
    {
        public static void SetControlStatus(string? text)
        {
            try { App.SetControlStatus(text); }
            catch (Exception ex) { Debug.WriteLine($"SetControlStatus: {ex.Message}"); }
        }

        /// <summary>This PC's display name as peers know it (hello "name"); the machine name before the network starts.</summary>
        public static string LocalName()
        {
            try { if (App.Peers?.Identity.Name is { Length: > 0 } n) return n; }
            catch (Exception ex) { Debug.WriteLine($"LocalName: {ex.Message}"); }
            return Environment.MachineName;
        }
    }
}
