# AirWeb

AirWeb lets a phone or computer **without AirBridge** swap files through a device that has it, from a browser. The
host (an Android phone or a Mac) serves a small web page on its Wi-Fi (or hotspot) address and shows a QR code of the
link. Everyone who opens the link joins **one shared pool**.

## The shared pool

- A file added on the host, or uploaded by **any** browser, is listed for, and downloadable by, **everyone** who is
  connected. It doesn't matter whose QR code or link someone used: they are all the same session.
- Any connected browser can show the link as a QR code (the page's "Invite someone" section, drawn by the host at
  `/qr.svg`) so people nearby can join without going to the host.
- The host keeps a copy of every upload (Android: Downloads › AirBridge Transfers; Mac: the same folder in
  Downloads). On the host's screen an uploaded file has a ✕ to stop sharing it (the copy stays).
- The host sees who is connected ("3 devices connected: Chrome on Windows · Safari on iPhone · …"). Browsers are
  labelled from their user agent; two of the same kind are numbered.
- Everything lives for the session only: closing AirWeb on the host stops the server and empties the pool.

## HTTP interface

HTTP/1.1, not WebSocket. One request per connection (`Connection: close`). Every request needs the session token
`t` (128 random bits, in the link) or the answer is 403.

| Route | |
|---|---|
| `GET /` | the page |
| `GET /api/state?c=&w=&v=` | JSON: `name`, `free`, `now`, `poll` (ms), `v`, `count`, `peers` (`[{id, name}]`, at most 24, the asking page always among them) and `files` (`[{id, name, size, added, by?, own?}]`). When `v` is the pool's current version the list is left out and `"same":true` is sent: a few hundred bytes. |
| `GET\|HEAD /d/<id>[/<name>]` | download one pool file |
| `POST /u?n=<name>&c=&w=` | upload one file into the pool; raw body; `Content-Length` required |
| `GET /qr.svg` | the QR code of this page's own link (built from the `Host` header the browser used) |

`c` is a random id the page makes for its tab (8 to 40 of `A-Za-z0-9_-`); `w` is its device label. Both are optional
(`curl` works), but only pages that send `c` count as "connected". File ids from the host count up from 1; uploads
start at 1 000 000.

## Staying up with many browsers

All limits are in one place (`AirWebServer.Limits` on Android, `AirWebLimits` on the Mac). The numbers below are the
defaults (the Mac allows 12 transfers at once instead of 8).

| Limit | Default | When exceeded |
|---|---|---|
| Connections open at once (one thread each) | 128 | `503` + `Retry-After: 3` |
| Connections from one address | 24 | `503` + `Retry-After: 3` |
| Request head must arrive within | 10 s | the connection is dropped (slow or silent clients can't hold it) |
| No byte moving for | 30 s | the connection is dropped (Android also cuts a transfer socket with no progress for 45 s) |
| Files moving at once | 8 (Mac 12) | the request waits its turn in a fair waiting room |
| Requests waiting for a turn | 32, for up to 120 s | `503` + `Retry-After: 5` |
| Connected browsers tracked | 256 (gone after 20 s of silence) | oldest forgotten |
| Files in the pool | 1000 | upload refused with `507` `"error":"full"` |
| Free space | checked per upload **including the uploads already running** | `507` `"error":"space"` |

The page polls every `poll` ms, which grows with the number of browsers (2 s alone, 8 s with 60), asks a hidden
tab only a third as often, and sends back `v` so an unchanged pool costs almost nothing. A busy answer makes the page
wait and try again (uploads retry up to five times with growing pauses).

## Tests

- `AirWebServerTest`, `AirWebHttpTest`, `AirWebPoolTest` (Android unit tests, real sockets): the pool, who is
  connected, the unchanged-poll shortcut, the QR route, slow and silent connections, the per-address and total
  connection caps, the waiting room (never more than the limit moving, overflow and timeout answers), space shared
  between uploads, a stalled client being cut loose, and 60 browsers at once.
- `tools/airwebcheck/check.py <link>`: the same scenarios against a live server (the phone via `adb forward`, or the
  Mac app via `airbridge://debug/airweb?out=<file>`), with `--slow` for the slow-connection checks. It prints the
  poll latency under a crowd.
