# Transfer speed: AirBridge vs LocalSend, and how to go faster

Status: research + design note, 2026-11-02. Nothing here changes the protocol yet.
Labels: **[measured]** = we ran it; **[source]** = read in code; **[reported]** = user reports in issues;
**[estimate]** = reasoning, verify with the benchmark plan in §5.

## 1. How LocalSend actually transfers

Read from `localsend/localsend` main (pushed 2026-10-29) and the v1.18.2 tag (2026-08-21); the
transfer path is the same in both.

| Aspect | What LocalSend does | Evidence |
|---|---|---|
| Discovery | UDP multicast `224.0.0.167:53317` announce; peers answer with `POST /api/localsend/v2/register`; legacy fallback = HTTP scan of every local IP | [protocol README](https://github.com/localsend/protocol/blob/main/README.md) |
| Transport | HTTPS with a self-signed cert pinned by SHA-256 fingerprint (HTTP optional). Since the Rust migration: `hyper 1` server (`http1` only), `reqwest` client, `rustls` + `ring` (TLS 1.2/1.3) | `packages/core/Cargo.toml`; [#3026](https://github.com/localsend/localsend/pull/3026) |
| HTTP version | **HTTP/1.1 forced** by ALPN: comment says *"HTTP/2's flow-control window caps bulk upload throughput"* | `packages/core/src/http/client/mod.rs` (`alpn_protocols = [http/1.1]`) |
| Upload model | `POST /prepare-upload` once per batch, then **one `POST /upload?sessionId&fileId&token` per file**, raw body, no chunking/resume | protocol README |
| Concurrency | **2 files at once** (`const _concurrency = 2`) → up to 2 TCP+TLS connections; a single big file uses 1 | `localsend_isolates/.../upload_isolate.dart` |
| Buffers | sender reads 512 KiB (`READ_BUFFER_SIZE`), channel capacity 16; receiver coalesces TLS records (~16 KiB) in a 512 KiB `BufWriter` | `core/src/model/transfer.rs`, `core/src/http/server/common/save.rs` |
| Hashing | **SHA-256 on by default** (`ls_create_checksums ?? true`, `ls_verify_checksums ?? true`). The sender hashes **every file, one after another, before the first byte is uploaded**; the receiver verifies inline and returns 422 → re-upload | `app/lib/provider/persistence_provider.dart`, `send_provider.dart` L163-210 |
| Encryption | TLS only (AES-GCM or ChaCha20 as negotiated) | as above |
| Progress | Rust → Dart isolate → main isolate events per chunk ("throttled") | `rust/src/api/http.rs`, `upload_isolate.dart` |

**Throughput numbers people report [reported]**
- Dart era, HTTPS on: 5–6 MB/s; HTTPS off: 75–100 MB/s on Wi-Fi 6 / 5 GHz
  ([#1321](https://github.com/localsend/localsend/issues/1321), [#1090](https://github.com/localsend/localsend/issues/1090), [#384](https://github.com/localsend/localsend/issues/384)).
- After the Rust core (v1.18.x): one user sees a ceiling of ~21 MB/s whether encryption is on or off, Windows↔iOS, on links that test at 800 Mb/s ([#384, latest comments](https://github.com/localsend/localsend/issues/384)).
- Many small files: speed falls to 1–2 MB/s and keeps falling across a 7 000-file or 10 000-file batch ([#1657](https://github.com/localsend/localsend/issues/1657), [#2402](https://github.com/localsend/localsend/issues/2402)).
- Android receiving is a known weak spot (SAF writes; [#1090](https://github.com/localsend/localsend/issues/1090)).

**LocalSend's real bottlenecks.** (1) The serial SHA-256 pre-pass delays the start of every transfer. It reads all the data once before sending it [source]. At ~1–2 GB/s that adds ~1 s per GB on a Mac and more on phones [estimate].
(2) One request per file: each file costs a request/response round trip, a token check and a file open/close. Only 2 run in parallel, which explains the small-file collapse [source + reported].
(3) TLS 16 KiB records and one TCP connection for a single large file [source].
(4) Dart↔Rust bridging for streams and progress, plus Flutter UI work. This was the dominant cost in the Dart era and is the likely cause of the reported ~21 MB/s ceiling [estimate].
(5) Android receive through SAF/MediaStore [reported].

## 2. AirBridge today: where we are slower or faster

Current design (PROTOCOL.md §3.6, §6): one WebSocket over one TCP connection, every frame
`0x02|seq|AES-256-GCM(innerKind|payload)`, 512 KiB chunks, 8 MiB receiver window, acks every 512 KiB–1 MiB,
one `xfer_offer` per batch, so small files flow back-to-back with no per-file round trip.

| # | Finding | Evidence | Impact |
|---|---|---|---|
| A1 | **Android receiver runs a MediaStore query over the whole `Download/AirBridge Transfers` folder for every file** (`existingNames`). It then does insert → openOutputStream → query → update(IS_PENDING=0): about 5 binder calls per file. The per-file cost is O(files ever received), so a batch is O(N²) | `FileTransfer.kt` L552-572, L556, L586-593, L603-613 | **Large** for 1000+ files [estimate: 5–20 ms/file; 10 000 × 10 KB → minutes] |
| A2 | No `WifiLock`, no `PARTIAL_WAKE_LOCK` anywhere in the app. With the screen off, Wi-Fi power-save (DTIM sleep) and CPU suspend can throttle the transfer | `grep WifiLock\|WakeLock` → none | Large when the screen is off [estimate] |
| A3 | Android path copies each 512 KiB chunk **5×** to send and **5×** to receive. Send: readFully buf → `chunkFrame` → `seal` output → `toByteString` → okio segments + client mask. Receive: okio frame buffer → `readByteString` → `toByteArray` → `doFinal` output → `copyOfRange`. About 2–3 MB of large-object garbage per chunk means ART GC churn | `FileTransfer.kt` L188-206, `Protocol.kt` L101-109, `SecureChannel.kt` L198-226, `WsClient.kt` L212, L228 | CPU/battery. Not the link limit on flagships [measured: ~1.7× CPU vs zero-copy, §4] |
| A4 | `seal` feeds the 1-byte inner kind through `Cipher.update()` and then `doFinal(payload)`. Conscrypt's AEAD buffers input until `doFinal`, which likely adds another full copy. `Cipher.init` runs per frame (cheap) | `SecureChannel.kt` L203-206 | small CPU [estimate] |
| A5 | The Android sender polls the OkHttp queue (`wait(10)` while `queueSize ≥ 2 MiB`); queue drain never notifies. The 2 MiB high-water mark is right: OkHttp **closes the socket if the queue would exceed 16 MiB**, and `send()` returns false | `FileTransfer.kt` L50, L197-205 | Minor: wake-ups, ≤10 ms jitter [estimate] |
| A6 | One TCP flow. On busy or lossy Wi-Fi, one CUBIC flow often leaves 10–30% of airtime unused. On Android the per-socket buffer may also cap throughput below the BDP when bufferbloat pushes RTT to 20–40 ms | design | Medium on bad Wi-Fi, ~0 on clean 5 GHz [estimate; check with `iperf3 -P1` vs `-P4`] |
| A7 | macOS sender: one disk read at a time and `inFlight < 2` (≤1 MiB queued in `NWConnection`), with two queue hops per chunk. Probably enough because `.contentProcessed` fires when TCP takes the bytes | `FileTransfer.swift` L382-431 | Small [estimate] |
| A8 | macOS seal/open: `plain` copy, `box` → `out` append, `Data(plain[1...])`. Client role adds a mask copy (`var body = payload`) | `SecureChannel.swift` L119-150, `WebSocket.swift` L125-132 | small CPU (AES on M-series is ~5 GB/s/core) |
| A9 | Windows sender awaits each chunk's `SendAsync` before reading the next (read ↔ send serialised). The receiver writes without preallocation (NTFS extends the file per write); Defender scans each file on close | `FileTransfer.cs` L384-410, L213-215; `Session.cs` L215-228 | Small for big files, noticeable for many small files [estimate] |
| A10 | Windows envelope is already zero-copy (in-place `AesGcm`, `ArrayPool`) | `SecureChannel.cs` L179-210 | good |
| A11 | 8 MiB window vs BDP: at 110 MB/s it covers ~75 ms of RTT plus receiver write latency. Fine on LAN; acks wait for the disk write, which is correct | `Protocol.kt` L37 | Not a bottleneck on LAN [estimate] |
| A12 | Nagle off (`NoDelaySocketFactory`, `tcp.noDelay`, `client.NoDelay`) and permessage-deflate off on every platform | `WsClient.kt` L242-252; `WebSocket.swift` L42; `Server.cs` L111 | good |
| A13 | **No pre-hash**: the first byte leaves right after `xfer_accept`; GCM already authenticates each frame | design | **faster than LocalSend** at start |
| A14 | **One offer per batch, continuous chunk stream across files**, so no per-file round trip | §6.1 | **faster than LocalSend** for small files (except A1 on Android) |

### Side by side

| Bottleneck | LocalSend 1.18 | AirBridge v2 now |
|---|---|---|
| Start latency | SHA-256 pre-pass over all files (on by default) | none |
| Many small files | 1 HTTP request per file, 2 parallel | streamed in one transfer; **Android receiver O(N²) MediaStore lookups (A1)** |
| Big single file | 1 TLS connection, 16 KiB records | 1 WS connection, 512 KiB GCM frames |
| Parallelism | 2 files → 2 connections | none |
| Crypto CPU per GiB, loopback | TLS 1.3: 0.74 s/GiB [measured] | current: 1.66 s/GiB; zero-copy: 0.95 [measured] |
| Integrity | TLS + optional SHA-256 | GCM per frame; no whole-file hash |
| Resume | none | none |
| Android power | (not checked) | no Wifi/Wake lock (A2) |
| Android save path | SAF/MediaStore | MediaStore + IS_PENDING (fine) + A1 |

**Bottom line [estimate]:** for one big file on good 5 GHz, both apps are limited by the link and the
receiver, so we should already roughly match or beat LocalSend (no pre-hash, 32× larger crypto records). We lose
on Android receive of many files (A1) and with the phone screen off (A2). Parallel streams and a leaner frame
path are how we pull clearly ahead.

## 3. The speed plan, ranked by gain ÷ effort

| Rank | Change | Expected gain | Effort | Risk |
|---|---|---|---|---|
| 1 | Fix A1: cache folder names once per transfer (or insert with the desired name and let MediaProvider de-dupe, reading back `DISPLAY_NAME`) | 10 000 × 10 KB to Android: **5–50×** [estimate] | XS | none |
| 2 | Android radio/CPU: `WIFI_MODE_FULL_LOW_LATENCY` lock + `PARTIAL_WAKE_LOCK` for the transfer's lifetime; keep the screen on while the transfer UI is open | screen-off transfers: **up to 2–5×**; screen-on: 0–15% [estimate] | XS | battery only during transfers |
| 3 | Zero-copy envelope on Android and macOS, same wire format (pooled buffers, read straight into the frame, seal/mask/open in place) | CPU per GiB **−43%** [measured, loopback 1.66 → 0.95 s/GiB]; single-core ceiling **+50%** [measured 865 → 1303 MiB/s] | S | low; no protocol change |
| 4 | Parallel bulk streams (2–3 TCP connections) with work-stealing chunk assignment | lossy/busy Wi-Fi **+10–40%**; clean link 0–5% [estimate; decide per pair with a 1 s probe] | M | more code; hurts on 2.4 GHz/congested APs → adaptive |
| 5 | Raw bulk data socket (no WebSocket framing, 256 KiB GCM records, TCP backpressure instead of app window) | CPU −40% vs current zero-copy WS [measured 0.95 → 0.59 s/GiB], ceiling +55%; Wi-Fi throughput +0–5% | M | second socket: firewall, fallback needed |
| 6 | Small-file batching in the stream + preallocation + positional writes on a dedicated I/O thread | many files: +20–100% on desktop receivers [estimate] | S | low |
| 7 | Resume after reconnect (per-file offsets) | saves whole re-sends on flaky links | M | partial-file lifetime |
| 8 | Selective zstd-1 (sample the first 256 KiB; skip jpg/mp4/zip/heic) | only text/logs/raw: up to 2–4×; others 0 | S | CPU on phones; must be adaptive |
| — | QUIC, custom UDP, Wi-Fi + Wi-Fi Direct bonding, multipath | no (see §3.9) | — | — |

### 3.1 Parallel streams (rank 4)
Design: `xfer_accept` gains `"streams":N` and `"bulk":{port,token,key}` (see 3.3). Each stream pulls the next
`(fileIndex, offset, len)` unit from a shared cursor. Work-stealing beats round-robin because Wi-Fi flows are
unequal. The receiver preallocates every file and writes positionally. A file is done when its byte count is
reached (set of ranges, not "next offset"). Today's in-order check (`FileTransfer.kt` L353-361) becomes
a range check. Why it helps: each flow gets its own congestion window and socket buffer, recovers from losses
independently, and Wi-Fi aggregation (A-MPDU) fills better. iperf3 `-P 4` vs `-P 1` on the real pair tells
you the gain *before* you build it. When it hurts: 2.4 GHz and crowded APs (more contention and retries),
weak phone CPUs (more threads), HDD sources on Windows (seek thrash, cf. LocalSend
[#2814](https://github.com/localsend/localsend/issues/2814)). So: start with 1 stream, add a 2nd after 1 s if
throughput is below 80% of the probe, and stop adding once a new stream gains less than 5%.

### 3.2 Bigger frames and window
Loopback shows 512 KiB → 1 MiB WS frames gain **0%** on the current copy-heavy path and **+11%** on the zero-copy
path [measured]. Low value on its own. Raising the window helps only if RTT × rate > 8 MiB, which needs >70 ms
at 110 MB/s, so it does not happen on a LAN. Better: size the window as `max(8 MiB, 2 × measured rate × srtt)`,
capped at 64 MiB (`MAX_WINDOW` already allows it). Keep acks at ≥1 MiB, since Wi-Fi is half-duplex and fewer
upstream frames is slightly better.

### 3.3 Raw bulk path (rank 5), encryption kept mandatory
- Capability `bulk1` in `hello.caps`. Android is always the WebSocket client, so **the client dials the data
  socket in both directions**. Android needs no listener, and Mac/Windows reuse their existing port by
  sniffing the first 4 bytes (`ABX1` instead of `GET `).
- Keys: `bulkKey = HKDF(ikm = sessionExporter, salt = xferId‖nonce16, info = "airbridge/v2/bulk|" ‖ dir, 32)`.
  `sessionExporter` is 32 extra bytes from the §3.6 HKDF output (length 96 instead of 64), kept beside
  the envelope keys. Simpler alternative with the same security: put a random 32-byte key in the
  encrypted `xfer_accept`. Forward secrecy is unchanged because it travels inside the session.
- Preamble: `ABX1 | u32 xferId | u8 stream | HMAC(bulkKey,"open"|xferId|stream)[0..16]`.
- Record: `u32 fileIndex | u64 offset | u32 len` (AAD) ‖ `AES-256-GCM(payload)` ‖ tag, nonce = `u32 stream ‖ u64 seq`.
  A final authenticated `len=0` record marks the end of the stream (truncation detection).
- Flow control is TCP's: the receiver stops reading when its write queue holds more than 8 MiB.
  `xfer_ack` stays on the control channel for progress and completion. Fall back to today's path if the
  connect fails within 1 s, and always on Nearby.

### 3.4 Crypto and copies per platform (rank 3)
- **Android**: Conscrypt already is BoringSSL with ARMv8 AES/PMULL. Tink or another provider gains nothing
  [estimate]. Fix the copies: read the file straight into a pooled `ByteArray` at the frame's payload offset
  (and put the kind byte there too, so it is one `doFinal(in,inOff,len,out,outOff)` with no `update`). Send with
  `ByteString.of(array, off, len)`… which still copies (okio). The real zero-copy option is our own
  WebSocket/TCP writer or the raw bulk socket (3.3). `FileChannel.transferTo`/`sendfile` is impossible with
  encryption in userspace. ChaCha20-Poly1305 only helps SoCs without the ARMv8 crypto extensions (rare, low-end).
  Not worth negotiating now.
- **macOS**: CryptoKit is ~5.2 GB/s/core here [measured with Go's ARMv8 AES-GCM as a proxy]. Remove the
  `plain`/`out` re-assembly copies with `AES.GCM.seal` into a preallocated buffer (or CommonCrypto/BoringSSL),
  and avoid the `Data` slice copies in `open`.
- **Windows**: already in place, so nothing to do.

### 3.5 Android Wi-Fi tuning (rank 2)
`WifiManager.createWifiLock(WIFI_MODE_FULL_LOW_LATENCY, …)`. Since API 34, `WIFI_MODE_FULL_HIGH_PERF` silently
becomes LOW_LATENCY, which only takes effect while the app is in the foreground with the screen on
([WifiManager docs](https://developer.android.com/reference/android/net/wifi/WifiManager)). So also: a
`PARTIAL_WAKE_LOCK` from offer to complete, `FLAG_KEEP_SCREEN_ON` on the transfer screen, and on Android 12+
`PerformanceHintManager`/higher thread priority so crypto threads stay on big cores. Band: we cannot pick 5/6 GHz
programmatically on a user's network, but we can **show a hint** ("you're on 2.4 GHz, expect ≤ 10 MB/s") from
`WifiInfo.frequency` and the link speed. Bind sockets to the Wi-Fi `Network` (`Network.bindSocket`) so traffic
never falls back to cellular or VPN.

### 3.6 Disk path (rank 6)
Receiver: preallocate (`posix_fallocate` via `Os.posix_fallocate` on Android, `F_PREALLOCATE` on macOS,
`FileStreamOptions.PreallocationSize` on .NET). Positional writes on a dedicated I/O thread with a bounded queue,
which already exists on all three. Android: keep MediaStore + `IS_PENDING` but write through
`openFileDescriptor("rw")` + `FileChannel.write(buf, pos)`. Writing to app cache and moving costs a full copy on
scoped storage, so no. No fsync per chunk; one at close at most. Many tiny files: the stream is already "tar-like".
Also batch the `IS_PENDING=0` updates (`ContentResolver.applyBatch`) and the per-file `xfer_ack`.

### 3.7 Compression (rank 8)
zstd-1 runs at a few hundred MB/s per desktop core and less on phones [estimate], which is close to Wi-Fi speed.
It only pays off for clearly compressible data. Flag per file in the offer. Skip by MIME/extension and by a 256 KiB
sample whose ratio is >0.9.

### 3.8 Resume and dedup
`xfer_resume {id, files:[{i, have}]}` after reconnect. Keep `IS_PENDING` partials for 10 minutes instead of
deleting them on disconnect (today: `Saver.discard`). An optional per-file hash computed **while streaming**
(BLAKE3/xxh3) and sent in `xfer_done` gives end-to-end integrity without LocalSend's pre-pass. Dedup (skip if an
identical file exists) needs a hash up front, so only offer it for resume.

### 3.9 What not to do
- **Wi-Fi + Wi-Fi Direct/hotspot bonding**: one radio, time-sliced (MCC). Usually slower, not faster.
- **Multipath over two physical links**: needs two independent links (rarely present between a phone
  and a laptop) and MPTCP, which apps cannot rely on (Android: no app API; Windows: none). Out of scope.
- **QUIC**: helps loss recovery and avoids head-of-line blocking, but userspace QUIC costs much more CPU per byte.
  Measured up to 45% lower throughput than TCP+TLS on fast links
  ([Zhang et al., "QUIC is not Quick Enough over Fast Internet", WWW 2024](https://arxiv.org/abs/2310.09423)).
  Phones are exactly where that hurts.
- **BBR**: congestion control is system-wide and set by the sender's OS (macOS/Windows: CUBIC). Apps cannot
  switch it on stock Android. Parallel streams get most of the benefit.
- **Custom UDP protocol**: high cost and risk, no LAN upside over tuned TCP.

## 4. Localhost benchmark [measured]

Go 1.26, Apple M1 Pro (10 cores), macOS 26.5.1, loopback TCP, NODELAY, 1 GiB per run, 3 runs (median).
**Loopback has no Wi-Fi and no disk. It measures CPU/crypto/framing cost only**, both ends on one machine.
CPU = user+sys seconds per GiB for both ends combined. Harness: a local Go benchmark (`bench/main.go`, kept outside
the repo). `ws` copies like the Android code. `wsopt` keeps the same v2 wire format with zero copies.

| Design | MiB/s | CPU s/GiB | 1 core (GOMAXPROCS=1) |
|---|---|---|---|
| plain TCP (ceiling) | 9 201 | 0.18 | — |
| LocalSend-like: TLS 1.3, 1 conn | 2 514 | 0.74 | 1 762 MiB/s |
| LocalSend-like: TLS 1.3, 2 conns | 3 277 | 1.10 | — |
| (a) **current** WS + GCM 512 KiB | 1 376 | 1.66 | 865 MiB/s |
| (b) current, 1 MiB frames | 1 372 | 1.61 | — |
| (a′) zero-copy WS, 512 KiB | 2 100 | 0.95 | 1 303 MiB/s |
| (b′) zero-copy WS, 1 MiB | 2 341 | 0.86 | — |
| (c) raw stream, 64 KiB records | 2 964 | 0.65 | — |
| (c) raw stream, 256 KiB records | 3 275 | 0.59 | 1 800 MiB/s |
| (c) raw stream, 1 MiB records | 3 363 | 0.55 | — |
| (d) current WS × 3 streams | 2 540 | 2.13 | — |
| (d′) zero-copy WS × 3 streams | 5 963 | 1.05 | — |
| (d″) raw 256 KiB × 3 streams | 7 811 | 0.72 | — |

Micro: AES-256-GCM seal ≈ 5.0 GB/s/core at 16 KiB records and ≈ 5.2 GB/s at 64 KiB–1 MiB. SHA-256 ≈ 2.3 GB/s/core
(LocalSend's pre-pass ≈ 0.45 s/GiB of pure CPU here before any I/O).

How to read this: every design is 10× or more above Wi-Fi on a Mac. A phone big core is roughly 3–5× slower and
ART adds GC [estimate], which puts today's path at ~200–300 MB/s on a phone core. That is above real Wi-Fi 6 TCP
(~60–110 MB/s), but it uses a large share of a core and battery. The copy removal (a→a′) is the cheapest CPU win.
The raw path (c) costs about as much CPU as TLS, and is cheaper per byte than our current path.

## 5. Fair benchmark vs LocalSend (real devices)

1. **Same pair, same spot**: Android phone ↔ MacBook, then ↔ Windows PC. Same SSID and band (record
   `adb shell cmd wifi status` frequency/link speed; macOS `wdutil info`). Both on chargers, battery saver off,
   phone screen on (and a second pass with the screen off), other apps closed. Cool down for 60 s between runs.
2. **Link ceiling**: `iperf3 -s` on the Mac (`brew install iperf3`), and on the phone use Termux `pkg install
   iperf3` (no stock binary; Play Store "Magic iPerf" also works). Windows: iperf.fr build. Run 20 s each way
   (`-R`), with `-P 1` and `-P 4`. The `-P4/-P1` ratio predicts the gain from parallel streams.
3. **Datasets** (incompressible: `dd if=/dev/urandom`): 1 × 1 GiB; 1000 × 1 MiB; 10 000 × 10 KiB.
4. **Configs**: AirBridge current; LocalSend defaults (HTTPS on, checksums on); LocalSend fastest (HTTPS off,
   checksums off). Order A-B-C, C-B-A, A-B-C (3 runs each) to cancel thermal and order effects.
5. **Timing**: from the send tap to the receiver's "complete" event. AirBridge: logcat/os_log timestamps of
   `xfer_offer` and `xfer_complete`. LocalSend: a screen recording, or a watcher script polling total bytes in the
   receive folder; use the same script for both apps.
6. **Report**: MB/s = bytes ÷ wall time, % of iperf3 `-P1` and `-P4`, phone CPU (`adb shell top -d 1`)
   and battery temperature. Target: AirBridge ≥ LocalSend-fastest on every dataset, and ≥ 90% of iperf3 `-P1` on 1 GiB.

To rerun the localhost numbers: `cd bench && go build -o bench . && ./bench -mode micro &&
./bench -mode ws && ./bench -mode wsopt && ./bench -mode raw -frame 262144 -streams 3` (modes: plain|tls|ws|wsopt|raw;
flags `-frame -streams -mb -runs -window`).
