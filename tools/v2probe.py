#!/usr/bin/env python3
"""AirBridge Protocol v2 reference probe.

A minimal, spec-exact peer used to test the real apps from a laptop. It can act as a
HOST (listen, show a pairing code, accept clients) or as a CLIENT (dial a host, pair,
then send clipboard/files/control messages interactively).

Requires: python3 >= 3.9, `websockets` (13.x).  No other dependencies.

  python3 tools/v2probe.py host [--port 47632] [--name Probe] [--code 123456]
  python3 tools/v2probe.py client ws://192.168.1.20:47632/v2 --code 123456
  python3 tools/v2probe.py client 'airbridge://pair?v=2&id=...&addr=...&port=...&k=...'

Interactive commands (client): clip <text> | send <path> | ping | enter <edge> <rel> |
  move <dx> <dy> | scroll <dx> <dy> | btn <b> <down|up> | click <b> [n] | key <hid> [text] |
  action <name> | leave | unpair | quit

State (device id + peer keys) is kept in ~/.airbridge-probe.json so re-connects use `auth`.
"""
import argparse
import asyncio
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import struct
import subprocess
import sys
import time
import uuid
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import websockets

STATE_PATH = Path(os.environ.get("AIRBRIDGE_PROBE_STATE", Path.home() / ".airbridge-probe.json"))
DOWNLOAD_DIR = Path.home() / "Downloads" / "AirBridge Transfers"
CHUNK = 512 * 1024
WINDOW = 8 * 1024 * 1024
PORT_BASE = 47632


# ----------------------------------------------------------------------------- helpers
def b64(b: bytes) -> str:
    return base64.b64encode(b).decode()


def unb64(s: str) -> bytes:
    return base64.b64decode(s + "=" * (-len(s) % 4))


def b64url_decode(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def hmac256(key: bytes, data: bytes) -> bytes:
    return hmac.new(key, data, hashlib.sha256).digest()


def derive_k(secret: bytes, client_id: str, host_id: str) -> bytes:
    return hmac256(secret, f"airbridge/v2/pair|{client_id}|{host_id}".encode())


def mac(k: bytes, tag: str, n1: bytes, n2: bytes, who: str) -> str:
    return b64(hmac256(k, tag.encode() + b"|" + n1 + b"|" + n2 + b"|" + who.encode()))


def load_state() -> dict:
    if STATE_PATH.exists():
        return json.loads(STATE_PATH.read_text())
    st = {"id": str(uuid.uuid4()), "peers": {}}
    STATE_PATH.write_text(json.dumps(st, indent=2))
    return st


def save_state(st: dict) -> None:
    STATE_PATH.write_text(json.dumps(st, indent=2))


def sanitize(name: str) -> str:
    name = name.replace("\\", "/").split("/")[-1]
    name = re.sub(r"[\x00-\x1f]", "", name).lstrip(". ")
    stem = name.split(".")[0].upper()
    if stem in {"CON", "PRN", "AUX", "NUL"} or re.fullmatch(r"(COM|LPT)[1-9]", stem):
        name = "_" + name
    return (name or "file")[:200]


def unique_path(d: Path, name: str) -> Path:
    p = d / name
    if not p.exists():
        return p
    stem, dot, ext = name.rpartition(".")
    if not dot:
        stem, ext = name, ""
    n = 2
    while True:
        cand = d / (f"{stem} ({n}).{ext}" if ext else f"{stem} ({n})")
        if not cand.exists():
            return cand
        n += 1


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ----------------------------------------------------------------------------- session
class Session:
    """One authenticated connection. Symmetric apart from who speaks first."""

    def __init__(self, ws, st: dict, name: str, is_host: bool, pairing=None):
        self.ws, self.st, self.name, self.is_host, self.pairing = ws, st, name, is_host, pairing
        self.my_id = st["id"]
        self.peer = None  # dict(id, name, plat, caps)
        self.k = None
        self.ready = False
        self.last_rx = time.time()
        self.xfers_in = {}
        self.xfer_acks = {}  # id -> (event, received)
        self.next_xfer_id = 1

    async def send(self, msg: dict):
        await self.ws.send(json.dumps(msg, separators=(",", ":")))

    def hello(self, nonce: bytes, paired=None):
        m = {"t": "hello", "v": 2, "id": self.my_id, "name": self.name,
             "plat": "linux", "app": "probe-0.1",
             "caps": ["clip", "xfer", "ctl_source", "ctl_target", "trackpad"],
             "nonce": b64(nonce), "screen": {"w": 1920, "h": 1080}}
        if paired is not None:
            m["paired"] = paired
        return m

    # -- handshake -----------------------------------------------------------
    async def run_client_handshake(self, code=None, qr_secret=None):
        c_nonce = secrets.token_bytes(16)
        await self.send(self.hello(c_nonce))
        h = json.loads(await asyncio.wait_for(self.ws.recv(), 10))
        assert h.get("t") == "hello" and h.get("v") == 2, h
        self.peer = h
        h_nonce = unb64(h["nonce"])
        peer_rec = self.st["peers"].get(h["id"])
        if h.get("paired") and peer_rec:
            self.k = unb64(peer_rec["k"])
            await self.send({"t": "auth", "mac": mac(self.k, "auth", c_nonce, h_nonce, self.my_id)})
            r = json.loads(await asyncio.wait_for(self.ws.recv(), 10))
            if r.get("t") != "auth_ok":
                log("auth failed:", r)
                if r.get("reason") in ("unpaired", "bad_mac"):
                    self.st["peers"].pop(h["id"], None)
                    save_state(self.st)
                return False
            exp = mac(self.k, "auth_ack", h_nonce, c_nonce, h["id"])
            if not hmac.compare_digest(exp, r["mac"]):
                log("host auth_ack MAC mismatch!")
                return False
        else:
            secret = qr_secret if qr_secret else (code or "").encode()
            if not secret:
                log("host does not know us and no --code/--qr given")
                return False
            self.k = derive_k(secret, self.my_id, h["id"])
            await self.send({"t": "pair", "proof": mac(self.k, "pair", c_nonce, h_nonce, self.my_id)})
            r = json.loads(await asyncio.wait_for(self.ws.recv(), 10))
            if r.get("t") != "pair_ok":
                log("pair failed:", r)
                return False
            exp = mac(self.k, "pair_ack", h_nonce, c_nonce, h["id"])
            if not hmac.compare_digest(exp, r["proof"]):
                log("host pair_ack proof mismatch!")
                return False
            self.st["peers"][h["id"]] = {"k": b64(self.k), "name": h["name"], "plat": h["plat"]}
            save_state(self.st)
            log("paired with", h["name"])
        self.ready = True
        log(f"READY with {h['name']} ({h['plat']}) caps={h.get('caps')}")
        return True

    async def run_host_handshake(self):
        h = json.loads(await asyncio.wait_for(self.ws.recv(), 10))
        if h.get("t") != "hello" or h.get("v") != 2:
            await self.send({"t": "auth_fail", "reason": "version"})
            return False
        self.peer = h
        c_nonce = unb64(h["nonce"])
        h_nonce = secrets.token_bytes(16)
        rec = self.st["peers"].get(h["id"])
        await self.send(self.hello(h_nonce, paired=bool(rec)))
        r = json.loads(await asyncio.wait_for(self.ws.recv(), 10))
        if r.get("t") == "auth":
            if not rec:
                await self.send({"t": "auth_fail", "reason": "unpaired"})
                return False
            self.k = unb64(rec["k"])
            if not hmac.compare_digest(mac(self.k, "auth", c_nonce, h_nonce, h["id"]), r.get("mac", "")):
                await self.send({"t": "auth_fail", "reason": "bad_mac"})
                return False
            await self.send({"t": "auth_ok", "mac": mac(self.k, "auth_ack", h_nonce, c_nonce, self.my_id)})
        elif r.get("t") == "pair":
            ok = False
            for secret in self.pairing.secrets():
                k = derive_k(secret, h["id"], self.my_id)
                if hmac.compare_digest(mac(k, "pair", c_nonce, h_nonce, h["id"]), r.get("proof", "")):
                    self.k, ok = k, True
                    break
            if not ok:
                self.pairing.fail()
                await self.send({"t": "pair_fail", "reason": "locked" if self.pairing.locked() else "bad_code"})
                return False
            self.st["peers"][h["id"]] = {"k": b64(self.k), "name": h["name"], "plat": h["plat"]}
            save_state(self.st)
            self.pairing.rotate()
            await self.send({"t": "pair_ok", "proof": mac(self.k, "pair_ack", h_nonce, c_nonce, self.my_id)})
            log("paired new client", h["name"])
        else:
            return False
        self.ready = True
        log(f"READY with {h['name']} ({h['plat']}) caps={h.get('caps')}")
        return True

    # -- steady state --------------------------------------------------------
    async def pinger(self):
        while True:
            await asyncio.sleep(5)
            await self.send({"t": "ping", "ts": int(time.time() * 1000)})
            if time.time() - self.last_rx > 15:
                log("peer silent for 15 s, closing")
                await self.ws.close()
                return

    async def reader(self):
        async for frame in self.ws:
            self.last_rx = time.time()
            if isinstance(frame, bytes):
                await self.on_binary(frame)
            else:
                try:
                    msg = json.loads(frame)
                except Exception:
                    log("bad json:", frame[:80])
                    continue
                await self.on_text(msg)

    async def on_text(self, m: dict):
        t = m.get("t")
        if t == "ping":
            await self.send({"t": "pong", "ts": m.get("ts")})
        elif t == "pong":
            log(f"pong rtt={int(time.time()*1000) - m.get('ts', 0)} ms")
        elif t == "clip":
            log(f"CLIP from {self.peer['name']}: {m.get('text')!r}")
            if sys.platform == "darwin":
                subprocess.run(["pbcopy"], input=m.get("text", "").encode())
        elif t == "xfer_offer":
            xid = m["id"]
            DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)
            files = {}
            for f in m["files"]:
                p = unique_path(DOWNLOAD_DIR, sanitize(f["name"]))
                files[f["i"]] = {"path": p, "size": f["size"], "fh": open(p, "wb"), "got": 0}
                if f["size"] == 0:
                    files[f["i"]]["fh"].close()
            self.xfers_in[xid] = {"files": files, "received": 0, "last_ack": 0, "total": m["total"]}
            log(f"xfer_offer {xid}: {len(files)} file(s), {m['total']} bytes → accept")
            await self.send({"t": "xfer_accept", "id": xid, "window": WINDOW})
        elif t == "xfer_done":
            x = self.xfers_in.pop(m["id"], None)
            if x:
                saved = []
                for f in x["files"].values():
                    if not f["fh"].closed:
                        f["fh"].close()
                    saved.append(f["path"].name)
                await self.send({"t": "xfer_ack", "id": m["id"], "received": x["received"]})
                await self.send({"t": "xfer_complete", "id": m["id"], "ok": True, "saved": saved})
                log(f"xfer {m['id']} complete: {saved}")
        elif t == "xfer_accept":
            self.xfer_acks.setdefault(m["id"], [asyncio.Event(), 0])
            self.xfer_acks[m["id"]][0].set()
        elif t == "xfer_ack":
            ev = self.xfer_acks.setdefault(m["id"], [asyncio.Event(), 0])
            ev[1] = m["received"]
            ev[0].set()
        elif t == "xfer_complete":
            log(f"peer completed xfer {m['id']}: ok={m.get('ok')} saved={m.get('saved')}")
        elif t in ("xfer_reject", "xfer_cancel"):
            log("xfer", t, m)
            x = self.xfers_in.pop(m["id"], None)
            if x:
                for f in x["files"].values():
                    f["fh"].close()
                    f["path"].unlink(missing_ok=True)
        elif t == "bye":
            log("peer says bye:", m.get("reason"))
        elif t == "unpair":
            log("peer unpaired us")
            self.st["peers"].pop(self.peer["id"], None)
            save_state(self.st)
        elif t in ("ctl_enter", "ctl_leave", "ptr_move", "ptr_scroll", "ptr_btn", "ptr_click",
                   "key", "key_state", "action", "name", "error"):
            log("CTL", json.dumps(m))
        else:
            log("ignored", t)

    async def on_binary(self, b: bytes):
        if len(b) < 17 or b[0] != 1:
            log("unknown binary kind", b[:1])
            return
        xid, idx, off = struct.unpack(">IIQ", b[1:17])
        x = self.xfers_in.get(xid)
        if not x or idx not in x["files"]:
            return
        f = x["files"][idx]
        f["fh"].seek(off)
        f["fh"].write(b[17:])
        f["got"] += len(b) - 17
        x["received"] += len(b) - 17
        done_file = f["got"] >= f["size"]
        if done_file and not f["fh"].closed:
            f["fh"].close()
        if done_file or x["received"] - x["last_ack"] >= 1024 * 1024:
            x["last_ack"] = x["received"]
            await self.send({"t": "xfer_ack", "id": xid, "received": x["received"]})

    # -- outgoing ------------------------------------------------------------
    async def send_clip(self, text: str):
        await self.send({"t": "clip", "kind": "text", "text": text, "ts": int(time.time() * 1000)})

    async def send_files(self, paths):
        paths = [Path(p) for p in paths if Path(p).is_file()]
        if not paths:
            log("no files")
            return
        xid, self.next_xfer_id = self.next_xfer_id, self.next_xfer_id + 1
        files = [{"i": i, "name": p.name, "size": p.stat().st_size} for i, p in enumerate(paths)]
        total = sum(f["size"] for f in files)
        ev = self.xfer_acks.setdefault(xid, [asyncio.Event(), 0])
        await self.send({"t": "xfer_offer", "id": xid, "files": files, "total": total})
        await asyncio.wait_for(ev[0].wait(), 15)  # accept
        sent, t0 = 0, time.time()
        for i, p in enumerate(paths):
            with open(p, "rb") as fh:
                off = 0
                while True:
                    data = fh.read(CHUNK)
                    if not data:
                        break
                    while sent - ev[1] > WINDOW - len(data):
                        ev[0].clear()
                        await asyncio.wait_for(ev[0].wait(), 30)
                    await self.ws.send(struct.pack(">BIIQ", 1, xid, i, off) + data)
                    off += len(data)
                    sent += len(data)
        await self.send({"t": "xfer_done", "id": xid})
        dt = max(time.time() - t0, 1e-6)
        log(f"sent {sent} bytes in {dt:.2f}s = {sent/dt/1048576:.1f} MiB/s (waiting for xfer_complete)")


# ----------------------------------------------------------------------------- pairing state (host)
class Pairing:
    def __init__(self, fixed_code=None):
        self.fixed = fixed_code
        self.fails, self.lock_until = 0, 0
        self.rotate()

    def rotate(self):
        self.code = self.fixed or f"{secrets.randbelow(10**6):06d}"
        self.secret = secrets.token_bytes(32)
        self.fails = 0

    def secrets(self):
        return [] if self.locked() else [self.secret, self.code.encode()]

    def fail(self):
        self.fails += 1
        if self.fails >= 5:
            self.lock_until = time.time() + 60
            self.rotate()

    def locked(self):
        return time.time() < self.lock_until

    def qr(self, host_id, name, addr, port):
        k = base64.urlsafe_b64encode(self.secret).decode().rstrip("=")
        return f"airbridge://pair?v=2&id={host_id}&name={name}&plat=linux&addr={addr}&port={port}&k={k}"


# ----------------------------------------------------------------------------- modes
async def run_host(args):
    st = load_state()
    pairing = Pairing(args.code)
    sessions = []

    async def handler(ws, path=None):
        req_path = getattr(ws, "path", path) or path
        if req_path != "/v2":
            await ws.close(1008, "path")
            return
        s = Session(ws, st, args.name, True, pairing)
        try:
            if not await s.run_host_handshake():
                await ws.close()
                return
            sessions.append(s)
            await asyncio.gather(s.reader(), s.pinger())
        except Exception as e:
            log("session ended:", type(e).__name__, e)
        finally:
            if s in sessions:
                sessions.remove(s)
            log("client gone", s.peer and s.peer.get("name"))

    port = args.port
    server = None
    for p in range(port, port + 10):
        try:
            server = await websockets.serve(handler, "0.0.0.0", p, max_size=2 * 1024 * 1024, compression=None)
            port = p
            break
        except OSError:
            continue
    if not server:
        sys.exit("no free port")
    dnssd = None
    if sys.platform == "darwin":
        dnssd = subprocess.Popen(["dns-sd", "-R", args.name, "_airbridge._tcp", "local.", str(port),
                                  "v=2", f"id={st['id']}", f"name={args.name}", "plat=linux"],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    log(f"HOST {args.name} id={st['id']} listening on :{port}/v2")
    log(f"pairing code: {pairing.code}")
    log("QR url:", pairing.qr(st["id"], args.name, args.addr or "0.0.0.0", port))
    log("commands: clip <text> | send <path> | code | peers | quit")
    loop = asyncio.get_event_loop()
    try:
        while True:
            line = await loop.run_in_executor(None, sys.stdin.readline)
            if not line:
                break
            cmd, _, rest = line.strip().partition(" ")
            if cmd == "quit":
                break
            if cmd == "code":
                log("pairing code:", pairing.code)
            elif cmd == "peers":
                log([s.peer["name"] for s in sessions])
            elif cmd == "clip":
                for s in sessions:
                    await s.send_clip(rest)
            elif cmd == "send":
                for s in sessions:
                    asyncio.create_task(s.send_files(rest.split()))
    finally:
        if dnssd:
            dnssd.terminate()
        server.close()


async def run_client(args):
    st = load_state()
    url, qr_secret = args.target, None
    if url.startswith("airbridge://"):
        q = parse_qs(urlparse(url).query)
        url = f"ws://{q['addr'][0]}:{q['port'][0]}/v2"
        qr_secret = b64url_decode(q["k"][0])
    if not url.endswith("/v2"):
        url = url.rstrip("/") + "/v2"
    backoff = 1
    while True:
        try:
            async with websockets.connect(url, max_size=2 * 1024 * 1024, compression=None) as ws:
                s = Session(ws, st, args.name, False)
                if not await s.run_client_handshake(args.code, qr_secret):
                    return
                backoff = 1
                tasks = [asyncio.create_task(s.reader()), asyncio.create_task(s.pinger())]
                loop = asyncio.get_event_loop()
                while not ws.closed:
                    line = await loop.run_in_executor(None, sys.stdin.readline)
                    if not line:
                        return
                    cmd, _, rest = line.strip().partition(" ")
                    a = rest.split()
                    try:
                        if cmd == "quit":
                            await s.send({"t": "bye", "reason": "user"})
                            return
                        elif cmd == "clip":
                            await s.send_clip(rest)
                        elif cmd == "send":
                            asyncio.create_task(s.send_files(a))
                        elif cmd == "ping":
                            await s.send({"t": "ping", "ts": int(time.time() * 1000)})
                        elif cmd == "enter":
                            await s.send({"t": "ctl_enter", "edge": a[0], "rel": float(a[1])})
                        elif cmd == "leave":
                            await s.send({"t": "ctl_leave"})
                        elif cmd == "move":
                            await s.send({"t": "ptr_move", "dx": float(a[0]), "dy": float(a[1])})
                        elif cmd == "scroll":
                            await s.send({"t": "ptr_scroll", "dx": float(a[0]), "dy": float(a[1]), "px": True})
                        elif cmd == "btn":
                            await s.send({"t": "ptr_btn", "b": int(a[0]), "down": a[1] == "down"})
                        elif cmd == "click":
                            await s.send({"t": "ptr_click", "b": int(a[0]), "n": int(a[1]) if len(a) > 1 else 1})
                        elif cmd == "key":
                            hid = int(a[0], 0)
                            m = {"t": "key", "hid": hid, "down": True, "mods": 0}
                            if len(a) > 1:
                                m["text"] = a[1]
                            await s.send(m)
                            await s.send({"t": "key", "hid": hid, "down": False, "mods": 0})
                        elif cmd == "action":
                            await s.send({"t": "action", "name": a[0]})
                        elif cmd == "unpair":
                            await s.send({"t": "unpair"})
                            st["peers"].pop(s.peer["id"], None)
                            save_state(st)
                        else:
                            log("?")
                    except Exception as e:
                        log("cmd error:", e)
                for t in tasks:
                    t.cancel()
        except (OSError, websockets.exceptions.WebSocketException, asyncio.TimeoutError) as e:
            log(f"connection failed ({type(e).__name__}: {e}); retry in {backoff}s")
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 30)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="mode", required=True)
    h = sub.add_parser("host")
    h.add_argument("--port", type=int, default=PORT_BASE)
    h.add_argument("--name", default="Probe Host")
    h.add_argument("--code", help="fixed 6-digit pairing code (default: random)")
    h.add_argument("--addr", help="IP to put in the QR url")
    c = sub.add_parser("client")
    c.add_argument("target", help="ws://host:port[/v2] or an airbridge://pair?... url")
    c.add_argument("--name", default="Probe Client")
    c.add_argument("--code", help="6-digit code shown on the host (first pairing only)")
    args = ap.parse_args()
    try:
        asyncio.run(run_host(args) if args.mode == "host" else run_client(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
