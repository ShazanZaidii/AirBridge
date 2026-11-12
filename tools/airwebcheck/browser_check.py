#!/usr/bin/env python3
"""Drives the real AirWeb page in headless Chrome (through its DevTools port) against a live server.

    tools/airwebcheck/browser_check.py 'http://127.0.0.1:47650/?t=TOKEN'   # a server that already has files in its pool

Checks, in a real browser with real downloads: the two sections, "already downloaded" turning a row grey (and staying
grey after a reload), Select mode with round ticks, Get all / Select all / Clear, a selection downloaded as separate
files, everything as one zip, the "Need a short link?" round trip, and the layout and tap sizes on a phone-sized window. Needs Google Chrome; standard library only. It saves the files
it downloads in a temporary folder and deletes it afterwards. Screenshots go to the folder given by $SHOTS if set.
"""
import base64
import hashlib
import http.client
import json
import os
import random
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.parse
import zipfile

CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
FAILS = []


def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what)
    if not ok:
        FAILS.append(what)


class WS:
    """The least of a WebSocket client: one connection, text messages."""

    def __init__(self, url):
        u = urllib.parse.urlparse(url)
        self.sock = socket.create_connection((u.hostname, u.port), timeout=30)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET {u.path} HTTP/1.1\r\nHost: {u.hostname}:{u.port}\r\nUpgrade: websocket\r\n"
                           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            head += self.sock.recv(1)
        assert b" 101 " in head.split(b"\r\n")[0], head
        self.buf = b""
        self.n = 0
        self.events = []

    def _read(self, n):
        while len(self.buf) < n:
            d = self.sock.recv(65536)
            if not d:
                raise EOFError("connection closed")
            self.buf += d
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _send(self, text):
        data = text.encode()
        mask = os.urandom(4)
        h = bytes([0x81])
        if len(data) < 126: h += bytes([0x80 | len(data)])
        elif len(data) < 65536: h += bytes([0x80 | 126]) + struct.pack(">H", len(data))
        else: h += bytes([0x80 | 127]) + struct.pack(">Q", len(data))
        self.sock.sendall(h + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def _message(self):
        msg = b""
        while True:
            b0, b1 = self._read(2)
            n = b1 & 0x7F
            if n == 126: n = struct.unpack(">H", self._read(2))[0]
            elif n == 127: n = struct.unpack(">Q", self._read(8))[0]
            payload = self._read(n)
            msg += payload
            if b0 & 0x80:
                return msg.decode()

    def call(self, method, **params):
        self.n += 1
        mid = self.n
        self._send(json.dumps({"id": mid, "method": method, "params": params}))
        while True:
            m = json.loads(self._message())
            if m.get("id") == mid:
                if "error" in m:
                    raise RuntimeError(f"{method}: {m['error']}")
                return m.get("result", {})
            self.events.append(m)

    def ev(self, expr):
        r = self.call("Runtime.evaluate", expression=expr, returnByValue=True, awaitPromise=True)
        if "exceptionDetails" in r:
            raise RuntimeError(f"JS error in {expr!r}: {r['exceptionDetails']}")
        return r["result"].get("value")


def wait_for(ws, expr, timeout=30, what=None):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        try:
            last = ws.ev(expr)
            if last: return last
        except RuntimeError:
            pass
        time.sleep(0.25)
    if what: print(f"    (timed out waiting for {what}; last value {last!r})")
    return None


def files_in(d):
    return sorted(f for f in os.listdir(d) if not f.endswith(".crdownload") and not f.startswith("."))


def wait_files(d, n, timeout=60):
    end = time.time() + timeout
    while time.time() < end:
        if len(files_in(d)) >= n and not any(f.endswith(".crdownload") for f in os.listdir(d)):
            return files_in(d)
        time.sleep(0.3)
    return files_in(d)


def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(2)
    link = sys.argv[1]
    shots = os.environ.get("SHOTS")
    work = tempfile.mkdtemp(prefix="airweb-browser-")
    dl = os.path.join(work, "downloads"); os.makedirs(dl)
    prof = os.path.join(work, "profile")
    port = random.randint(9300, 9700)
    chrome = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", f"--remote-debugging-port={port}", f"--user-data-dir={prof}",
                               "--window-size=480,1100", "--no-first-run", "--no-default-browser-check", "about:blank"],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        target = None
        for _ in range(60):
            try:
                c = http.client.HTTPConnection("127.0.0.1", port, timeout=2); c.request("GET", "/json"); r = c.getresponse()
                targets = [t for t in json.loads(r.read()) if t.get("type") == "page"]; c.close()
                if targets: target = targets[0]; break
            except OSError:
                pass
            time.sleep(0.3)
        assert target, "Chrome didn't start"
        ws = WS(target["webSocketDebuggerUrl"])
        ws.call("Page.enable"); ws.call("Runtime.enable")
        ws.call("Browser.setDownloadBehavior", behavior="allow", downloadPath=dl, eventsEnabled=True)
        ws.call("Emulation.setDeviceMetricsOverride", width=420, height=900, deviceScaleFactor=2, mobile=True)

        print("the page, with a big pool")
        ws.call("Page.navigate", url=link)
        n = wait_for(ws, "document.querySelectorAll('#files li').length", 30, "the list") or 0
        check(n >= 2, f"'From others' lists {n} files")
        check(ws.ev("document.getElementById('oth-count').textContent") == str(n), "its counter says so")
        check(ws.ev("document.getElementById('bulk').classList.contains('hidden')"), "the bottom bar is out of the way until you select")
        check(ws.ev("getComputedStyle(document.querySelector('#files .tick')).display") == "none", "no tick circles until you press Select")
        check(ws.ev("document.querySelector('#mine-h').textContent") == "Shared by you" and ws.ev("document.querySelector('#oth-h').textContent") == "From others", "the sections are 'Shared by you' and 'From others'")
        check(ws.ev("document.querySelectorAll('#files li.got').length") == 0, "nothing is grey yet")
        radius = ws.ev("getComputedStyle(document.querySelector('#files li')).borderRadius")
        check(radius not in ("0px", ""), f"rows are rounded ({radius})")
        if shots: open(os.path.join(shots, "1-list.png"), "wb").write(base64.b64decode(ws.call("Page.captureScreenshot", format="png")["data"]))

        print("a file downloaded on its own turns grey")
        ws.ev("document.querySelector('#files li a.btn').click()")
        got1 = wait_for(ws, "document.querySelectorAll('#files li.got').length === 1", 20, "the row to turn grey")
        check(bool(got1), "the downloaded row turns grey")
        check(ws.ev("document.querySelector('#files li.got a.btn').getAttribute('aria-label')").startswith("Download again"), "its button now says 'Download again'")
        check("Downloaded" in ws.ev("document.querySelector('#files li.got .sub').textContent"), "its detail line says 'Downloaded'")
        check(ws.ev("getComputedStyle(document.querySelector('#files li.got .info')).opacity") != "1", "it is visibly muted")
        names = wait_files(dl, 1, 20)
        check(len(names) == 1, f"the file really arrived ({names})")
        if shots: open(os.path.join(shots, "2-one-grey.png"), "wb").write(base64.b64decode(ws.call("Page.captureScreenshot", format="png")["data"]))

        print("it stays grey after a reload (the host remembers this browser)")
        ws.call("Page.reload")
        wait_for(ws, "document.querySelectorAll('#files li').length > 1", 20)
        check(bool(wait_for(ws, "document.querySelectorAll('#files li.got').length === 1", 15, "grey after reload")), "still grey after reloading")

        print("Select: round ticks, tap a row, the bar says how many")
        ws.ev("document.getElementById('selmode').click()")
        check(ws.ev("getComputedStyle(document.querySelector('#files .tick')).display") != "none", "round ticks appear")
        check(not ws.ev("document.getElementById('bulk').classList.contains('hidden')"), "the bottom bar rises")
        check(ws.ev("document.getElementById('selcount').textContent") == "Tap files to select", "it asks you to tap files")
        check(ws.ev("document.getElementById('dl-files').disabled"), "nothing to download until something is ticked")
        ws.ev("(function(){var li=document.querySelectorAll('#files li:not(.got)');for(var i=0;i<6;i++){li[i].click();}})()")
        check(ws.ev("document.getElementById('selcount').textContent") == "6 files selected", "tapping 6 rows says '6 files selected'")
        check(ws.ev("document.querySelectorAll('#files li.sel').length") == 6, "and those rows are highlighted")
        check(ws.ev("document.getElementById('dl-zip').textContent") == "As one zip", "the bar offers 'As one zip'")
        check(ws.ev("document.getElementById('dl-files').textContent") == "As separate files", "and 'As separate files'")
        if shots: open(os.path.join(shots, "3-select.png"), "wb").write(base64.b64decode(ws.call("Page.captureScreenshot", format="png")["data"]))
        before = len(files_in(dl))
        ws.ev("document.getElementById('dl-files').click()")
        arrived = wait_files(dl, before + 6, 90)
        check(len(arrived) >= before + 6, f"all 6 arrived as separate files ({len(arrived) - before} new)")
        done = wait_for(ws, "document.querySelectorAll('#files li.got').length >= 7", 30, "the 6 rows to turn grey")
        check(bool(done), "and those rows turned grey too")
        status = ws.ev("document.getElementById('bulk-status').textContent")
        check("downloaded" in status.lower(), f"the status line says it is done ({status!r})")

        print("Get all as one zip")
        ws.ev("document.getElementById('seldone').click()")
        check(ws.ev("document.getElementById('selcount').textContent") != "", "Done leaves select mode")
        check(ws.ev("getComputedStyle(document.querySelector('#files .tick')).display") == "none", "the ticks go away again")
        ws.ev("document.getElementById('getall').click()")
        check(ws.ev("document.getElementById('selcount').textContent") == f"{n} files selected", f"Get all ticks all {n}")
        ws.ev("document.getElementById('clear').click()")
        check(ws.ev("document.getElementById('selcount').textContent") == "Tap files to select", "Clear empties the selection")
        ws.ev("document.getElementById('selall').click()")
        check(ws.ev("document.getElementById('selcount').textContent") == f"{n} files selected", f"Select all ticks all {n}")
        before_files = set(files_in(dl))
        ws.ev("document.getElementById('dl-zip').click()")
        end = time.time() + 90
        newzip = None
        while time.time() < end and not newzip:
            fresh = [f for f in files_in(dl) if f not in before_files and f.endswith(".zip")]
            if fresh: newzip = fresh[0]
            time.sleep(0.3)
        check(newzip is not None, f"a zip arrived ({newzip})")
        if newzip:
            time.sleep(0.5)
            z = zipfile.ZipFile(os.path.join(dl, newzip))
            check(z.testzip() is None and len(z.namelist()) == n, f"it opens, with all {n} files and good CRCs ({len(z.namelist())} entries)")
            check(len(set(z.namelist())) == n, "every name in it is unique")
        all_grey = wait_for(ws, f"document.querySelectorAll('#files li.got').length === {n}", 30, "every row to turn grey")
        check(bool(all_grey), f"afterwards all {n} rows are grey")
        check("finished" in ws.ev("document.getElementById('bulk-status').textContent"), "the status line says the zip finished")
        if shots: open(os.path.join(shots, "4-all-grey.png"), "wb").write(base64.b64decode(ws.call("Page.captureScreenshot", format="png")["data"]))
        ws.ev("document.getElementById('seldone').click()")

        print("Need a short link?")
        check(ws.ev("document.getElementById('short-box').classList.contains('hidden')"), "the short link is tucked away until asked for")
        ws.ev("document.getElementById('short-ask').click()")
        shorturl = wait_for(ws, "document.getElementById('short-url').textContent", 10, "the short link")
        check(bool(shorturl) and "/j/" in shorturl, f"the page shows a short link ({shorturl!r})")
        code = ws.ev("document.querySelector('#short-url .short-code').textContent")
        check(bool(code) and len(code) == 6, f"with a six-character code ({code!r})")
        check(ws.ev("document.getElementById('link').textContent").find("?t=") > 0, "the full link is still shown above it")
        u = urllib.parse.urlparse(link)
        c = http.client.HTTPConnection(u.hostname, u.port, timeout=10)
        c.request("GET", "/j/" + code.lower()); r = c.getresponse(); r.read()
        check(r.status == 302 and "t=" in (r.getheader("Location") or ""), f"opening it redirects to the real link ({r.status})")
        c = http.client.HTTPConnection(u.hostname, u.port, timeout=10)
        c.request("GET", "/j/AAAAAA"); r = c.getresponse(); r.read()
        check(r.status in (404, 429), f"a wrong code is refused ({r.status})")
        if shots:
            ws.ev("document.getElementById('inv-h').scrollIntoView()")
            open(os.path.join(shots, "5-invite.png"), "wb").write(base64.b64decode(ws.call("Page.captureScreenshot", format="png")["data"]))

        print("layout on a phone")
        check(ws.ev("document.documentElement.scrollWidth <= window.innerWidth + 1"), "nothing scrolls sideways")
        ws.ev("document.getElementById('selmode').click()")
        bar = ws.ev("(function(){var r=document.getElementById('bulk').getBoundingClientRect();return [r.left,r.right,window.innerWidth]})()")
        check(bar[0] >= -1 and bar[1] <= bar[2] + 1, f"the bottom bar fits the screen {bar}")
        small = ws.ev("(function(){var b=document.querySelectorAll('button,.btn,.chipbtn,.tick'),bad=0;for(var i=0;i<b.length;i++){var r=b[i].getBoundingClientRect();if(r.width>0&&(r.height<43||r.width<43))bad++;}return bad})()")
        check(small == 0, f"every button and tick is at least 44 px ({small} too small)")
    finally:
        chrome.terminate()
        try: chrome.wait(10)
        except subprocess.TimeoutExpired: chrome.kill()
        shutil.rmtree(work, ignore_errors=True)

    print()
    print("ALL OK" if not FAILS else f"{len(FAILS)} FAILED")
    sys.exit(0 if not FAILS else 1)


if __name__ == "__main__":
    main()
