using System;
using System.Diagnostics;
using System.Threading.Tasks;

namespace AirBridge.Net
{
    /// <summary>
    /// Windows Firewall rules AirBridge needs so other devices can find and reach this PC: inbound TCP for the host
    /// ports (pairing and sessions 47632-47641, AirWeb 47650-47659) and inbound UDP 5353 (mDNS answers). Windows asks
    /// once when an app first listens, and a tray app's prompt is easy to miss or to refuse; "phones can't find my
    /// PC" is most often that. Rules are by port, so they also work for the Store (MSIX) build.
    /// </summary>
    public static class FirewallHelper
    {
        public const string RuleTcp = "AirBridge";
        public const string RuleMdns = "AirBridge mDNS";

        private static DateTime _checkedAt = DateTime.MinValue;
        private static bool? _present;

        /// <summary>True when both rules exist, false when one is missing, null when it can't be told (not Windows, no netsh).</summary>
        public static async Task<bool?> IsConfiguredAsync(bool force = false)
        {
            if (!force && _present != null && DateTime.UtcNow - _checkedAt < TimeSpan.FromSeconds(60)) return _present;
            var tcp = await RuleExistsAsync(RuleTcp).ConfigureAwait(false);
            var udp = await RuleExistsAsync(RuleMdns).ConfigureAwait(false);
            _present = tcp == null || udp == null ? null : tcp.Value && udp.Value;
            _checkedAt = DateTime.UtcNow;
            return _present;
        }

        private static async Task<bool?> RuleExistsAsync(string name)
        {
            try
            {
                var psi = new ProcessStartInfo("netsh", $"advfirewall firewall show rule name=\"{name}\"")
                {
                    CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true,
                };
                using var p = Process.Start(psi);
                if (p == null) return null;
                string output = await p.StandardOutput.ReadToEndAsync().ConfigureAwait(false);
                await p.WaitForExitAsync().ConfigureAwait(false);
                // Found: exit 0 and the rule is listed. Not found: exit 1 ("No rules match the specified criteria.").
                return p.ExitCode == 0 && output.Contains(name, StringComparison.OrdinalIgnoreCase);
            }
            catch (Exception ex)
            {
                Log.Warn($"Firewall check failed: {ex.Message}");
                return null;
            }
        }

        /// <summary>
        /// Adds both rules (one UAC prompt). Returns true when they are there afterwards; false if the user declined
        /// or it failed.
        /// </summary>
        public static async Task<bool> AddRulesAsync()
        {
            try
            {
                string tcp = $"netsh advfirewall firewall delete rule name=\\\"{RuleTcp}\\\" & netsh advfirewall firewall add rule name=\\\"{RuleTcp}\\\" " +
                             "dir=in action=allow protocol=TCP localport=47632-47641,47650-47659 profile=any enable=yes";
                string udp = $"netsh advfirewall firewall delete rule name=\\\"{RuleMdns}\\\" & netsh advfirewall firewall add rule name=\\\"{RuleMdns}\\\" " +
                             "dir=in action=allow protocol=UDP localport=5353 profile=any enable=yes";
                var psi = new ProcessStartInfo("cmd.exe", $"/c \"{tcp} & {udp}\"")
                {
                    Verb = "runas", UseShellExecute = true, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden,
                };
                using var p = Process.Start(psi);
                if (p == null) return false;
                await p.WaitForExitAsync().ConfigureAwait(false);
            }
            catch (Exception ex) // the user said No in the UAC prompt
            {
                Log.Info($"Firewall rules not added: {ex.Message}");
                return false;
            }
            return await IsConfiguredAsync(force: true).ConfigureAwait(false) == true;
        }
    }
}
