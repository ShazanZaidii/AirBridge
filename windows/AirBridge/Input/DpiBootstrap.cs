using System.Runtime.CompilerServices;

namespace AirBridge.Input
{
    internal static class DpiBootstrap
    {
        /// <summary>
        /// Runs when the assembly loads, i.e. before Main and before WPF reads the process DPI mode.
        /// Fails harmlessly (ERROR_ACCESS_DENIED) if a manifest already set the awareness.
        /// Input threads additionally set per-monitor-v2 on themselves (see <see cref="EnterThread"/>).
        /// </summary>
#pragma warning disable CA2255
        [ModuleInitializer]
#pragma warning restore CA2255
        internal static void Init()
        {
            try { NativeMethods.SetProcessDpiAwarenessContext(NativeMethods.DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2); }
            catch { /* pre-1703 Windows: stay with the manifest default */ }
        }

        /// <summary>Makes the calling thread per-monitor-v2 aware so all coordinates are physical pixels.</summary>
        internal static IntPtr EnterThread()
        {
            try { return NativeMethods.SetThreadDpiAwarenessContext(NativeMethods.DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2); }
            catch { return IntPtr.Zero; }
        }

        internal static void RestoreThread(IntPtr previous)
        {
            if (previous == IntPtr.Zero) return;
            try { NativeMethods.SetThreadDpiAwarenessContext(previous); } catch { }
        }
    }
}
