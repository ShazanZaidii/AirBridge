// AirBridge transfer-path localhost benchmark.
// Measures CPU/crypto/framing overhead only (loopback TCP: no Wi-Fi, no disk).
//
// Modes:
//   plain      raw TCP, no crypto (loopback ceiling)
//   ws         current AirBridge path modelled with the Android copy pattern:
//              chunkFrame copy, seal into new array, toByteString copy, client masking copy,
//              receiver: frame buffer + ByteString + toByteArray + open alloc + copyOfRange,
//              sealed xfer_ack every 512 KiB, 8 MiB window
//   wsopt      same wire format (v2 compatible), zero-copy: pooled buffers, in-place seal/mask/open
//   raw        dedicated data socket: u32 len | AES-GCM record (in place), TCP flow control only
//   tls        Go crypto/tls 1.3 (16 KiB records), 512 KiB writes: LocalSend-like transport
package main

import (
	"bufio"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"sort"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

var (
	mode    = flag.String("mode", "ws", "plain|ws|wsopt|raw|tls|micro")
	frame   = flag.Int("frame", 512<<10, "chunk / record size in bytes")
	streams = flag.Int("streams", 1, "parallel TCP connections")
	totalMB = flag.Int("mb", 1024, "MiB to send in total")
	runs    = flag.Int("runs", 3, "runs")
	window  = flag.Int64("window", 8<<20, "receiver window (ws modes)")
)

var key = make([]byte, 32)
var src []byte // pre-filled "file" contents

func newGCM() cipher.AEAD {
	b, _ := aes.NewCipher(key)
	g, _ := cipher.NewGCM(b)
	return g
}

func nonce(n []byte, stream uint32, seq uint64) {
	binary.BigEndian.PutUint32(n[0:4], stream)
	binary.BigEndian.PutUint64(n[4:12], seq)
}

func cpuSeconds() float64 {
	var r syscall.Rusage
	syscall.Getrusage(syscall.RUSAGE_SELF, &r)
	return float64(r.Utime.Sec+r.Stime.Sec) + float64(r.Utime.Usec+r.Stime.Usec)/1e6
}

func mask(b []byte, k [4]byte) {
	m := uint64(binary.LittleEndian.Uint32(k[:]))
	m |= m << 32
	i := 0
	for ; i+8 <= len(b); i += 8 {
		binary.LittleEndian.PutUint64(b[i:], binary.LittleEndian.Uint64(b[i:])^m)
	}
	for ; i < len(b); i++ {
		b[i] ^= k[i%4]
	}
}

func wsHeader(n int, masked bool, k [4]byte) []byte {
	h := make([]byte, 0, 14)
	h = append(h, 0x82)
	mb := byte(0)
	if masked {
		mb = 0x80
	}
	switch {
	case n < 126:
		h = append(h, mb|byte(n))
	case n <= 0xFFFF:
		h = append(h, mb|126, byte(n>>8), byte(n))
	default:
		h = append(h, mb|127)
		h = binary.BigEndian.AppendUint64(h, uint64(n))
	}
	if masked {
		h = append(h, k[:]...)
	}
	return h
}

// readWS reads one frame header; returns payload length, mask key, masked flag.
func readWSHeader(r *bufio.Reader) (int, [4]byte, bool, error) {
	var k [4]byte
	var h [2]byte
	if _, err := io.ReadFull(r, h[:]); err != nil {
		return 0, k, false, err
	}
	masked := h[1]&0x80 != 0
	n := int(h[1] & 0x7F)
	if n == 126 {
		var e [2]byte
		io.ReadFull(r, e[:])
		n = int(binary.BigEndian.Uint16(e[:]))
	} else if n == 127 {
		var e [8]byte
		io.ReadFull(r, e[:])
		n = int(binary.BigEndian.Uint64(e[:]))
	}
	if masked {
		io.ReadFull(r, k[:])
	}
	return n, k, masked, nil
}

// ---------------- ws (current, Android copy pattern) ----------------

type flow struct {
	mu    sync.Mutex
	cond  *sync.Cond
	acked int64
}

func wsSender(c net.Conn, total int64, fs int, opt bool, sid uint32) {
	g := newGCM()
	var seq uint64
	nb := make([]byte, 12)
	f := &flow{}
	f.cond = sync.NewCond(&f.mu)
	// ack reader (host -> client, unmasked, sealed JSON)
	go func() {
		r := bufio.NewReaderSize(c, 64<<10)
		dg := newGCM()
		var rseq uint64
		dn := make([]byte, 12)
		buf := make([]byte, 4096)
		for {
			n, _, _, err := readWSHeader(r)
			if err != nil {
				f.mu.Lock(); f.acked = 1 << 62; f.cond.Broadcast(); f.mu.Unlock()
				return
			}
			io.ReadFull(r, buf[:n])
			nonce(dn, sid, rseq); rseq++
			pt, err := dg.Open(buf[9:9], dn, buf[9:n], buf[:9])
			if err != nil { panic(err) }
			v := int64(binary.BigEndian.Uint64(pt[1:9])) // compact "received"
			f.mu.Lock(); if v > f.acked { f.acked = v }; f.cond.Broadcast(); f.mu.Unlock()
		}
	}()
	w := bufio.NewWriterSize(c, 64<<10) // okio-like segment buffering
	readBuf := make([]byte, fs)
	pooled := make([]byte, 14+9+1+17+fs+16)
	var sent, off int64
	for sent < total {
		n := int64(fs)
		if total-sent < n { n = total - sent }
		f.mu.Lock()
		for sent+n-f.acked > *window { f.cond.Wait() }
		f.mu.Unlock()
		so := off % int64(len(src)-fs)
		nonce(nb, sid, seq)
		if !opt {
			copy(readBuf, src[so:so+n])                 // 1 readFully into buf
			inner := make([]byte, 17+n)                 // 2 chunkFrame
			inner[0] = 1
			binary.BigEndian.PutUint64(inner[9:], uint64(off))
			copy(inner[17:], readBuf[:n])
			out := make([]byte, 9+1+len(inner)+16)      // 3 seal output
			out[0] = 2
			binary.BigEndian.PutUint64(out[1:], seq)
			out[9] = 1
			copy(out[10:], inner)
			g.Seal(out[:9], nb, out[9:10+len(inner)], out[:9])
			bs := make([]byte, len(out))                // 4 toByteString
			copy(bs, out)
			var k [4]byte
			rand.Read(k[:])
			masked := make([]byte, len(bs))             // 5 okio buffer + mask
			copy(masked, bs)
			mask(masked, k)
			w.Write(wsHeader(len(masked), true, k))
			w.Write(masked)
		} else {
			// v2 wire-compatible, zero alloc: read straight into the pooled frame, seal + mask in place.
			body := pooled[14:]
			body[0] = 2
			binary.BigEndian.PutUint64(body[1:], seq)
			body[9] = 1
			body[10] = 1
			binary.BigEndian.PutUint64(body[10+9:], uint64(off))
			copy(body[10+17:], src[so:so+n]) // = the file read
			pl := 1 + 17 + int(n)
			g.Seal(body[:9], nb, body[9:9+pl], body[:9])
			fl := 9 + pl + 16
			var k [4]byte
			binary.LittleEndian.PutUint32(k[:], uint32(seq*2654435761))
			mask(body[:fl], k)
			h := wsHeader(fl, true, k)
			w.Write(h)
			w.Write(body[:fl])
		}
		seq++
		sent += n
		off += n
	}
	w.Flush()
	f.mu.Lock()
	for f.acked < total { f.cond.Wait() }
	f.mu.Unlock()
}

func wsReceiver(c net.Conn, total int64, fs int, opt bool, sid uint32) {
	g := newGCM()
	r := bufio.NewReaderSize(c, 64<<10)
	var rseq, aseq uint64
	nb := make([]byte, 12)
	an := make([]byte, 12)
	var got, lastAck int64
	pool := make([]byte, 9+1+17+fs+16)
	ackBuf := make([]byte, 64)
	sendAck := func() {
		// sealed {"t":"xfer_ack",...} ~ 45 bytes JSON; we send 8-byte value + padding to similar size
		pl := make([]byte, 1+44)
		binary.BigEndian.PutUint64(pl[1:], uint64(got))
		ackBuf[0] = 2
		binary.BigEndian.PutUint64(ackBuf[1:], aseq)
		copy(ackBuf[9:], pl)
		nonce(an, sid, aseq); aseq++
		g2 := g
		_ = g2
		ct := ackG.Seal(ackBuf[:9], an, ackBuf[9:9+len(pl)], ackBuf[:9])
		var k [4]byte
		c.Write(append(wsHeader(len(ct), false, k), ct...))
		lastAck = got
	}
	_ = sendAck
	for got < total {
		n, k, masked, err := readWSHeader(r)
		if err != nil { panic(err) }
		var fr []byte
		if !opt {
			buf := make([]byte, n)                     // 1 okio messageFrameBuffer
			io.ReadFull(r, buf)
			if masked { mask(buf, k) }
			bs := make([]byte, n); copy(bs, buf)       // 2 readByteString
			fr = make([]byte, n); copy(fr, bs)         // 3 toByteArray
			nonce(nb, sid, rseq); rseq++
			pt, err := g.Open(nil, nb, fr[9:], fr[:9]) // 4 doFinal output
			if err != nil { panic(err) }
			payload := make([]byte, len(pt)-1)         // 5 copyOfRange
			copy(payload, pt[1:])
			got += int64(len(payload) - 17)
		} else {
			fr = pool[:n]
			io.ReadFull(r, fr)
			if masked { mask(fr, k) }
			nonce(nb, sid, rseq); rseq++
			pt, err := g.Open(fr[9:9], nb, fr[9:], fr[:9])
			if err != nil { panic(err) }
			got += int64(len(pt) - 1 - 17)
		}
		if got-lastAck >= 512<<10 || got >= total {
			sendAckWith(c, ackBuf, &aseq, sid, got)
			lastAck = got
		}
	}
}

var ackG = newGCM()

func sendAckWith(c net.Conn, ackBuf []byte, aseq *uint64, sid uint32, got int64) {
	an := make([]byte, 12)
	pl := make([]byte, 45)
	binary.BigEndian.PutUint64(pl[1:], uint64(got))
	ackBuf[0] = 2
	binary.BigEndian.PutUint64(ackBuf[1:], *aseq)
	nonce(an, sid, *aseq)
	*aseq++
	g := newGCMCached(sid)
	ct := g.Seal(ackBuf[:9], an, pl, ackBuf[:9])
	var k [4]byte
	c.Write(append(wsHeader(len(ct), false, k), ct...))
}

var gcmCache sync.Map

func newGCMCached(sid uint32) cipher.AEAD {
	if v, ok := gcmCache.Load(sid); ok { return v.(cipher.AEAD) }
	g := newGCM()
	gcmCache.Store(sid, g)
	return g
}

// ---------------- raw record stream ----------------

func rawSender(c net.Conn, total int64, rs int, sid uint32) {
	g := newGCM()
	nb := make([]byte, 12)
	buf := make([]byte, 4+rs+16)
	var seq uint64
	var sent int64
	for sent < total {
		n := int64(rs)
		if total-sent < n { n = total - sent }
		so := sent % int64(len(src)-rs)
		binary.BigEndian.PutUint32(buf, uint32(n+16))
		copy(buf[4:], src[so:so+n]) // = pread from file into the record buffer
		nonce(nb, sid, seq); seq++
		g.Seal(buf[4:4], nb, buf[4:4+n], buf[:4])
		if _, err := c.Write(buf[:4+n+16]); err != nil { panic(err) }
		sent += n
	}
	var fin [1]byte
	c.Read(fin[:]) // final ack
}

func rawReceiver(c net.Conn, total int64, rs int, sid uint32) {
	g := newGCM()
	nb := make([]byte, 12)
	r := bufio.NewReaderSize(c, 256<<10)
	buf := make([]byte, 4+rs+16)
	var seq uint64
	var got int64
	for got < total {
		if _, err := io.ReadFull(r, buf[:4]); err != nil { panic(err) }
		l := int(binary.BigEndian.Uint32(buf))
		io.ReadFull(r, buf[4:4+l])
		nonce(nb, sid, seq); seq++
		pt, err := g.Open(buf[4:4], nb, buf[4:4+l], buf[:4])
		if err != nil { panic(err) }
		got += int64(len(pt))
	}
	c.Write([]byte{1})
}

// ---------------- plain / tls ----------------

func plainSender(c net.Conn, total int64, fs int) {
	var sent int64
	for sent < total {
		n := int64(fs)
		if total-sent < n { n = total - sent }
		so := sent % int64(len(src)-fs)
		if _, err := c.Write(src[so : so+n]); err != nil { panic(err) }
		sent += n
	}
	var fin [1]byte
	c.Read(fin[:])
}

func plainReceiver(c net.Conn, total int64, fs int) {
	buf := make([]byte, fs)
	var got int64
	for got < total {
		n, err := c.Read(buf)
		if err != nil { panic(err) }
		got += int64(n)
	}
	c.Write([]byte{1})
}

func tlsConfig() *tls.Config {
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	t := &x509.Certificate{SerialNumber: big.NewInt(1), NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour)}
	der, _ := x509.CreateCertificate(rand.Reader, t, t, &k.PublicKey, k)
	return &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: k}},
		MinVersion: tls.VersionTLS13, CipherSuites: nil}
}

// ---------------- harness ----------------

func runOnce() (float64, float64) {
	total := int64(*totalMB) << 20
	per := total / int64(*streams)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil { panic(err) }
	defer ln.Close()
	var srvCfg *tls.Config
	if *mode == "tls" { srvCfg = tlsConfig() }
	var wg sync.WaitGroup
	var ready int32
	wg.Add(*streams * 2)
	go func() {
		for i := 0; i < *streams; i++ {
			c, err := ln.Accept()
			if err != nil { return }
			c.(*net.TCPConn).SetNoDelay(true)
			sid := uint32(i)
			go func(c net.Conn) {
				defer wg.Done()
				defer c.Close()
				switch *mode {
				case "plain": plainReceiver(c, per, *frame)
				case "ws": wsReceiver(c, per, *frame, false, sid)
				case "wsopt": wsReceiver(c, per, *frame, true, sid)
				case "raw": rawReceiver(c, per, *frame, sid)
				case "tls":
					tc := tls.Server(c, srvCfg)
					tc.Handshake()
					plainReceiver(tc, per, *frame)
				}
			}(c)
		}
	}()
	conns := make([]net.Conn, *streams)
	for i := range conns {
		c, err := net.Dial("tcp", ln.Addr().String())
		if err != nil { panic(err) }
		c.(*net.TCPConn).SetNoDelay(true)
		if *mode == "tls" {
			tc := tls.Client(c, &tls.Config{InsecureSkipVerify: true, MinVersion: tls.VersionTLS13})
			if err := tc.Handshake(); err != nil { panic(err) }
			c = tc
		}
		conns[i] = c
	}
	_ = ready
	cpu0 := cpuSeconds()
	t0 := time.Now()
	for i, c := range conns {
		go func(c net.Conn, sid uint32) {
			defer wg.Done()
			switch *mode {
			case "plain", "tls": plainSender(c, per, *frame)
			case "ws": wsSender(c, per, *frame, false, sid)
			case "wsopt": wsSender(c, per, *frame, true, sid)
			case "raw": rawSender(c, per, *frame, sid)
			}
			c.Close()
		}(c, uint32(i))
	}
	wg.Wait()
	el := time.Since(t0).Seconds()
	cpu := cpuSeconds() - cpu0
	mb := float64(total) / (1 << 20)
	return mb / el, cpu / (mb / 1024) // MiB/s, CPU-seconds per GiB (both ends)
}

func micro() {
	g := newGCM()
	nb := make([]byte, 12)
	for _, sz := range []int{16 << 10, 64 << 10, 256 << 10, 512 << 10, 1 << 20} {
		buf := make([]byte, sz+16)
		iters := (2 << 30) / sz
		t0 := time.Now()
		for i := 0; i < iters; i++ {
			nonce(nb, 0, uint64(i))
			g.Seal(buf[:0], nb, buf[:sz], nil)
		}
		fmt.Printf("AES-256-GCM seal %4d KiB records: %7.0f MiB/s (1 core)\n", sz>>10, 2048/time.Since(t0).Seconds())
	}
	buf := make([]byte, 1<<20)
	h := sha256.New()
	t0 := time.Now()
	for i := 0; i < 2048; i++ { h.Write(buf) }
	h.Sum(nil)
	fmt.Printf("SHA-256: %7.0f MiB/s (1 core)\n", 2048/time.Since(t0).Seconds())
	// copy bandwidth
	a, b := make([]byte, 512<<10), make([]byte, 512<<10)
	t0 = time.Now()
	for i := 0; i < 8192; i++ { copy(b, a) }
	fmt.Printf("memcpy 512 KiB: %7.0f MiB/s (1 core)\n", 4096/time.Since(t0).Seconds())
}

func main() {
	flag.Parse()
	src = make([]byte, 16<<20)
	rand.Read(src)
	rand.Read(key)
	if *mode == "micro" { micro(); return }
	var rates, cpus []float64
	for i := 0; i < *runs; i++ {
		r, c := runOnce()
		rates = append(rates, r)
		cpus = append(cpus, c)
	}
	sort.Float64s(rates)
	sort.Float64s(cpus)
	m := len(rates) / 2
	fmt.Fprintf(os.Stdout, "%-6s frame=%4dKiB streams=%d  median %7.0f MiB/s  (min %6.0f max %6.0f)  CPU %.2f s/GiB\n",
		*mode, *frame>>10, *streams, rates[m], rates[0], rates[len(rates)-1], cpus[m])
	_ = atomic.AddInt32
}
