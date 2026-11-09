package com.shazan.airbridge.net

import android.Manifest
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shazan.airbridge.AirBridgeService
import com.shazan.airbridge.R
import com.shazan.airbridge.net.link.LinkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-session file transfer (§6): streaming 512 KiB chunks, receiver-driven window, writes on a
 * per-transfer writer thread fed by a bounded queue so the network reader never touches disk.
 */
class FileTransfer(private val s: Session) {
    companion object {
        private const val TAG = "AirBridge.Xfer"
        private val senders = Executors.newCachedThreadPool { r -> Thread(r, "airbridge-xfer-send").apply { isDaemon = true } }
        private val notifIds = AtomicInteger(5000)
        private const val QUEUE_HIGH_WATER = 2L * 1024 * 1024
        private const val STALL_MS = 30_000L
        private const val MAX_WINDOW = 64L * 1024 * 1024
        private const val UI_INTERVAL_MS = 400L
        private val DONE = Any()
        private val CANCEL = Any()
    }

    private class XferError(msg: String) : Exception(msg)

    private val ctx: Context get() = Hub.app
    private val nextId = AtomicInteger(1)
    private val outgoing = ConcurrentHashMap<Int, Outgoing>()
    private val incoming = ConcurrentHashMap<Int, Incoming>()
    private val peerName: String get() = s.peerName.ifBlank { Hub.app.getString(com.shazan.airbridge.R.string.xf_device) }

    // ================= control =================

    fun onControl(t: String, m: JSONObject) {
        val id = m.optInt("id", -1)
        if (id < 0) return
        when (t) {
            "xfer_offer" -> onOffer(id, m)
            "xfer_done" -> incoming[id]?.enqueue(DONE)
            "xfer_accept" -> outgoing[id]?.update {
                accepted = true
                window = m.optLong("window", Protocol.RECV_WINDOW).coerceIn(1L, MAX_WINDOW)
            }
            "xfer_reject" -> outgoing[id]?.update { rejected = m.str("reason") ?: "user" }
            "xfer_ack" -> outgoing[id]?.update {
                val r = m.optLong("received", 0)
                if (r > acked) { acked = r; lastProgressAt = System.currentTimeMillis() }
            }
            "xfer_complete" -> outgoing[id]?.update { complete = m }
            // `dir` is from the canceller's view: its "out" is our incoming. Missing → match either.
            "xfer_cancel" -> {
                val reason = m.str("reason") ?: "user"
                val dir = m.str("dir")
                if (dir != "out") outgoing[id]?.update { cancelled = reason }
                if (dir != "in") incoming[id]?.cancel(reason, tellPeer = false)
            }
        }
    }

    fun onChunk(frame: ByteArray) {
        val c = Protocol.parseChunk(frame) ?: return
        val inc = incoming[c.xferId] ?: return // unknown / cancelled: ignore
        inc.onChunk(c)
    }

    fun onSessionClosed() {
        outgoing.values.forEach { it.update { if (cancelled == null) cancelled = "disconnect" } }
        incoming.values.forEach { it.cancel("disconnect", tellPeer = false) }
    }

    /** User pressed Cancel on a notification. */
    fun cancelLocal(id: Int, isIncoming: Boolean) {
        if (isIncoming) incoming[id]?.cancel("user", tellPeer = true)
        else outgoing[id]?.let { o ->
            o.update { cancelled = "user" }
            s.send(cancelMsg(id, "out", "user"))
        }
    }

    private fun cancelMsg(id: Int, dir: String, reason: String) =
        Protocol.withReason("xfer_cancel", reason).put("id", id).put("dir", dir)

    private fun cancelAction(id: Int, isIncoming: Boolean): NotificationCompat.Action {
        val i = Intent(ctx, AirBridgeService::class.java).setAction(AirBridgeService.ACTION_CANCEL_XFER)
            .putExtra("peer", s.peerId).putExtra("id", id).putExtra("incoming", isIncoming)
        val pi = PendingIntent.getService(ctx, id * 2 + (if (isIncoming) 1 else 0) + (s.peerId.hashCode() and 0xFFFF) * 4096, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action(0, Hub.app.getString(com.shazan.airbridge.R.string.cancel), pi)
    }

    // ================= sending =================

    /** [fast] = the user asked for "Send fast": upgrade to a Wi-Fi Direct link first if the peer can host one. */
    fun send(uris: List<Uri>, fast: Boolean = false) {
        if (uris.isEmpty()) return
        senders.execute { prepareAndSend(uris, fast) }
    }

    /** True while any transfer runs on this session (keeps a link from going idle). */
    fun isBusy(): Boolean = outgoing.isNotEmpty() || incoming.isNotEmpty()

    /**
     * Describe the files, then (§10.1) hold the `xfer_offer` until an upgraded link session is ready
     * if [LinkPolicy] says so, and send on whichever session [LinkManager.prepareSend] returns.
     */
    private fun prepareAndSend(uris: List<Uri>, fast: Boolean) {
        val srcs = try { uris.map { describe(it) } } catch (e: Exception) {
            Log.w(TAG, "describe failed: ${e.message}")
            Hub.putTransfer(TransferUi("${s.peerId}:out:prep${System.nanoTime()}", false, peerName, "", 0, uris.size, 0, 0, 0,
                result = Hub.app.getString(com.shazan.airbridge.R.string.xf_failed_detail, Hub.app.getString(com.shazan.airbridge.R.string.xf_cant_read, e.message ?: ""))))
            return
        }
        val total = srcs.sumOf { it.size }
        val holdKey = "${s.peerId}:out:link"
        val target = try {
            LinkManager.prepareSend(s, total, fast) {
                Hub.putTransfer(TransferUi(holdKey, false, peerName, Hub.app.getString(com.shazan.airbridge.R.string.xf_wfd_setup), 0, srcs.size, 0, total, 0))
            }
        } catch (e: Exception) {
            Log.w(TAG, "link upgrade", e); s
        } finally {
            Hub.removeTransfer(holdKey)
        }
        target.xfer.runSend(srcs)
    }

    private class Src(val uri: Uri, val tmp: File?, val name: String, val size: Long, val mime: String?)

    private class Outgoing(val id: Int) {
        val lock = Object()
        var accepted = false
        var rejected: String? = null
        var window = Protocol.RECV_WINDOW
        var acked = 0L
        var lastProgressAt = System.currentTimeMillis()
        var complete: JSONObject? = null
        var cancelled: String? = null
        fun update(block: Outgoing.() -> Unit) = synchronized(lock) { block(); lock.notifyAll() }
    }

    private fun runSend(srcs: List<Src>) {
        val notifId = notifIds.incrementAndGet()
        val id = nextId.getAndIncrement()
        val key = "${s.peerId}:out:$id"
        val o = Outgoing(id)
        outgoing[id] = o
        TransferLocks.acquire(ctx)
        try {
            val total = srcs.sumOf { it.size }
            val files = JSONArray()
            srcs.forEachIndexed { i, f ->
                files.put(JSONObject().put("i", i).put("name", f.name).put("size", f.size).apply { f.mime?.let { put("mime", it) } })
            }
            if (!s.send(Protocol.msg("xfer_offer").put("id", id).put("files", files).put("total", total))) throw XferError("not connected")
            val started = System.currentTimeMillis()
            var lastUi = 0L
            fun ui(filesDone: Int, name: String, force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastUi < UI_INTERVAL_MS) return
                lastUi = now
                val acked = synchronized(o.lock) { o.acked }
                val bps = if (now > started) acked * 1000 / (now - started) else 0
                Hub.putTransfer(TransferUi(key, false, peerName, name, filesDone, srcs.size, acked, total, bps))
                progressNotification(notifId, false, name, filesDone, srcs.size, acked, total, bps, cancelAction(id, false))
            }
            ui(0, srcs.firstOrNull()?.name ?: "", force = true)

            synchronized(o.lock) {
                val deadline = System.currentTimeMillis() + 30_000
                while (!o.accepted && o.rejected == null) {
                    checkAlive(o)
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) throw XferError(Hub.app.getString(com.shazan.airbridge.R.string.xf_no_answer, peerName))
                    o.lock.wait(left.coerceAtMost(500))
                }
                o.rejected?.let { throw XferError(if (it == "disk") Hub.app.getString(com.shazan.airbridge.R.string.xf_no_space, peerName) else Hub.app.getString(com.shazan.airbridge.R.string.xf_rejected, it)) }
                o.lastProgressAt = System.currentTimeMillis()
            }

            val window = synchronized(o.lock) { o.window }
            val chunkSize = minOf(Protocol.CHUNK_SIZE.toLong(), window).toInt() // never exceed a tiny window
            val buf = ByteArray(chunkSize)
            var sent = 0L
            srcs.forEachIndexed { i, f ->
                openSrc(f).use { input ->
                    var off = 0L
                    while (off < f.size) {
                        val want = minOf(chunkSize.toLong(), f.size - off).toInt()
                        val n = readFully(input, buf, want)
                        if (n < want) throw XferError(Hub.app.getString(com.shazan.airbridge.R.string.xf_changed, f.name))
                        synchronized(o.lock) {
                            while (true) {
                                checkAlive(o)
                                val windowOk = sent + n - o.acked <= o.window
                                if (windowOk && s.link.queuedBytes() < QUEUE_HIGH_WATER) break
                                if (System.currentTimeMillis() - o.lastProgressAt > STALL_MS) throw XferError(Hub.app.getString(com.shazan.airbridge.R.string.xf_stopped, peerName))
                                o.lock.wait(if (windowOk) 10 else 200)
                            }
                        }
                        if (!s.sendBinary(Protocol.chunkFrame(id, i, off, buf, n))) throw XferError("connection lost")
                        off += n
                        sent += n
                        ui(i, f.name)
                    }
                }
            }
            if (!s.send(Protocol.msg("xfer_done").put("id", id))) throw XferError("connection lost")
            val complete = synchronized(o.lock) {
                while (o.complete == null) {
                    checkAlive(o)
                    if (System.currentTimeMillis() - o.lastProgressAt > 60_000) throw XferError(Hub.app.getString(com.shazan.airbridge.R.string.xf_no_confirm, peerName))
                    o.lock.wait(500)
                }
                o.complete!!
            }
            if (!complete.optBoolean("ok", true)) throw XferError(Hub.app.getString(com.shazan.airbridge.R.string.xf_cant_save, peerName))
            val label = if (srcs.size == 1) srcs[0].name else Hub.app.resources.getQuantityString(com.shazan.airbridge.R.plurals.sh_files, srcs.size, srcs.size)
            Hub.putTransfer(TransferUi(key, false, peerName, label, srcs.size, srcs.size, total, total, 0, result = Hub.app.getString(com.shazan.airbridge.R.string.xfer_sent)))
            resultNotification(notifId, Hub.app.getString(com.shazan.airbridge.R.string.sh_sent_to, peerName), label, null)
        } catch (e: Exception) {
            val why = when (e) {
                is XferError -> e.message ?: Hub.app.getString(com.shazan.airbridge.R.string.xf_failed_word)
                is IOException, is SecurityException -> Hub.app.getString(com.shazan.airbridge.R.string.xf_cant_read, e.message ?: "")
                else -> { Log.e(TAG, "send failed", e); Hub.app.getString(com.shazan.airbridge.R.string.xf_failed_word) }
            }
            Log.w(TAG, "send $id to $peerName failed: $why")
            val cancelledBy = synchronized(o.lock) { o.cancelled }
            if (cancelledBy == null && !s.isClosed) s.send(cancelMsg(id, "out", "error"))
            val text = if (cancelledBy == "user") Hub.app.getString(com.shazan.airbridge.R.string.xfer_cancelled) else Hub.app.getString(com.shazan.airbridge.R.string.xf_failed_detail, why)
            Hub.putTransfer(TransferUi(key, false, peerName, srcs.firstOrNull()?.name ?: "", 0, srcs.size, 0, 0, 0, result = text))
            resultNotification(notifId, Hub.app.getString(com.shazan.airbridge.R.string.xf_send_failed, peerName), text, null)
        } finally {
            outgoing.remove(id)
            TransferLocks.release()
            srcs.forEach { it.tmp?.delete() }
        }
    }

    private fun checkAlive(o: Outgoing) {
        o.cancelled?.let {
            throw XferError(when (it) { "user" -> "cancelled"; "error" -> "$peerName couldn't save the files"; else -> "cancelled ($it)" })
        }
        if (s.isClosed) throw XferError(Hub.app.getString(com.shazan.airbridge.R.string.st_disconnected))
    }

    private fun describe(uri: Uri): Src {
        val cr = ctx.contentResolver
        var name: String? = null
        var size = -1L
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { File(it) }?.let { name = it.name; size = it.length() }
        } else try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) { Log.d(TAG, "query $uri: ${e.message}") }
        if (size < 0) size = try { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1 } catch (_: Exception) { -1 }
        val finalName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mime = cr.getType(uri)
        if (size >= 0) return Src(uri, null, finalName, size, mime)
        // Unknown length (some providers stream): spool to cache so the offer has an exact size.
        val tmp = File.createTempFile("send", ".tmp", ctx.cacheDir)
        (cr.openInputStream(uri) ?: throw IOException("cannot open")).use { i -> FileOutputStream(tmp).use { i.copyTo(it) } }
        return Src(uri, tmp, finalName, tmp.length(), mime)
    }

    private fun openSrc(f: Src): InputStream =
        f.tmp?.inputStream() ?: ctx.contentResolver.openInputStream(f.uri) ?: throw IOException("cannot open ${f.name}")

    private fun readFully(input: InputStream, buf: ByteArray, want: Int): Int {
        var got = 0
        while (got < want) {
            val r = input.read(buf, got, want - got)
            if (r < 0) break
            got += r
        }
        return got
    }

    // ================= receiving =================

    private class Meta(val name: String, val size: Long, val mime: String?)

    private fun onOffer(id: Int, m: JSONObject) {
        if (incoming.containsKey(id)) {
            s.send(Protocol.withReason("xfer_reject", "busy").put("id", id)); return
        }
        val metas = ArrayList<Meta>()
        val arr = m.optJSONArray("files")
        for (k in 0 until (arr?.length() ?: 0)) {
            val f = arr?.optJSONObject(k)
            val size = f?.optLong("size", -1) ?: -1
            if (f == null || size < 0) { metas.clear(); break }
            metas.add(Meta(f.str("name") ?: "file", size, f.str("mime")))
        }
        if (metas.isEmpty()) {
            s.send(Protocol.withReason("xfer_reject", "user").put("id", id)); return
        }
        val total = metas.sumOf { it.size }
        val free = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
        if (total > free - 64L * 1024 * 1024) {
            s.send(Protocol.withReason("xfer_reject", "disk").put("id", id))
            Hub.toast(Hub.app.getString(com.shazan.airbridge.R.string.xf_no_space_in, peerName))
            return
        }
        val inc = Incoming(id, metas, total)
        incoming[id] = inc
        TransferLocks.acquire(ctx)
        s.send(Protocol.msg("xfer_accept").put("id", id).put("window", Protocol.RECV_WINDOW))
        Thread(inc, "airbridge-xfer-write-$id").apply { isDaemon = true }.start()
    }

    private inner class Incoming(val id: Int, val files: List<Meta>, val total: Long) : Runnable {
        // Bounded by bytes, never blocking the network reader: acks are sent after writing, so a sender
        // that respects the window can never have more than RECV_WINDOW bytes queued here.
        private val queue = LinkedBlockingQueue<Any>()
        private val queuedBytes = AtomicLong()
        @Volatile var cancelled: String? = null
        private val key = "${s.peerId}:in:$id"
        private val notifId = notifIds.incrementAndGet()
        // reader-thread validation state
        private var rxIndex = 0
        private var rxOffset = 0L
        // writer-thread state
        private var cur = 0
        private var curWritten = 0L
        private var written = 0L
        private var lastAck = 0L
        private var target: Saver.Target? = null
        // Names already in the destination folder, read once per transfer (not once per file) and kept current.
        private val taken: MutableSet<String> by lazy { Saver.existingNames(ctx).toMutableSet() }
        private val saved = ArrayList<String>()
        private val started = System.currentTimeMillis()
        private var lastUi = 0L

        fun enqueue(item: Any) {
            if (cancelled != null) return
            if (item is Protocol.Chunk && queuedBytes.addAndGet(item.len.toLong()) > Protocol.RECV_WINDOW + Protocol.CHUNK_SIZE) {
                Log.w(TAG, "sender exceeded the window")
                cancel("error", tellPeer = true)
                return
            }
            queue.offer(item)
        }

        fun onChunk(c: Protocol.Chunk) {
            if (cancelled != null) return
            // Validate on the reader thread: in-order, in-bounds (§6.2).
            while (rxIndex < files.size && rxOffset == files[rxIndex].size) { rxIndex++; rxOffset = 0 }
            if (rxIndex >= files.size || c.fileIndex != rxIndex || c.offset != rxOffset || c.offset + c.len > files[rxIndex].size) {
                Log.w(TAG, "bad chunk ${c.fileIndex}@${c.offset} (expected $rxIndex@$rxOffset)")
                cancel("error", tellPeer = true)
                return
            }
            rxOffset += c.len
            enqueue(c)
        }

        fun cancel(reason: String, tellPeer: Boolean) {
            if (cancelled != null) return
            cancelled = reason
            if (tellPeer) s.send(cancelMsg(id, "in", reason))
            queue.clear()
            queue.offer(CANCEL)
        }

        override fun run() {
            var ok = false
            try {
                advanceZeroSize()
                while (cancelled == null) {
                    val item = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    when (item) {
                        CANCEL -> break
                        DONE -> {
                            // Ordered channel: every chunk precedes xfer_done, so anything missing now is lost.
                            if (cur < files.size) throw XferError(Hub.app.getString(com.shazan.airbridge.R.string.xf_ended_early))
                            ok = true
                            finishAll() // §6.1: xfer_complete only after xfer_done with everything written
                            return
                        }
                        is Protocol.Chunk -> { queuedBytes.addAndGet(-item.len.toLong()); write(item) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "receive $id from $peerName failed: ${e.message}")
                if (cancelled == null) {
                    cancelled = "error"
                    s.send(cancelMsg(id, "in", "error"))
                }
            } finally {
                if (!ok) {
                    target?.let { Saver.discard(ctx, it) } // partial file only; completed ones stay
                    target = null
                    val text = when (cancelled) {
                        "user" -> Hub.app.getString(com.shazan.airbridge.R.string.xfer_cancelled)
                        "disconnect" -> Hub.app.getString(com.shazan.airbridge.R.string.xf_interrupted, peerName)
                        null, "error" -> Hub.app.getString(com.shazan.airbridge.R.string.xfer_failed)
                        else -> Hub.app.getString(com.shazan.airbridge.R.string.xf_cancelled_by, peerName)
                    }
                    Hub.putTransfer(TransferUi(key, true, peerName, files.getOrNull(cur)?.name ?: "", saved.size, files.size, written, total, 0, result = text))
                    resultNotification(notifId, Hub.app.getString(com.shazan.airbridge.R.string.xf_in_failed, peerName), text, null)
                }
                incoming.remove(id)
                TransferLocks.release()
            }
        }

        private fun write(c: Protocol.Chunk) {
            val f = files[cur]
            val t = target ?: Saver.create(ctx, f.name, f.mime, taken).also { target = it }
            t.out.write(c.data, c.dataOff, c.len)
            curWritten += c.len
            written += c.len
            if (curWritten == f.size) {
                finishFile()
                ack()
                advanceZeroSize()
            } else if (written - lastAck >= Protocol.ACK_EVERY / 2) ack() // keeps every gap ≤ 1 MiB for chunks ≤ 1 MiB
            ui()
        }

        private fun finishFile() {
            val t = target ?: return
            t.out.close()
            saved.add(Saver.publish(ctx, t))
            target = null
            cur++
            curWritten = 0
        }

        /** Zero-size files have no chunks (§6.2): create them as soon as they are next. */
        private fun advanceZeroSize() {
            while (cur < files.size && files[cur].size == 0L) {
                target = Saver.create(ctx, files[cur].name, files[cur].mime, taken)
                finishFile()
            }
        }

        private fun ack() {
            lastAck = written
            s.send(Protocol.msg("xfer_ack").put("id", id).put("received", written))
        }

        private fun ui(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastUi < UI_INTERVAL_MS) return
            lastUi = now
            val bps = if (now > started) written * 1000 / (now - started) else 0
            val name = files.getOrNull(cur)?.name ?: ""
            Hub.putTransfer(TransferUi(key, true, peerName, name, saved.size, files.size, written, total, bps))
            progressNotification(notifId, true, name, saved.size, files.size, written, total, bps, cancelAction(id, true))
        }

        private fun finishAll() {
            s.send(Protocol.msg("xfer_complete").put("id", id).put("ok", true).put("saved", JSONArray(saved)))
            val label = if (saved.size == 1) saved[0] else Hub.app.resources.getQuantityString(com.shazan.airbridge.R.plurals.sh_files, saved.size, saved.size)
            Hub.putTransfer(TransferUi(key, true, peerName, label, saved.size, files.size, total, total, 0, result = Hub.app.getString(com.shazan.airbridge.R.string.xfer_received)))
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            resultNotification(notifId, Hub.app.getString(com.shazan.airbridge.R.string.xf_received_from, peerName), "$label · Downloads/${Saver.SUBDIR}", open)
        }
    }

    // ================= notifications =================

    private fun progressNotification(
        id: Int, incoming: Boolean, file: String, filesDone: Int, filesTotal: Int,
        done: Long, total: Long, bps: Long, action: NotificationCompat.Action,
    ) {
        TransferNotifs.progress(ctx, id, incoming, peerName, file, filesDone, filesTotal, done, total, bps, action)
    }

    private fun resultNotification(id: Int, title: String, text: String, open: PendingIntent?) =
        TransferNotifs.finished(ctx, id, title, text, open)
}

fun formatSpeed(bps: Long): String = String.format(Locale.US, "%.1f MB/s", bps / (1024.0 * 1024.0))

/** Destination handling for received files (§6.3). */
internal object Saver {
    const val SUBDIR = "AirBridge Transfers"
    private const val TAG = "AirBridge.Saver"
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    class Target(val out: OutputStream, val uri: Uri?, val file: File?, val name: String)

    fun sanitize(raw: String): String {
        var n = raw.substringAfterLast('/').substringAfterLast('\\')
        n = n.filter { it >= ' ' && it != '\u007f' && it !in '\u0080'..'\u009f' && it !in "<>:\"|?*" }
        n = n.trim('.', ' ')
        if (n.substringBefore('.').trimEnd().uppercase(Locale.ROOT) in RESERVED) n = "_$n"
        n = truncateUtf8(n, 200).trimEnd('.', ' ')
        return n.ifEmpty { "file" }
    }

    private fun truncateUtf8(name: String, maxBytes: Int): String {
        if (name.toByteArray(Charsets.UTF_8).size <= maxBytes) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        var base = name.substring(0, name.length - ext.length)
        while (base.isNotEmpty() && (base + ext).toByteArray(Charsets.UTF_8).size > maxBytes) {
            base = base.substring(0, base.offsetByCodePoints(base.length, -1))
        }
        return base + ext
    }

    private fun unique(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 2..9999) {
            val c = "$base ($i)$ext"
            if (!exists(c)) return c
        }
        return "$base (${System.currentTimeMillis()})$ext"
    }

    private fun mimeFor(name: String, offered: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: offered?.takeIf { it.contains('/') && ext.isEmpty() }
            ?: "application/octet-stream"
    }

    /** [taken] = names already present in the destination (see [existingNames]); updated with the name chosen. */
    fun create(ctx: Context, rawName: String, offeredMime: String?, taken: MutableSet<String>): Target {
        val clean = sanitize(rawName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = ctx.contentResolver
            val name = unique(clean) { it in taken }.also { taken.add(it) }
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name, offeredMime))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("MediaStore insert failed")
            val out = try { cr.openOutputStream(uri, "w") } catch (e: Exception) { null }
            if (out == null) { cr.delete(uri, null, null); throw IOException("cannot open output") }
            val actual = try {
                cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (_: Exception) { null }
            if (actual != null) taken.add(actual)
            return Target(out.buffered(256 * 1024), uri, null, actual ?: name)
        }
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val root = if (granted) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        else ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        val dir = File(root, SUBDIR).apply { mkdirs() }
        val name = unique(clean) { File(dir, it).exists() }
        val f = File(dir, name)
        if (f.canonicalFile.parentFile != dir.canonicalFile) throw IOException("bad path")
        return Target(FileOutputStream(f).buffered(256 * 1024), null, f, name)
    }

    /** Makes the file visible; returns its final name. */
    fun publish(ctx: Context, t: Target): String {
        if (t.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ctx.contentResolver.update(t.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else if (t.file != null) {
            MediaScannerConnection.scanFile(ctx, arrayOf(t.file.path), null, null)
        }
        return t.name
    }

    fun discard(ctx: Context, t: Target) {
        try { t.out.close() } catch (_: Exception) {}
        try {
            if (t.uri != null) ctx.contentResolver.delete(t.uri, null, null)
            t.file?.delete()
        } catch (e: Exception) { Log.w(TAG, "discard", e) }
    }

    fun existingNames(ctx: Context): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
        val out = HashSet<String>()
        try {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR%"), null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { out.add(it) } }
        } catch (e: Exception) { Log.d(TAG, "existingNames: ${e.message}") }
        return out
    }

    /** Delete IS_PENDING rows left by a crash/kill mid-transfer. Call before any transfer starts. */
    fun cleanupStalePending(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val cr = ctx.contentResolver
            val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING}=1"
            val args = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR%")
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                }, null)
            } else {
                @Suppress("DEPRECATION")
                cr.query(MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI), arrayOf(MediaStore.MediaColumns._ID), sel, args, null)
            }
            cursor?.use { c ->
                while (c.moveToNext()) {
                    cr.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)), null, null)
                }
            }
        } catch (e: Exception) { Log.w(TAG, "cleanupStalePending", e) }
    }
}
