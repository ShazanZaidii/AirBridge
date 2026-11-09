#!/usr/bin/env python3
"""Live check of an AirWeb server (the Android app's or the Mac app's) over real sockets.

    tools/airwebcheck/check.py 'http://127.0.0.1:47650/?t=TOKEN' [--browsers 60] [--slow]

Checks the shared pool (what one browser uploads another downloads, byte for byte), who is connected, the
"nothing changed" poll, the invite QR, and then a crowd: many browsers polling while some upload and some download,
with the poll latency measured. `--slow` also opens silent and dribbling connections to prove the server drops them.
Uses only the standard library. Files it uploads are named `airweb-check-*.bin` (delete them afterwards).
"""
import hashlib
import http.client
import json
import os
import random
import re
import socket
import sys
import threading
import time
import urllib.parse

URL = None
HOST = PORT = TOKEN = None
FAILS = []


def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what)
    if not ok:
        FAILS.append(what)


def request(method, path, body=None, headers=None, timeout=60):
    """One request on its own connection. Returns (status, headers, body) or (-1, {}, b'') on a network error."""
    sep = "&" if "?" in path else "?"
    try:
        c = http.client.HTTPConnection(HOST, PORT, timeout=timeout)
        h = {"Connection": "close"}
        h.update(headers or {})
        c.request(method, f"{path}{sep}t={TOKEN}", body=body, headers=h)
        r = c.getresponse()
        data = r.read()
        hdrs = {k.lower(): v for k, v in r.getheaders()}
        c.close()
        return r.status, hdrs, data
    except (OSError, http.client.HTTPException):
        return -1, {}, b""


def state(client=None, label=None, v=None):
    q = "/api/state?x=1"
    if client: q += "&c=" + urllib.parse.quote(client)
    if label: q += "&w=" + urllib.parse.quote(label)
    if v: q += "&v=" + urllib.parse.quote(v)
    st, _, body = request("GET", q)
    return st, (json.loads(body) if st == 200 else {})


def upload(client, label, name, data):
    q = f"/u?n={urllib.parse.quote(name)}&c={urllib.parse.quote(client)}&w={urllib.parse.quote(label)}"
    for attempt in range(25):
        st, h, body = request("POST", q, body=data, headers={"Content-Type": "application/octet-stream"})
        if st in (200, 507): return st, body
        time.sleep(0.4 + 0.2 * attempt)  # busy (503) or reset: the page retries the same way
    return st, b""


def main():
    global URL, HOST, PORT, TOKEN
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__); sys.exit(2)
    URL = args[0]
    u = urllib.parse.urlparse(URL)
    HOST, PORT = u.hostname, u.port
    TOKEN = urllib.parse.parse_qs(u.query)["t"][0]
    browsers = int(sys.argv[sys.argv.index("--browsers") + 1]) if "--browsers" in sys.argv else 60
    run = "%06x" % random.randrange(1 << 24)

    print("page, qr, token")
    st, _, body = request("GET", "/")
    check(st == 200 and b"AirWeb" in body, "page loads")
    st, h, body = request("GET", "/qr.svg", headers={"Host": f"{HOST}:{PORT}"})
    check(st == 200 and body.startswith(b"<svg") and h.get("content-type", "").startswith("image/svg+xml"), "invite QR is an SVG")
    c = http.client.HTTPConnection(HOST, PORT, timeout=10); c.request("GET", "/api/state"); r = c.getresponse(); r.read(); c.close()
    check(r.status == 403, "everything needs the token")

    print("the shared pool")
    a, b = f"checkA{run}", f"checkB{run}"
    data = os.urandom(400_000)
    name = f"airweb-check-{run}.bin"
    st, _ = upload(a, "Chrome on Windows", name, data)
    check(st == 200, "browser A uploads a file")
    st, sb = state(b, "Safari on iPhone")
    f = next((x for x in sb.get("files", []) if x["name"].startswith(f"airweb-check-{run}")), None)
    check(f is not None and f.get("by") == "Chrome on Windows" and not f.get("own"), "browser B sees it, from A's device")
    _, sa = state(a, "Chrome on Windows")
    fa = next((x for x in sa.get("files", []) if x["name"].startswith(f"airweb-check-{run}")), None)
    check(fa is not None and fa.get("own") is True, "browser A sees it marked as theirs")
    if f:
        st, _, got = request("GET", f"/d/{f['id']}/{urllib.parse.quote(f['name'])}")
        check(st == 200 and hashlib.sha256(got).digest() == hashlib.sha256(data).digest(), "browser B downloads the same bytes")

    print("who is connected")
    _, s1 = state(b, "Safari on iPhone")
    names = [p["name"] for p in s1.get("peers", [])]
    check(s1.get("count", 0) >= 2 and "Safari on iPhone" in names, f"both browsers are listed ({names})")
    d1, d2 = f"checkC{run}", f"checkD{run}"
    state(d1, "Firefox on Linux"); _, s2 = state(d2, "Firefox on Linux")
    labels = [p["name"] for p in s2.get("peers", []) if p["name"].startswith("Firefox on Linux")]
    check(sorted(labels) == ["Firefox on Linux 1", "Firefox on Linux 2"], f"two of the same kind get numbers ({labels})")

    print("an unchanged poll is tiny")
    ver = sb.get("v")
    st, _, body = request("GET", f"/api/state?c={b}&w=x&v={ver}")
    j = json.loads(body) if st == 200 else {}
    check(j.get("same") is True and "files" not in j and len(body) < 600, f"answered {len(body)} bytes with same:true")

    if "--slow" in sys.argv:
        print("slow and silent connections (from one address, so under the per-address cap)")
        silent = [socket.create_connection((HOST, PORT)) for _ in range(18)]
        drib = [socket.create_connection((HOST, PORT)) for _ in range(3)]
        stop = threading.Event()
        def dribble():
            while not stop.is_set():
                for s in drib:
                    try: s.send(b"G")
                    except OSError: pass
                time.sleep(0.3)
        threading.Thread(target=dribble, daemon=True).start()
        t0 = time.time(); st, _ = state(b, "Safari on iPhone"); dt = time.time() - t0
        check(st == 200 and dt < 2, f"a real request is still answered in {dt*1000:.0f} ms with 21 dead connections open")
        deadline = time.time() + 25
        closed = 0
        for s in silent + drib:
            s.settimeout(max(0.5, deadline - time.time()))
            try:
                if s.recv(1) == b"": closed += 1
            except socket.timeout: pass
            except OSError: closed += 1
        stop.set()
        check(closed == len(silent) + len(drib), f"all 21 are dropped by the server ({closed} closed)")
        for s in silent + drib:
            try: s.close()
            except OSError: pass

        print("one address can't take every connection")
        held = []
        for _ in range(40):
            try: held.append(socket.create_connection((HOST, PORT)))
            except OSError: break
            time.sleep(0.01)
        time.sleep(0.8)
        turned_away = 0
        for s in held:
            s.settimeout(0.3)
            try:
                if s.recv(64).startswith(b"HTTP/1.1 503"): turned_away += 1
            except (socket.timeout, OSError): pass
        check(turned_away >= 5, f"{turned_away} of 40 simultaneous connections from one address were answered 503, not held")
        for s in held:
            try: s.close()
            except OSError: pass
        time.sleep(1)
        st, _ = state(b, "Safari on iPhone")
        check(st == 200, "and the server is fine once they close")

    print(f"a crowd: {browsers} browsers polling, 8 uploading, 8 downloading")
    pool = [x for x in state(b)[1].get("files", []) if x["name"].startswith(f"airweb-check-{run}")]
    dl_id = pool[0]["id"] if pool else None
    lat, bad, busy = [], [], [0]
    done = threading.Barrier(1)
    lock = threading.Lock()
    up_data = os.urandom(1_000_000)

    def browser(i):
        me = f"crowd{i:03d}{run}"
        v = None
        for _ in range(8):
            # Like the page: a busy answer means "try again soon" (all 60 browsers here share one address, so the
            # per-address limit makes this machine's polls queue up; real devices each have their own address).
            for attempt in range(14):
                t0 = time.time()
                q = f"/api/state?c={me}&w={urllib.parse.quote('Chrome on Windows')}" + (f"&v={v}" if v else "")
                st, _, body = request("GET", q, timeout=10)
                if st == 200:
                    with lock: lat.append((time.time() - t0) * 1000)
                    j = json.loads(body)
                    if not j.get("same"): v = j.get("v")
                    break
                with lock: busy[0] += 1
                time.sleep(0.4)
            else:
                with lock: bad.append(f"poll {i}")
            time.sleep(0.2 + (i % 5) * 0.03)

    def uploader(i):
        st, _ = upload(f"crowdU{i:02d}{run}", "Chrome on Android", f"airweb-check-{run}-u{i}.bin", up_data)
        if st != 200:
            with lock: bad.append(f"upload {i} -> {st}")

    def downloader(i):
        for attempt in range(25):
            st, _, got = request("GET", f"/d/{dl_id}")
            if st == 200 and len(got) == len(data): return
            time.sleep(0.4)
        with lock: bad.append(f"download {i}")

    ts = [threading.Thread(target=browser, args=(i,)) for i in range(browsers)]
    ts += [threading.Thread(target=uploader, args=(i,)) for i in range(8)]
    ts += [threading.Thread(target=downloader, args=(i,)) for i in range(8)]
    t0 = time.time()
    for t in ts: t.start()
    for t in ts: t.join(180)
    lat.sort()
    p50 = lat[len(lat) // 2] if lat else -1
    p95 = lat[min(len(lat) - 1, len(lat) * 95 // 100)] if lat else -1
    check(not bad, f"every poll, upload and download finished ({len(bad)} problems {bad[:3]})")
    check(0 < p95 < 1500, f"poll latency p50 {p50:.0f} ms, p95 {p95:.0f} ms over {len(lat)} polls ({busy[0]} busy answers)")
    _, sf = state(b)
    ups = [x for x in sf.get("files", []) if re.match(rf"airweb-check-{run}-u\d", x["name"])]
    check(len(ups) == 8, f"all 8 uploads are in the pool ({len(ups)})")
    check(sf.get("count", 0) >= browsers, f"{sf.get('count')} devices listed as connected")
    st, _ = state(b)
    check(st == 200, f"the server still answers after {time.time() - t0:.0f} s of crowd")

    print()
    print("ALL OK" if not FAILS else f"{len(FAILS)} FAILED")
    sys.exit(0 if not FAILS else 1)


if __name__ == "__main__":
    main()
