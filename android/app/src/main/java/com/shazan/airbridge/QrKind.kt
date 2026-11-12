package com.shazan.airbridge

import java.net.URI
import java.util.Locale

/**
 * What a scanned QR code is, so the scanner can do the right thing instead of ignoring everything that is not
 * a pairing code. Pure Kotlin (no Android classes) so it is unit-tested on the JVM.
 */
sealed interface QrKind {
    /** `airbridge://pair…`: handed to the pairing code, which decides whether it is valid and current. */
    data class Pair(val raw: String) : QrKind
    /** Another `airbridge://` link (a different version, or not a pairing one). */
    data class OtherAirBridge(val raw: String) : QrKind
    /** The page of an AirWeb host on this network: `http://192.168.1.4:47650/?t=…` or `…/j/K3X9QP`. */
    data class AirWeb(val url: String, val host: String) : QrKind
    /** Any other web address. */
    data class Web(val url: String, val host: String) : QrKind
    /** A Wi-Fi sign-in code (`WIFI:T:WPA;S:name;P:pass;;`). */
    data class Wifi(val ssid: String) : QrKind
    data class Other(val text: String) : QrKind

    companion object {
        /** The most useful of several codes seen together: pairing first, then AirWeb, then the rest. */
        fun best(codes: List<String>): QrKind? = codes.map { classify(it) }.minByOrNull { rank(it) }

        private fun rank(k: QrKind) = when (k) {
            is Pair -> 0; is AirWeb -> 1; is OtherAirBridge -> 2; is Web -> 3; is Wifi -> 4; is Other -> 5
        }

        fun classify(raw: String): QrKind {
            val text = raw.trim()
            val lower = text.lowercase(Locale.ROOT)
            if (lower.startsWith("airbridge://pair")) return Pair(text)
            if (lower.startsWith("airbridge://")) return OtherAirBridge(text)
            if (lower.startsWith("wifi:")) return Wifi(wifiName(text))
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                val uri = try { URI(text) } catch (_: Exception) { null }
                val host = uri?.host
                if (uri != null && host != null) {
                    return if (lower.startsWith("http://") && isAirWebPath(uri) && isLocalHost(host)) AirWeb(text, hostWithPort(uri))
                    else Web(text, host)
                }
            }
            return Other(text)
        }

        private fun hostWithPort(u: URI) = if (u.port > 0) "${u.host}:${u.port}" else u.host

        /** `/?t=<token>` (the full link) or `/j/<code>` (the short one). */
        private fun isAirWebPath(u: URI): Boolean {
            val q = u.rawQuery.orEmpty()
            val token = q.split('&').firstOrNull { it.startsWith("t=") }?.drop(2).orEmpty()
            if (token.length >= 20 && token.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return true
            val path = u.rawPath.orEmpty()
            return path.startsWith("/j/") && path.length == 9
        }

        /** A private address or a `.local` name: where an AirWeb host can be. */
        fun isLocalHost(host: String): Boolean {
            val h = host.lowercase(Locale.ROOT)
            if (h.endsWith(".local")) return true
            val p = h.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4 || p.any { it !in 0..255 }) return false
            return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
        }

        private fun wifiName(t: String): String {
            val m = Regex("""(?:^|;|:)S:((?:\\.|[^;\\])*)""").find(t)
            return m?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1").orEmpty()
        }
    }
}
