#!/usr/bin/env python3
"""AirBridge Protocol v2 (rev 2) reference probe.

A minimal, spec-exact peer used to test the real apps from a laptop. It can act as a
HOST (listen, accept clients, show pairing digits) or as a CLIENT (dial a host, pair, then
send clipboard/files/control messages interactively). Everything after the handshake is
encrypted exactly as docs/PROTOCOL.md §3.6 describes.

Requires: python3 >= 3.9, `websockets` (>= 10) and `cryptography`.
  (e.g.  uv venv .venv && uv pip install --python .venv/bin/python websockets cryptography)

  python3 tools/v2probe.py host [--port 47632] [--name Probe] [--auto-accept]
  python3 tools/v2probe.py client ws://192.168.1.20:47632          # confirm-mode pairing (shows digits)
  python3 tools/v2probe.py client 'airbridge://pair?v=2&id=...'    # QR-mode pairing

Interactive commands (client): clip <text> | send <path> | ping | enter <edge> <rel> |
  move <dx> <dy> | scroll <dx> <dy> | btn <b> <down|up> | click <b> [n] | abs <x> <y> |
  key <hid> [text] | kd|ku <hid> [mods] | action <name> | mirror [w h fps] | mirror_stop | leave | unpair | quit

State (device id + peer keys) is kept in ~/.airbridge-probe.json (override: AIRBRIDGE_PROBE_STATE).
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
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

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


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(ikm)


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
    name = re.sub(r'[\x00-\x1f<>:"|?*]', "", name).strip(". ")
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


def is_open(ws) -> bool:
    if hasattr(ws, "closed"):          # websockets <= 13 legacy API
        return not ws.closed
    return ws.close_code is None       # websockets >= 14


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def pk_bytes(priv: ec.EllipticCurvePrivateKey) -> bytes:
    return priv.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)


def transcript(c_nonce, h_nonce, client_id, host_id, c_pk, h_pk) -> bytes:
    return c_nonce + b"|" + h_nonce + b"|" + client_id.encode() + b"|" + host_id.encode() + b"|" + c_pk + b"|" + h_pk


# ----------------------------------------------------------------------------- session
class Session:
    """One authenticated, encrypted connection. Symmetric apart from who speaks first."""

    def __init__(self, ws, st: dict, name: str, is_host: bool, pairing=None, auto_accept=False):
        self.ws, self.st, self.name, self.is_host, self.pairing = ws, st, name, is_host, pairing
        self.auto_accept = auto_accept
        self.my_id = st["id"]
        self.peer = None
        self.k = None
        self.ready = False
        self.last_rx = time.time()
        self.xfers_in = {}
        self.xfer_acks = {}
        self.next_xfer_id = secrets.randbelow(1 << 20) + 1
        # crypto
        self.priv = ec.generate_private_key(ec.SECP256R1())
        self.tx_key = self.rx_key = None
        self.tx_seq = self.rx_seq = 0
        self.mirror_frames = 0
        self.mirror_t0 = None

    # -- wire ---------------------------------------------------------------
    async def send_plain(self, msg: dict):
        await self.ws.send(json.dumps(msg, separators=(",", ":")))

    async def send(self, msg: dict):
        if not self.ready:
            await self.send_plain(msg)
            return
        await self.send_inner(0, json.dumps(msg, separators=(",", ":")).encode())

    async def send_inner(self, inner_kind: int, payload: bytes):
        header = b"\x02" + struct.pack(">Q", self.tx_seq)
        nonce = b"\x00" * 4 + struct.pack(">Q", self.tx_seq)
        self.tx_seq += 1
        ct = AESGCM(self.tx_key).encrypt(nonce, bytes([inner_kind]) + payload, header)
        await self.ws.send(header + ct)

    def open_envelope(self, frame: bytes):
        if len(frame) < 9 + 16 or frame[0] != 2:
            raise ValueError("not an envelope")
        seq = struct.unpack(">Q", frame[1:9])[0]
        if seq != self.rx_seq:
            raise ValueError(f"bad seq {seq} != {self.rx_seq}")
        self.rx_seq += 1
        nonce = b"\x00" * 4 + frame[1:9]
        pt = AESGCM(self.rx_key).decrypt(nonce, frame[9:], frame[:9])
        return pt[0], pt[1:]

    def derive_session_keys(self, z: bytes, salt: bytes, T: bytes, client_id: str, host_id: str):
        keys = hkdf(z, salt, b"airbridge/v2/session|" + T, 64)
        c2h, h2c = keys[:32], keys[32:]
        self.tx_key, self.rx_key = (h2c, c2h) if self.is_host else (c2h, h2c)

    def hello(self, nonce: bytes, paired=None):
        m = {"t": "hello", "v": 2, "id": self.my_id, "name": self.name,
             "plat": "linux", "app": "probe-0.2",
             "caps": ["clip", "xfer", "ctl_source", "ctl_target", "trackpad", "mirror_sink"],
             "nonce": b64(nonce), "pk": b64(pk_bytes(self.priv)), "screen": {"w": 1920, "h": 1080}}
        if paired is not None:
            m["paired"] = paired
        return m

    @staticmethod
    def confirm_digits(z: bytes, T: bytes) -> str:
        v = struct.unpack(">I", hmac256(z, b"airbridge/v2/confirm|" + T)[:4])[0] % 1_000_000
        return f"{v:06d}"

    # -- handshake -----------------------------------------------------------
    async def run_client_handshake(self, qr_secret=None):
        c_nonce = secrets.token_bytes(16)
        await self.send_plain(self.hello(c_nonce))
        h = json.loads(await asyncio.wait_for(self.ws.recv(), 10))
        assert h.get("t") == "hello" and h.get("v") == 2, h
        self.peer = h
        h_nonce = unb64(h["nonce"])
        h_pk = unb64(h["pk"])
        z = self.priv.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), h_pk))
        T = transcript(c_nonce, h_nonce, self.my_id, h["id"], pk_bytes(self.priv), h_pk)
        peer_rec = self.st["peers"].get(h["id"])
        if h.get("paired") and peer_rec:
            self.k = unb64(peer_rec["k"])
            await self.send_plain({"t": "auth", "mac": b64(hmac256(self.k, b"auth|" + T))})
            r = json.loads(await asyncio.wait_for(self.ws.recv(), 10))
            if r.get("t") != "auth_ok":
                log("auth failed:", r)
                if r.get("reason") in ("unpaired", "bad_mac"):
                    self.st["peers"].pop(h["id"], None)
                    save_state(self.st)
                return False
            if not hmac.compare_digest(b64(hmac256(self.k, b"auth_ack|" + T)), r["mac"]):
                log("host auth_ack MAC mismatch!")
                return False
        else:
            if qr_secret:
                self.k = hmac256(qr_secret, f"airbridge/v2/pair|{self.my_id}|{h['id']}".encode())
                mode = "qr"
            else:
                self.k = hkdf(z, c_nonce + h_nonce, f"airbridge/v2/pairkey|{self.my_id}|{h['id']}".encode(), 32)
                mode = "confirm"
                await self.send_plain({"t": "pair_start", "mode": "confirm"})
                log(f"PAIRING DIGITS: {self.confirm_digits(z, T)}  (confirm on the host; the probe auto-confirms)")
            await self.send_plain({"t": "pair", "mode": mode, "proof": b64(hmac256(self.k, b"pair|" + T))})
            r = json.loads(await asyncio.wait_for(self.ws.recv(), 70))
            if r.get("t") != "pair_ok":
                log("pair failed:", r)
                return False
            if not hmac.compare_digest(b64(hmac256(self.k, b"pair_ack|" + T)), r["proof"]):
                log("host pair_ack proof mismatch!")
                return False
            self.st["peers"][h["id"]] = {"k": b64(self.k), "name": h["name"], "plat": h["plat"]}
            save_state(self.st)
            log("paired with", h["name"])
        self.derive_session_keys(z, c_nonce + h_nonce, T, self.my_id, h["id"])
        self.priv = None
        self.ready = True
        log(f"READY (encrypted) with {h['name']} ({h['plat']}) caps={h.get('caps')}")
        return True

    async def run_host_handshake(self):
        try:
            return await self._run_host_handshake()
        except Exception as e:
            log(f"handshake aborted: {type(e).__name__}: {e}")
            return False

    async def _run_host_handshake(self):
        h = json.loads(await asyncio.wait_for(self.ws.recv(), 10))
        log("hello from", h.get("name"), h.get("plat"), "caps=", h.get("caps"), "pk" in h and "pk-ok")
        if h.get("t") != "hello" or h.get("v") != 2:
            await self.send_plain({"t": "auth_fail", "reason": "version"})
            return False
        self.peer = h
        c_nonce = unb64(h["nonce"])
        c_pk = unb64(h["pk"])
        h_nonce = secrets.token_bytes(16)
        rec = self.st["peers"].get(h["id"])
        await self.send_plain(self.hello(h_nonce, paired=bool(rec)))
        z = self.priv.exchange(ec.ECDH(), ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), c_pk))
        T = transcript(c_nonce, h_nonce, h["id"], self.my_id, c_pk, pk_bytes(self.priv))
        r = json.loads(await asyncio.wait_for(self.ws.recv(), 10))
        if r.get("t") == "pair_start":
            digits = self.confirm_digits(z, T)
            log(f"PAIR REQUEST from {h['name']}: digits {digits} — {'auto-accepting' if self.auto_accept else 'type: accept'}")
            r = json.loads(await asyncio.wait_for(self.ws.recv(), 70))
        if r.get("t") == "auth":
            if not rec:
                await self.send_plain({"t": "auth_fail", "reason": "unpaired"})
                return False
            self.k = unb64(rec["k"])
            if not hmac.compare_digest(b64(hmac256(self.k, b"auth|" + T)), r.get("mac", "")):
                await self.send_plain({"t": "auth_fail", "reason": "bad_mac"})
                return False
            await self.send_plain({"t": "auth_ok", "mac": b64(hmac256(self.k, b"auth_ack|" + T))})
        elif r.get("t") == "pair":
            mode = r.get("mode", "confirm")
            log("pair request mode=", mode)
            if mode == "qr":
                cands = [hmac256(s, f"airbridge/v2/pair|{h['id']}|{self.my_id}".encode()) for s in self.pairing.secrets()]
            else:
                cands = [hkdf(z, c_nonce + h_nonce, f"airbridge/v2/pairkey|{h['id']}|{self.my_id}".encode(), 32)]
            ok = next((k for k in cands if hmac.compare_digest(b64(hmac256(k, b"pair|" + T)), r.get("proof", ""))), None)
            if ok is None:
                log("pair proof did not verify (mode", mode, ")")
                self.pairing.fail()
                await self.send_plain({"t": "pair_fail", "reason": "locked" if self.pairing.locked() else "bad_proof"})
                return False
            if mode == "confirm" and not self.auto_accept:
                log("waiting for 'accept' on stdin (or 'reject')…")
                ans = await asyncio.wait_for(self.pairing.answer(), 60)
                if ans != "accept":
                    await self.send_plain({"t": "pair_fail", "reason": "rejected"})
                    return False
            self.k = ok
            self.st["peers"][h["id"]] = {"k": b64(self.k), "name": h["name"], "plat": h["plat"]}
            save_state(self.st)
            self.pairing.rotate()
            await self.send_plain({"t": "pair_ok", "proof": b64(hmac256(self.k, b"pair_ack|" + T))})
            log("paired new client", h["name"])
        else:
            log("unexpected handshake message:", r)
            return False
        self.derive_session_keys(z, c_nonce + h_nonce, T, h["id"], self.my_id)
        self.priv = None
        self.ready = True
        log(f"READY (encrypted) with {h['name']} ({h['plat']}) caps={h.get('caps')}")
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
        try:
            async for frame in self.ws:
                self.last_rx = time.time()
                if isinstance(frame, str):
                    log("PROTOCOL ERROR: plaintext text frame after ready:", frame[:80])
                    continue
                try:
                    kind, payload = self.open_envelope(frame)
                except Exception as e:
                    log("envelope error:", e)
                    await self.ws.close(1002, "envelope")
                    return
                if kind == 0:
                    try:
                        msg = json.loads(payload)
                    except Exception:
                        log("bad json:", payload[:80])
                        continue
                    await self.on_text(msg)
                elif kind == 1:
                    await self.on_binary(payload)
        except websockets.exceptions.ConnectionClosed as e:
            log(f"connection closed ({getattr(e, 'code', e)})")

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
            if m.get("dir") != "in":
                x = self.xfers_in.pop(m["id"], None)
                if x:
                    for f in x["files"].values():
                        f["fh"].close()
                        f["path"].unlink(missing_ok=True)
        elif t == "mirror_info":
            self.mirror_frames, self.mirror_t0 = 0, time.time()
            log("MIRROR", json.dumps(m))
        elif t == "bye":
            log("peer says bye:", m.get("reason"))
        elif t == "unpair":
            log("peer unpaired us")
            self.st["peers"].pop(self.peer["id"], None)
            save_state(self.st)
        elif t in ("ctl_enter", "ctl_leave", "ctl_busy", "ptr_move", "ptr_scroll", "ptr_btn", "ptr_click",
                   "ptr_abs", "key", "key_state", "action", "name", "error", "mirror_stop", "mirror_key",
                   "mirror_ack"):
            log("CTL", json.dumps(m))
        else:
            log("ignored", t)

    async def on_binary(self, b: bytes):
        if not b:
            return
        if b[0] == 3 and len(b) >= 10:
            pts, flags = struct.unpack(">QB", b[1:10])
            self.mirror_frames += 1
            if self.mirror_frames % 10 == 0:
                await self.send({"t": "mirror_ack", "pts": pts})
            if flags & 1 or self.mirror_frames % 60 == 1:
                el = max(time.time() - (self.mirror_t0 or time.time()), 1e-6)
                log(f"video frame #{self.mirror_frames} pts={pts} flags={flags} size={len(b)-10} ({self.mirror_frames/el:.1f} fps)")
            return
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
        await asyncio.wait_for(ev[0].wait(), 15)
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
                    await self.send_inner(1, struct.pack(">BIIQ", 1, xid, i, off) + data)
                    off += len(data)
                    sent += len(data)
        await self.send({"t": "xfer_done", "id": xid})
        dt = max(time.time() - t0, 1e-6)
        log(f"sent {sent} bytes in {dt:.2f}s = {sent/dt/1048576:.1f} MiB/s (waiting for xfer_complete)")


# ----------------------------------------------------------------------------- pairing state (host)
class Pairing:
    def __init__(self):
        self.fails, self.lock_until = 0, 0
        self.answers = asyncio.Queue()
        self.rotate()

    def rotate(self):
        self.secret = secrets.token_bytes(32)
        self.fails = 0

    def secrets(self):
        return [] if self.locked() else [self.secret]

    def fail(self):
        self.fails += 1
        if self.fails >= 5:
            self.lock_until = time.time() + 60
            self.rotate()

    def locked(self):
        return time.time() < self.lock_until

    async def answer(self):
        return await self.answers.get()

    def qr(self, host_id, name, addr, port):
        k = base64.urlsafe_b64encode(self.secret).decode().rstrip("=")
        return f"airbridge://pair?v=2&id={host_id}&name={name}&plat=linux&addr={addr}&port={port}&k={k}"


async def run_command(s: "Session", st: dict, cmd: str, rest: str) -> bool:
    """Control/mirror commands shared by host and client modes. Returns False if `cmd` is unknown."""
    a = rest.split()
    if cmd == "ping":
        await s.send({"t": "ping", "ts": int(time.time() * 1000)})
    elif cmd == "enter":
        await s.send({"t": "ctl_enter", "edge": a[0], "rel": float(a[1])})
    elif cmd == "leave":
        await s.send({"t": "ctl_leave"})
    elif cmd == "move":
        await s.send({"t": "ptr_move", "dx": float(a[0]), "dy": float(a[1])})
    elif cmd == "abs":
        await s.send({"t": "ptr_abs", "x": float(a[0]), "y": float(a[1])})
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
    elif cmd in ("kd", "ku"):  # raw key down / up: kd <hid> [mods]  (hold modifiers like a real keyboard)
        await s.send({"t": "key", "hid": int(a[0], 0), "down": cmd == "kd", "mods": int(a[1], 0) if len(a) > 1 else 0})
    elif cmd == "action":
        await s.send({"t": "action", "name": a[0]})
    elif cmd == "mirror":
        w, h, fps = (int(a[0]), int(a[1]), int(a[2])) if len(a) >= 3 else (1280, 800, 30)
        await s.send({"t": "mirror_start", "codecs": ["h264", "hevc"], "maxW": w, "maxH": h, "fps": fps, "bitrate": 6_000_000})
    elif cmd == "mirror_stop":
        await s.send({"t": "mirror_stop"})
    else:
        return False
    return True


# ----------------------------------------------------------------------------- modes
async def run_host(args):
    st = load_state()
    pairing = Pairing()
    sessions = []

    async def handler(ws, path=None):
        req_path = getattr(ws, "path", None) or getattr(getattr(ws, "request", None), "path", None) or path
        if req_path != "/v2":
            await ws.close(1008, "path")
            return
        s = Session(ws, st, args.name, True, pairing, auto_accept=args.auto_accept)
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
    if sys.platform == "darwin" and args.bonjour:
        import atexit, signal
        dnssd = subprocess.Popen(["dns-sd", "-R", args.name, "_airbridge._tcp", "local.", str(port),
                                  "v=2", f"id={st['id']}", f"name={args.name}", "plat=linux"],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        atexit.register(dnssd.terminate)
        for _sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            signal.signal(_sig, lambda *_: (dnssd.terminate(), os._exit(0)))
    log(f"HOST {args.name} id={st['id']} listening on :{port}/v2")
    log("QR url:", pairing.qr(st["id"], args.name, args.addr or "0.0.0.0", port))
    log("commands: accept | reject | qr | clip <text> | send <path> | peers | quit   (the QR secret rotates after each pairing; use `qr` to print the current one)")
    loop = asyncio.get_event_loop()
    try:
        while True:
            line = await loop.run_in_executor(None, sys.stdin.readline)
            if not line:
                break
            cmd, _, rest = line.strip().partition(" ")
            if cmd == "quit":
                break
            if cmd in ("accept", "reject"):
                pairing.answers.put_nowait(cmd)
            elif cmd == "peers":
                log([s.peer["name"] for s in sessions])
            elif cmd == "qr":
                log("QR url:", pairing.qr(st["id"], args.name, args.addr or "0.0.0.0", port))
            elif cmd == "clip":
                for s in sessions:
                    await s.send_clip(rest)
            elif cmd == "send":
                for s in sessions:
                    asyncio.create_task(s.send_files(rest.split()))
            else:
                for s in sessions:
                    if not await run_command(s, st, cmd, rest):
                        log("?")
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
                if not await s.run_client_handshake(qr_secret):
                    return
                backoff = 1
                tasks = [asyncio.create_task(s.reader()), asyncio.create_task(s.pinger())]
                loop = asyncio.get_event_loop()
                while is_open(ws):
                    line = await loop.run_in_executor(None, sys.stdin.readline)
                    if not line:
                        return
                    cmd, _, rest = line.strip().partition(" ")
                    a = rest.split()
                    try:
                        if cmd == "quit":
                            await s.send({"t": "bye", "reason": "user"})
                            return
                        elif await run_command(s, st, cmd, rest):
                            pass
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
                        elif cmd == "abs":
                            await s.send({"t": "ptr_abs", "x": float(a[0]), "y": float(a[1])})
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
                        elif cmd == "mirror":
                            w, h, fps = (int(a[0]), int(a[1]), int(a[2])) if len(a) >= 3 else (1280, 800, 30)
                            await s.send({"t": "mirror_start", "codecs": ["h264", "hevc"], "maxW": w, "maxH": h,
                                          "fps": fps, "bitrate": 6_000_000})
                        elif cmd == "mirror_stop":
                            await s.send({"t": "mirror_stop"})
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
    h.add_argument("--addr", help="IP to put in the QR url")
    h.add_argument("--auto-accept", action="store_true", help="accept confirm-mode pairings without asking")
    h.add_argument("--bonjour", action="store_true", help="advertise over Bonjour (off by default: it shows up as a real device on other phones)")
    c = sub.add_parser("client")
    c.add_argument("target", help="ws://host:port[/v2] or an airbridge://pair?... url")
    c.add_argument("--name", default="Probe Client")
    args = ap.parse_args()
    try:
        asyncio.run(run_host(args) if args.mode == "host" else run_client(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
