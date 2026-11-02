package com.shazan.airbridge.net.link

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Raw TCP throughput over a joined network (device test S-W4). Sockets come from
 * [Network.getSocketFactory], so traffic cannot leak onto Wi-Fi/cellular (an `iperf3` in Termux would).
 * Peer: tools/wfd_tcp_peer.py — the first byte selects the direction: 'U' phone → peer, 'D' peer → phone.
 */
object LinkBench {
    data class Result(val bytes: Long, val ms: Long) {
        val mbps: Double get() = if (ms > 0) bytes / 1e6 / (ms / 1000.0) else 0.0
    }

    suspend fun run(
        network: Network, host: String, port: Int, upload: Boolean, seconds: Int = 10,
        onProgress: (Result) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val sock: Socket = network.socketFactory.createSocket()
        try {
            sock.tcpNoDelay = true
            if (upload) sock.sendBufferSize = 1 shl 20 else sock.receiveBufferSize = 1 shl 20
            sock.connect(InetSocketAddress(host, port), 3_000)
            sock.soTimeout = 3_000
            val out = sock.getOutputStream()
            out.write(if (upload) 'U'.code else 'D'.code)
            out.flush()
            val buf = ByteArray(64 * 1024)
            val t0 = System.nanoTime()
            val end = t0 + seconds * 1_000_000_000L
            var bytes = 0L
            var lastReport = t0
            val input = sock.getInputStream()
            while (System.nanoTime() < end) {
                ensureActive()
                if (upload) { out.write(buf); bytes += buf.size } else {
                    val n = input.read(buf)
                    if (n < 0) break
                    bytes += n
                }
                val now = System.nanoTime()
                if (now - lastReport > 500_000_000L) {
                    lastReport = now
                    onProgress(Result(bytes, (now - t0) / 1_000_000))
                }
            }
            Result(bytes, (System.nanoTime() - t0) / 1_000_000)
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }
}
