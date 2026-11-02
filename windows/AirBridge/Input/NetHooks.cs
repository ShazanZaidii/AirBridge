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
    }
}
