package com.shazan.airbridge

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.shazan.airbridge.net.AirWebServer
import com.shazan.airbridge.net.Identity
import com.shazan.airbridge.net.LanHost
import com.shazan.airbridge.net.Saver
import com.shazan.airbridge.net.TransferLocks
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.AbScaffold
import com.shazan.airbridge.ui.theme.AirBridgeTheme
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.EmptyState
import com.shazan.airbridge.ui.theme.InfoRow
import com.shazan.airbridge.ui.theme.PrimaryButton
import com.shazan.airbridge.ui.theme.RowDivider
import com.shazan.airbridge.ui.theme.SecondaryButton
import com.shazan.airbridge.ui.theme.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import com.shazan.airbridge.ui.relativeTime
import com.shazan.airbridge.ui.rememberNow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * AirWeb: swap files with a phone or computer that doesn't have AirBridge. This phone serves a small
 * web page on its Wi-Fi (or hotspot) address; the other device scans the QR code with any camera app,
 * downloads what was shared here and uploads files back from its browser.
 *
 * This screen is only a window onto the session ([AirWebSession], kept alive by [AirWebService]): going Back
 * leaves the server running, and sharing more files later from the gallery or any app joins the same server,
 * the same link and the same pool instead of starting another. Stop it from here or from its notification.
 */
class AirWebActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(AppLanguage.wrap(newBase)) }

    companion object {
        private const val EXTRA_URIS = "com.shazan.airbridge.extra.AIRWEB_URIS"

        /** Opens AirWeb offering [uris] (may be empty: receive only), passing our read grant along. */
        fun intent(ctx: Context, uris: List<Uri>): Intent {
            // Same task as the screen that opens it, so Back goes back there (a new task only without an activity).
            val i = Intent(ctx, AirWebActivity::class.java)
            if (ctx.findActivity() == null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (uris.isNotEmpty()) {
                i.putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                i.clipData = ClipData.newRawUri("files", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return i
        }
    }

    private val model: AirWebModel by lazy { AirWebSession.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        offer(if (savedInstanceState == null) urisFrom(intent) else emptyList())
        setContent {
            AirBridgeTheme {
                AirWebScreen(
                    model,
                    onClose = { finish() }, // leaves the screen; the server keeps running
                    onStop = { AirWebService.stop(this); finish() },
                )
            }
        }
    }

    /** Already open (singleTop): files offered again join the same session. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offer(urisFrom(intent))
    }

    /** Makes sure the one session is running (the service keeps it up after this screen closes) and adds [uris] to it. */
    private fun offer(uris: List<Uri>) {
        AirWebService.start(this, uris)
        model.add(uris)
    }

    override fun onStart() {
        super.onStart()
        model.onForeground()
    }

    override fun onStop() {
        super.onStop()
        model.setVisible(false)
    }

    @Suppress("DEPRECATION")
    private fun urisFrom(i: Intent?): List<Uri> {
        i ?: return emptyList()
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) i.getParcelableArrayListExtra(EXTRA_URIS, Uri::class.java)
        else i.getParcelableArrayListExtra(EXTRA_URIS)
        return list.orEmpty()
    }
}

/** One file offered for download. */
data class OfferedFile(
    val id: Int, val uri: Uri, val name: String, val size: Long, val mime: String?, val downloads: Int = 0,
    val added: Long = System.currentTimeMillis(), val downloadedAt: Long = 0L,
)

/**
 * One upload from a browser: in progress ([done] < [size]) or finished ([saved] set) or failed. A saved one is in the
 * shared pool, downloadable by everyone connected, until [shared] is turned off here.
 */
data class ReceivedFile(
    val id: Long, val name: String, val size: Long, val done: Long = 0,
    val saved: Boolean = false, val failed: Boolean = false, val uri: Uri? = null, val mime: String? = null,
    /** When it started, then when it finished (epoch ms). */
    val at: Long = System.currentTimeMillis(),
    /** Which browser sent it (its device label) and that page's client id. */
    val by: String? = null, val owner: String? = null,
    val shared: Boolean = true, val downloads: Int = 0,
)

/**
 * The one AirWeb session of this app: its server, link, shared files and uploads. It lives as long as
 * [AirWebService] does (not as long as a screen), so Back doesn't end it and a second share joins it.
 */
object AirWebSession {
    @Volatile private var model: AirWebModel? = null

    /** The session, started if there is none yet. Always the same one until it is [end]ed. */
    @Synchronized fun get(ctx: Context): AirWebModel = model ?: AirWebModel(ctx.applicationContext).also { model = it }

    /** The session if one exists (never creates one). */
    fun peek(): AirWebModel? = model

    /** Stops the server and forgets the session; the next [get] makes a fresh one with a new link. */
    @Synchronized fun end() { model?.shutdown(); model = null }
}

/**
 * Owns the AirWeb server. Same token and, when free, the same port after a restart, so a page that is
 * already open keeps working; [newLink] gives it a new token on purpose, and open pages are told.
 */
class AirWebModel(private val ctx: Context) {
    private companion object {
        const val TAG = "AirBridge.AirWeb"
        /** With nobody connected, nothing moving and the screen closed for this long, the session ends. */
        const val IDLE_STOP_MS = 2 * 60 * 60 * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _token = MutableStateFlow(AirWebServer.newToken())
    /** The session token of the current link (changes only with [newLink]). */
    val token: StateFlow<String> = _token
    private val nextId = AtomicInteger(1)
    private var lastPort = 0
    private var watcher: Job? = null
    private var starting = false
    @Volatile private var visible = false
    private var locked = false

    private val _offered = MutableStateFlow<List<OfferedFile>>(emptyList())
    val offered: StateFlow<List<OfferedFile>> = _offered
    private val _received = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val received: StateFlow<List<ReceivedFile>> = _received
    private val _peers = MutableStateFlow<List<AirWebServer.Peer>>(emptyList())
    /** The browsers connected right now. */
    val peers: StateFlow<List<AirWebServer.Peer>> = _peers
    private val _addresses = MutableStateFlow<List<String>>(emptyList())
    val addresses: StateFlow<List<String>> = _addresses
    private val _address = MutableStateFlow<String?>(null)
    /** Null when this phone has no Wi-Fi or hotspot address. */
    val address: StateFlow<String?> = _address
    private val _port = MutableStateFlow(0)
    val port: StateFlow<Int> = _port
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed

    fun url(addr: String, port: Int) = AirWebServer.url(addr, port, _token.value)

    private val taken: MutableSet<String> by lazy { Saver.existingNames(ctx).toMutableSet() }

    private val host = object : AirWebServer.Host {
        private val page by lazy { ctx.assets.open("airweb.html").use { String(it.readBytes(), Charsets.UTF_8) } }

        override fun deviceName() = Identity.name.ifBlank { ctx.getString(R.string.aw_this_phone) }
        override fun page() = page
        /** The shared pool: what this phone added plus what browsers uploaded (saved and still shared). */
        override fun offered() = _offered.value.map { AirWebServer.WebFile(it.id, it.name, it.size, it.mime, it.added) } +
            _received.value.filter { it.saved && it.shared && it.uri != null }.map {
                AirWebServer.WebFile(AirWebServer.UPLOAD_ID_BASE + it.id.toInt(), it.name, it.size, it.mime, it.at, it.by, it.owner)
            }

        override fun open(file: AirWebServer.WebFile): InputStream? {
            val uri = if (file.id >= AirWebServer.UPLOAD_ID_BASE) {
                _received.value.firstOrNull { it.id.toInt() == file.id - AirWebServer.UPLOAD_ID_BASE && it.shared }?.uri
            } else _offered.value.firstOrNull { it.id == file.id }?.uri
            uri ?: return null
            return if (uri.scheme == ContentResolver.SCHEME_FILE) uri.path?.let { File(it).inputStream() }
            else ctx.contentResolver.openInputStream(uri)
        }

        override fun freeSpace(): Long = try {
            @Suppress("DEPRECATION")
            val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Environment.getExternalStorageDirectory()
            else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            // Keep a margin so a full upload never leaves the phone with no space at all.
            maxOf(0L, StatFs(dir.path).availableBytes - 64L * 1024 * 1024)
        } catch (_: Exception) { -1 }

        override fun qr(text: String): Array<BooleanArray>? = try {
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(
                text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 0, com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M),
            )
            Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
        } catch (_: Exception) { null }

        override fun createUpload(rawName: String, mime: String?, size: Long): AirWebServer.UploadTarget {
            val name = rawName.ifBlank { "file" }
            val t = synchronized(taken) { Saver.create(ctx, name, mime, taken) }
            return Upload(t, mime)
        }

        override fun onUploadStarted(id: Long, name: String, size: Long, by: AirWebServer.Peer?) {
            _received.update { it + ReceivedFile(id, Saver.sanitize(name.ifBlank { "file" }), size, by = by?.label, owner = by?.id) }
        }

        override fun onPeersChanged(peers: List<AirWebServer.Peer>) { _peers.value = peers }

        override fun addresses() = _addresses.value

        override fun onUploadProgress(id: Long, done: Long, total: Long) {
            _received.update { l -> l.map { if (it.id == id) it.copy(done = done) else it } }
        }

        override fun onUploadFinished(id: Long, target: AirWebServer.UploadTarget?, savedName: String?) {
            val up = target as? Upload
            _received.update { l ->
                if (up == null || savedName == null) l.map { if (it.id == id) it.copy(failed = true, at = System.currentTimeMillis()) else it }
                else l.map { if (it.id == id) it.copy(name = savedName, done = it.size, saved = true, uri = up.t.uri, mime = up.mime, at = System.currentTimeMillis()) else it }
            }
        }

        override fun onDownloaded(file: AirWebServer.WebFile) {
            if (file.id >= AirWebServer.UPLOAD_ID_BASE) {
                _received.update { l -> l.map { if (it.id.toInt() == file.id - AirWebServer.UPLOAD_ID_BASE) it.copy(downloads = it.downloads + 1) else it } }
            } else {
                _offered.update { l -> l.map { if (it.id == file.id) it.copy(downloads = it.downloads + 1, downloadedAt = System.currentTimeMillis()) else it } }
            }
        }
    }

    private inner class Upload(val t: Saver.Target, val mime: String?) : AirWebServer.UploadTarget {
        override val out: OutputStream get() = t.out
        override fun commit(): String { t.out.close(); return Saver.publish(ctx, t) }
        override fun discard() = Saver.discard(ctx, t)
    }

    private val server = AirWebServer(_token.value, host)

    /** A new link on purpose (the old one may have been seen by the wrong person): pages open on the old one are told to move. */
    fun newLink() { _token.value = server.rotate() }

    /** The short code and what is left of it, for the host screen's "Need a short link?". */
    fun shortLink(): com.shazan.airbridge.net.AirWebShortLink.Issued = server.issueShort()

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val described = withContext(Dispatchers.IO) { uris.mapNotNull { describe(it) } }
            if (described.size < uris.size) toast(ctx.getString(if (uris.size == 1) R.string.aw_cant_read_one else R.string.aw_cant_read_some))
            _offered.update { l -> l + described.filter { d -> l.none { it.uri == d.uri } } }
        }
    }

    fun remove(id: Int) = _offered.update { l -> l.filterNot { it.id == id } }

    /** Stops sharing an uploaded file with the other browsers (it stays in Downloads on this phone). */
    fun stopSharing(id: Long) = _received.update { l -> l.map { if (it.id == id) it.copy(shared = false) else it } }

    private fun describe(uri: Uri): OfferedFile? {
        val cr = ctx.contentResolver
        var name: String? = null
        var size = -1L
        try {
            if (uri.scheme == ContentResolver.SCHEME_FILE) {
                val f = File(uri.path ?: return null)
                if (!f.canRead()) return null
                name = f.name; size = f.length()
            } else {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        if (!c.isNull(0)) name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
                // Proves we can read it now (the grant is there) and finds the size when the query had none.
                val afd = try { cr.openAssetFileDescriptor(uri, "r") } catch (_: Exception) { null }
                if (afd != null) afd.use { if (size < 0) size = it.length }
                else (cr.openInputStream(uri) ?: return null).close() // streaming providers: size stays unknown
            }
        } catch (e: Exception) {
            Log.w(TAG, "describe $uri: ${e.message}")
            return null
        }
        val finalName = Saver.sanitize(name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file")
        val mime = if (uri.scheme == ContentResolver.SCHEME_FILE) null else cr.getType(uri)
        return OfferedFile(nextId.getAndIncrement(), uri, finalName, size, mime)
    }

    /** Re-reads this phone's Wi-Fi / hotspot addresses; keeps the one in use if it is still there. */
    fun refreshAddresses() {
        val list = LanHost.localAddresses()
        _addresses.value = list
        val cur = _address.value
        _address.value = if (cur != null && cur in list) cur else list.firstOrNull()
    }

    /** Several addresses (Wi-Fi and hotspot): show the next one. */
    fun nextAddress() {
        val list = _addresses.value
        if (list.size < 2) return
        val i = list.indexOf(_address.value)
        _address.value = list[(i + 1).mod(list.size)]
    }

    /** The screen is on show (or not): the idle clock only runs while it is not. */
    fun setVisible(v: Boolean) { visible = v }

    fun onForeground() {
        visible = true
        ensureStarted()
        refreshAddresses()
    }

    fun ensureStarted() {
        // One server at a time: the service and the screen both ask when they come up.
        if (server.isRunning || starting) return
        starting = true
        scope.launch {
            val p = withContext(Dispatchers.IO) {
                try { server.start(lastPort) } catch (e: IOException) { Log.w(TAG, "start: ${e.message}"); 0 }
            }
            starting = false
            _failed.value = p == 0
            _port.value = p
            if (p != 0) {
                lastPort = p
                // Debug builds only (the link carries the session secret): lets the test rigs find the server.
                if (BuildConfig.DEBUG) Log.d(TAG, "airweb link http://127.0.0.1:$p/?t=${_token.value}")
                if (!locked) { TransferLocks.acquire(ctx); locked = true } // Wi-Fi out of power save while AirWeb is up
                watch()
            }
        }
    }

    /** While the server runs: keeps the addresses fresh (Wi-Fi/hotspot change) and ends the session after a long idle. */
    private fun watch() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            var lastBusy = System.currentTimeMillis()
            while (true) {
                delay(15_000)
                refreshAddresses()
                val now = System.currentTimeMillis()
                if (visible || _peers.value.isNotEmpty() || server.activeTransfers > 0) lastBusy = now
                else if (now - lastBusy > IDLE_STOP_MS) { AirWebService.stop(ctx); return@launch }
            }
        }
    }

    private fun stopServer() {
        watcher?.cancel(); watcher = null
        server.stop()
        _peers.value = emptyList()
        _port.value = 0
        if (locked) { TransferLocks.release(); locked = false }
    }

    fun shutdown() { stopServer(); scope.cancel() }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

// ---------------------------------------------------------------- screen

@Composable
private fun AirWebScreen(model: AirWebModel, onClose: () -> Unit, onStop: () -> Unit) {
    val ctx = LocalContext.current
    val offered by model.offered.collectAsState()
    val received by model.received.collectAsState()
    val peers by model.peers.collectAsState()
    val address by model.address.collectAsState()
    val addresses by model.addresses.collectAsState()
    val port by model.port.collectAsState()
    val failed by model.failed.collectAsState()
    val token by model.token.collectAsState()
    val myName by Identity.nameFlow.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> model.add(uris) }

    // Wi-Fi and hotspot come and go (hotspot changes send no network callback): look again every few seconds while visible.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { model.refreshAddresses(); delay(3_000) }
        }
    }

    AbScaffold(
        title = stringResource(R.string.airweb),
        onBack = onClose,
        bottomBar = {
            Row(Modifier.navigationBarsPadding().padding(Dimens.md), horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                SecondaryButton(stringResource(R.string.aw_add_files), icon = Icons.Outlined.Add, modifier = Modifier.weight(1f), onClick = { pick.launch(arrayOf("*/*")) })
                PrimaryButton(stringResource(R.string.aw_stop), modifier = Modifier.weight(1f), onClick = onStop)
            }
        },
    ) {
        val now = rememberNow()
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            item(key = "qr") {
                val addr = address
                when {
                    addr == null -> AbCard {
                        EmptyState(
                            Icons.Outlined.WifiOff,
                            stringResource(R.string.aw_join_wifi),
                            stringResource(R.string.aw_join_wifi_sub),
                            action = {
                                SecondaryButton(stringResource(R.string.aw_wifi_settings), onClick = {
                                    try { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) {}
                                })
                            },
                        )
                    }
                    failed -> AbCard {
                        EmptyState(Icons.Outlined.WifiOff, stringResource(R.string.aw_start_failed), stringResource(R.string.aw_start_failed_sub),
                            action = { SecondaryButton(stringResource(R.string.aw_try_again), onClick = { model.ensureStarted() }) })
                    }
                    port == 0 -> AbCard { Spacer(Modifier.height(Dimens.xl)) }
                    else -> key(token) { QrCard(model.url(addr, port), "http://$addr:$port", myName, model, canSwitch = addresses.size > 1, onSwitch = { model.nextAddress() }) }
                }
            }

            // Who is here: everyone connected shares one pool (this phone's files and everything browsers upload).
            item(key = "peers") {
                AbCard {
                    val names = peers.map { it.label }
                    InfoRow(
                        title = if (names.isEmpty()) stringResource(R.string.aw_connected_none)
                        else pluralStringResource(R.plurals.aw_connected, names.size, names.size),
                        subtitle = if (names.isEmpty()) stringResource(R.string.aw_connected_none_sub)
                        else names.take(4).joinToString(" · ") + if (names.size > 4) " · +${names.size - 4}" else "",
                    )
                }
            }

            item(key = "offered-h") { SectionHeader(stringResource(R.string.aw_files_to_send)) }
            if (offered.isEmpty()) item(key = "offered-empty") {
                AbCard { InfoRow(title = stringResource(R.string.aw_nothing_added), subtitle = stringResource(R.string.aw_nothing_added_sub)) }
            } else item(key = "offered") {
                AbCard {
                    offered.forEachIndexed { i, f ->
                        if (i > 0) RowDivider()
                        val parts = listOfNotNull(
                            formatSize(f.size),
                            if (f.downloads > 0) stringResource(R.string.aw_downloaded_ago, relativeTime(f.downloadedAt, now)) else relativeTime(f.added, now),
                        )
                        InfoRow(title = f.name, subtitle = parts.joinToString(" · ").ifEmpty { null }) {
                            IconButton(onClick = { model.remove(f.id) }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.aw_remove, f.name)) }
                        }
                    }
                }
            }

            item(key = "received-h") { SectionHeader(stringResource(R.string.aw_received)) }
            if (received.isEmpty()) item(key = "received-empty") {
                AbCard { InfoRow(title = stringResource(R.string.aw_nothing_yet), subtitle = stringResource(R.string.aw_nothing_yet_sub, "Downloads/" + Saver.SUBDIR)) }
            }
            items(received.asReversed(), key = { "r" + it.id }) { r ->
                AbCard(onClick = if (r.saved) ({ openReceived(ctx, r) }) else null) {
                    val sub = when {
                        r.saved -> listOfNotNull(
                            formatSize(r.size), r.by?.let { stringResource(R.string.aw_from, it) }, relativeTime(r.at, now),
                            stringResource(if (r.shared) R.string.aw_tap_to_open else R.string.aw_not_shared),
                        ).joinToString(" · ")
                        r.failed -> stringResource(R.string.aw_didnt_finish) + " · " + relativeTime(r.at, now)
                        else -> stringResource(R.string.aw_receiving_pct, if (r.size > 0) (r.done * 100 / r.size).toInt() else 0)
                    }
                    InfoRow(title = r.name, subtitle = sub) {
                        // Uploaded files are shared with everyone connected until you stop sharing them here.
                        if (r.saved && r.shared) IconButton(onClick = { model.stopSharing(r.id) }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.aw_stop_sharing, r.name))
                        }
                    }
                    if (!r.saved && !r.failed) {
                        LinearProgressIndicator(
                            progress = { if (r.size > 0) (r.done.toFloat() / r.size).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth().padding(start = Dimens.md, end = Dimens.md, bottom = Dimens.md),
                            color = MaterialTheme.colorScheme.onSurface,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(Dimens.md)) }
        }
    }
}

@Composable
private fun QrCard(url: String, base: String, name: String, model: AirWebModel, canSwitch: Boolean, onSwitch: () -> Unit) {
    val ctx = LocalContext.current
    var confirmNew by remember { mutableStateOf(false) }
    if (confirmNew) AlertDialog(
        onDismissRequest = { confirmNew = false },
        title = { Text(stringResource(R.string.aw_new_link_q)) },
        text = { Text(stringResource(R.string.aw_new_link_body)) },
        confirmButton = { TextButton(onClick = { confirmNew = false; model.newLink() }) { Text(stringResource(R.string.aw_new_link)) } },
        dismissButton = { TextButton(onClick = { confirmNew = false }) { Text(stringResource(R.string.cancel)) } },
    )
    AbCard {
        Column(
            Modifier.fillMaxWidth().padding(Dimens.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            QrCode(url, Modifier.fillMaxWidth().widthIn(max = 320.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f, fill = false)) {
                    Text(url, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                IconButton(onClick = { copyLink(ctx, url) }) { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.aw_copy_link)) }
            }
            ShortLink(base, model)
            Text(
                stringResource(R.string.aw_instructions),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.aw_keeps_running),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Text(name, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                if (canSwitch) TextButton(onClick = onSwitch) { Text(stringResource(R.string.aw_another_address)) }
                TextButton(onClick = { confirmNew = true }) { Text(stringResource(R.string.aw_new_link)) }
            }
        }
    }
}

/** "Need a short link?": a six-character link for a computer where the code can't be scanned. Only made when asked, lasts 10 minutes. */
@Composable
private fun ShortLink(base: String, model: AirWebModel) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var until by remember { mutableStateOf(0L) }
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    fun ask() { val s = model.shortLink(); code = s.code; until = System.currentTimeMillis() + s.secondsLeft * 1000 }
    LaunchedEffect(open, code) {
        while (open) { tick = System.currentTimeMillis(); delay(1000) }
    }
    val left = ((until - tick) / 1000).coerceAtLeast(0)
    val expired = code != null && left <= 0
    val shown = code?.let { "$base/j/$it".removePrefix("http://") }
    TextButton(onClick = { open = !open; if (open && (code == null || expired)) ask() }) {
        Text(stringResource(R.string.aw_need_short))
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
    }
    if (open && shown != null) {
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium).padding(Dimens.md),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.sm),
        ) {
            SelectionContainer {
                Text(shown, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
            Text(
                if (expired) stringResource(R.string.aw_short_expired)
                else stringResource(R.string.aw_short_left, "%d:%02d".format(left / 60, left % 60)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            if (expired) SecondaryButton(stringResource(R.string.aw_short_new), onClick = { ask() })
            else SecondaryButton(stringResource(R.string.aw_short_copy), icon = Icons.Outlined.ContentCopy, onClick = { copyLink(ctx, "http://$shown") })
            Text(stringResource(R.string.aw_short_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

private fun copyLink(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("AirWeb link", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(ctx, ctx.getString(R.string.aw_link_copied), Toast.LENGTH_SHORT).show()
}

/** Black on white with a quiet zone, as large as the card allows (scanners need the contrast). */
@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val bmp = remember(text) {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(
            text, com.google.zxing.BarcodeFormat.QR_CODE, 720, 720,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 2),
        )
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Box(modifier.aspectRatio(1f).background(androidx.compose.ui.graphics.Color.White, MaterialTheme.shapes.medium).padding(Dimens.sm)) {
        Image(bmp, contentDescription = stringResource(R.string.aw_qr_desc), modifier = Modifier.fillMaxSize())
    }
}

private fun openReceived(ctx: Context, r: ReceivedFile) {
    val uri = r.uri
    val view = if (uri != null) Intent(Intent.ACTION_VIEW).setDataAndType(uri, r.mime ?: ctx.contentResolver.getType(uri) ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) else null
    try {
        ctx.startActivity(view ?: Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
    } catch (_: Exception) {
        try { ctx.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        catch (_: Exception) { Toast.makeText(ctx, ctx.getString(R.string.aw_no_app), Toast.LENGTH_SHORT).show() }
    }
}

private fun formatSize(n: Long): String? {
    if (n < 0) return null
    if (n < 1024) return "$n B"
    var v = n / 1024.0
    val units = listOf("KB", "MB", "GB", "TB")
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (v < 10) String.format(Locale.US, "%.1f %s", v, units[i]) else String.format(Locale.US, "%.0f %s", v, units[i])
}

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
