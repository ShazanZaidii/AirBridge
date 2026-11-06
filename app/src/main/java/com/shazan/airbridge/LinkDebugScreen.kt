package com.shazan.airbridge

import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.shazan.airbridge.net.Hub
import com.shazan.airbridge.net.PairStore
import com.shazan.airbridge.net.link.JoinFailed
import com.shazan.airbridge.net.link.LinkBench
import com.shazan.airbridge.net.link.LinkManager
import com.shazan.airbridge.net.link.WfdJoiner
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.AbScaffold
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.InfoRow
import com.shazan.airbridge.ui.theme.PrimaryButton
import com.shazan.airbridge.ui.theme.RowDivider
import com.shazan.airbridge.ui.theme.SecondaryButton
import com.shazan.airbridge.ui.theme.SectionHeader
import com.shazan.airbridge.ui.theme.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Hidden Wi-Fi Direct test screen (long-press the title in Settings) for device tests S-W2/S-W3/S-W4
 * (docs/WIFI_DIRECT_ANDROID_WINDOWS.md §6.5). Joins the network derived for a paired peer directly with
 * [WfdJoiner] (no `link_*` signalling), measures raw TCP throughput bound to that network, and shows the
 * `AirBridge.Link` log. Leaving the screen releases the network.
 */
@Composable
fun LinkDebugScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val peers = remember { PairStore.all().filter { it.via == "lan" }.sortedByDescending { it.plat == "windows" } }
    var peerId by remember { mutableStateOf(peers.firstOrNull()?.id) }
    val peer = peers.firstOrNull { it.id == peerId }
    val creds = remember(peerId) { peerId?.let { LinkManager.credsFor(it) } }
    var showPsk by remember { mutableStateOf(false) }
    var longTimeout by remember { mutableStateOf(true) }

    var joined by remember { mutableStateOf<WfdJoiner.Joined?>(null) }
    var joinJob by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("Not joined") }
    var runs by remember { mutableStateOf(0) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5201") }
    var bench by remember { mutableStateOf("") }
    var benchJob by remember { mutableStateOf<Job?>(null) }
    var internet by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(LinkManager.autoUpgrade) }
    val linkStates by LinkManager.states.collectAsState()
    val log by LinkManager.log.collectAsState()

    fun release(why: String = "user") {
        joinJob?.cancel(); joinJob = null
        benchJob?.cancel(); benchJob = null
        joined?.let { it.release(); LinkManager.appendLog("lid=dbg ev=released reason=$why") }
        joined = null
        status = "Released"
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true // S-W3 holds the link for 10 min
        onDispose { view.keepScreenOn = false; release("screen_closed") }
    }
    BackHandler(onBack = onBack)

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val id = peerId ?: return@rememberLauncherForActivityResult
        val s = Hub.readySession(id)
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (s == null) Hub.toast("Not connected to ${peer?.name}") else s.xfer.send(uris, fast = true)
    }

    fun join() {
        val c = creds ?: run { status = "No pairing key for this peer"; return }
        release("rejoin")
        status = "Joining ${c.ssid}…"
        val timeout = if (longTimeout) 60_000L else 15_000L
        LinkManager.appendLog("lid=dbg peer=${peerId?.take(8)} type=wfd_legacy ev=join_start ms=0 ssid=${c.ssid} timeout=$timeout")
        joinJob = scope.launch {
            try {
                val j = WfdJoiner(ctx).join(c, timeout)
                joined = j
                runs++
                if (host.isBlank()) host = j.gateways.firstOrNull() ?: ""
                status = "Joined in ${j.ms} ms"
                LinkManager.appendLog("lid=dbg ev=joined ms=${j.ms} freq=${j.freq} sta=${j.sta} dlg=${j.dialogShown ?: "unknown"} api=${Build.VERSION.SDK_INT} ip=${j.localAddr} gw=${j.gateways.joinToString(",")} concurrency=${j.staConcurrency} bssid=${j.bssid ?: "-"}")
                j.onLost {
                    LinkManager.appendLog("lid=dbg ev=lost")
                    Hub.main.post { if (joined === j) { joined = null; status = "Network lost" } }
                }
            } catch (e: JoinFailed) {
                status = "Failed: ${e.reason} ${e.detail}"
                LinkManager.appendLog("lid=dbg ev=fail reason=${e.reason} detail=\"${e.detail}\"")
            }
        }
    }

    fun runBench(upload: Boolean) {
        val j = joined ?: return
        val p = port.toIntOrNull() ?: return
        benchJob?.cancel()
        val dir = if (upload) "up" else "down"
        bench = "Running $dir…"
        benchJob = scope.launch {
            try {
                val r = LinkBench.run(j.network, host.trim(), p, upload) { pr ->
                    Hub.main.post { bench = String.format(Locale.US, "%s %.1f MB/s · %d MB · %.1f s", dir, pr.mbps, pr.bytes / 1_000_000, pr.ms / 1000.0) }
                }
                bench = String.format(Locale.US, "%s %.1f MB/s (%.0f Mbit/s) · %d MB in %.1f s", dir, r.mbps, r.mbps * 8, r.bytes / 1_000_000, r.ms / 1000.0)
                LinkManager.appendLog(String.format(Locale.US, "lid=dbg ev=bench dir=%s MBps=%.1f bytes=%d ms=%d freq=%d", dir, r.mbps, r.bytes, r.ms, j.freq))
            } catch (e: Exception) {
                bench = "$dir failed: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    fun checkInternet() {
        internet = "Checking…"
        scope.launch {
            internet = withContext(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                try {
                    // Default network (not the joined one): does the phone keep its internet?
                    val c = URL("http://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection
                    c.connectTimeout = 5_000; c.readTimeout = 5_000
                    val code = c.responseCode
                    c.disconnect()
                    "HTTP $code in ${System.currentTimeMillis() - t0} ms"
                } catch (e: Exception) { "Failed: ${e.javaClass.simpleName}: ${e.message}" }
            }
            LinkManager.appendLog("lid=dbg ev=internet result=\"$internet\"")
        }
    }

    AbScaffold(title = "Wi-Fi Direct tests", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("Peer")
            AbCard {
                if (peers.isEmpty()) InfoRow("No paired computers", "Pair a Windows PC over the LAN first")
                peers.forEachIndexed { i, p ->
                    if (i > 0) RowDivider()
                    InfoRow(p.name, "${p.plat} · ${p.id.take(8)}", leading = {
                        RadioButton(selected = p.id == peerId, onClick = { peerId = p.id; release("peer_changed") })
                    })
                }
                if (creds != null) {
                    RowDivider()
                    InfoRow("SSID ${creds.ssid}", "gen ${creds.gen} · " + if (showPsk) "psk ${creds.psk}" else "passphrase hidden", trailing = {
                        TextButton(onClick = { showPsk = !showPsk }) { Text(if (showPsk) "Hide" else "Show") }
                    })
                }
            }

            SectionHeader("Join (S-W2, S-W3)")
            AbCard {
                InfoRow(status, joined?.let { j ->
                    "freq ${j.freq} MHz · sta ${j.sta} (kept=${j.staKept}) · dialog ${j.dialogShown ?: "unknown"} · ${j.ms} ms\n" +
                        "ip ${j.localAddr} · gw ${j.gateways.joinToString()} · STA+STA ${j.staConcurrency} · bssid ${j.bssid ?: "-"} · runs $runs"
                } ?: "API ${Build.VERSION.SDK_INT} · " + (WfdJoiner.unsupportedReason(ctx)?.let { "unsupported: $it" } ?: "wfd_join supported"))
                RowDivider()
                SwitchRow("60 s timeout", "For the first run, while you approve the system dialog (spec: 15 s)", longTimeout, { longTimeout = it })
                Column(Modifier.padding(Dimens.md), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    PrimaryButton("Join derived network for ${peer?.name ?: "…"}", onClick = { join() }, enabled = creds != null && joinJob?.isActive != true)
                    SecondaryButton("Release", onClick = { release() })
                    SecondaryButton("Check internet (default network)", onClick = { checkInternet() })
                    if (internet.isNotEmpty()) Text(internet, style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionHeader("TCP throughput (S-W4)")
            AbCard {
                Column(Modifier.padding(Dimens.md), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    Text("Peer runs tools/wfd_tcp_peer.py. 10 s per run, sockets bound to the joined network.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                        OutlinedTextField(host, { host = it }, Modifier.weight(2f), label = { Text("PC address") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text("Port") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                        Column(Modifier.weight(1f)) { SecondaryButton("Phone → PC", onClick = { runBench(true) }) }
                        Column(Modifier.weight(1f)) { SecondaryButton("PC → phone", onClick = { runBench(false) }) }
                    }
                    if (joined == null) Text("Join first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (bench.isNotEmpty()) Text(bench, style = MaterialTheme.typography.bodyMedium)
                }
            }

            SectionHeader("AirBridge link (link_* over the LAN session)")
            AbCard {
                val st = peerId?.let { linkStates[it] }
                InfoRow("Link state: ${st?.name ?: "IDLE"}",
                    "Connected: ${if (peerId != null && Hub.readySession(peerId!!) != null) Hub.readySession(peerId!!)!!.link.label else "no"}")
                RowDivider()
                SwitchRow("Upgrade large transfers", "Ask for Wi-Fi Direct for files over 200 MB", auto, { auto = it; LinkManager.autoUpgrade = it })
                Column(Modifier.padding(Dimens.md), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                    SecondaryButton("Request link now", onClick = { peerId?.let { LinkManager.request(it, "user") } })
                    SecondaryButton("Send files fast…", onClick = { pickFiles.launch(arrayOf("*/*")) })
                    SecondaryButton("End link", onClick = { peerId?.let { LinkManager.end(it) } })
                }
            }

            Row(Modifier.fillMaxWidth()) {
                SectionHeader("Log", Modifier.weight(1f))
                TextButton(onClick = { LinkManager.clearLog() }) { Text("Clear") }
            }
            AbCard {
                Text(
                    if (log.isEmpty()) "No AirBridge.Link lines yet." else log.takeLast(120).joinToString("\n"),
                    Modifier.padding(Dimens.md),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Dimens.xl))
        }
    }
}
