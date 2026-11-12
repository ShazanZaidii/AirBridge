# AirWeb

AirWeb lets a phone or computer **without AirBridge** swap files through a device that has it, from a browser. The
host (an Android phone, a Mac or a Windows PC) serves a small web page on its Wi-Fi (or hotspot) address and shows a QR
code of the link. Everyone who opens the link joins **one shared pool**. All three hosts serve the same page
(`airweb.html`, byte-identical in the three repos) and speak the same HTTP interface below.

## One session, always on until stopped

There is only ever one AirWeb server per host, and leaving its screen does not end it:

- **Android:** `AirWebService` (a foreground service with a notification and a Stop action) keeps the one session
  (`AirWebSession`) alive after Back. Opening AirWeb again, or sharing files into it later from the gallery or any app,
  joins the same server, link, port and pool instead of starting another. The files' read grants travel with the service
  start, so they outlive the sharing screen.
- **Mac:** closing the AirWeb window leaves it running; the menu's AirWeb tile brings the window back.
- **Windows:** the same: the window is only a view onto the session.
- It ends when you tap **Stop** (window, notification) or after **2 hours with nobody connected and nothing moving**.

**A new link on purpose** ("New link" in the host screen) gives the session a new token. The old link stops working for
new visitors, but a page that is already open on it is told in its next poll (`{"moved":"/?t=<new>"}`) and shows a
persistent banner with a button to the new link; reloading the old page lands on the new one (`302`). The last 8 old
tokens are remembered, nothing else is allowed on them.

**If the host's address changes** (Wi-Fi to hotspot), the state carries `alts`: full links on the host's other addresses.
A page that can't reach the host any more shows them as buttons in a banner ("may have moved to another network
address"). They are only shown when the host has more than one address.

## The shared pool

- A file added on the host, or uploaded by **any** browser, is listed for, and downloadable by, **everyone** who is
  connected. It doesn't matter whose QR code or link someone used: they are all the same session.
- Any connected browser can show the link as a QR code (the page's "Invite someone" section, drawn by the host at
  `/qr.svg`) so people nearby can join without going to the host.
- **Need a short link?** On a computer where you can't scan the code or type 40 characters, the same section can ask the
  host for a short one: `http://10.0.0.5:47650/j/K3X9QP`. It only redirects to the real link, which stays the one to
  trust. The host's own screen (phone, Mac, PC) has the same "Need a short link?" under its QR code. See "The short
  link" below.
- The host keeps a copy of every upload (Android: Downloads › AirBridge Transfers; Mac: the same folder in
  Downloads). On the host's screen an uploaded file has a ✕ to stop sharing it (the copy stays).
- The host sees who is connected ("3 devices connected: Chrome on Windows · Safari on iPhone · …"). Browsers are
  labelled from their user agent; two of the same kind are numbered.
- **Already downloaded** files show muted grey on the page ("Downloaded", with a "Download again" button). The host
  records, per browser, each file that was sent in full, so it is exact (not just "clicked") and survives a refresh or
  a new tab (the page keeps one random id per browser).
- **The page has two lists.** "Shared by you" holds the files this browser added, with the Add files button and the
  progress of what is being sent. "From others" holds everything the host and the other browsers shared; that is the
  list you download from. Rows are soft rounded cards that light up as a whole, with a download button that is a plain
  icon on a phone.
- **Select and download in bulk.** *Select* turns the rows into tappable cards with round ticks; *Get all* does the same
  with everything ticked. A bar rises from the bottom with the count and two choices: **one zip**, or **separate
  files**. A zip is the easy way for a lot of files; separate files are fetched four at a time, each started only when
  the host says the previous one arrived, so 200 files don't hit the browser all at once. (A browser may ask once to
  allow multiple downloads: choose Allow, or use the zip.)
- Everything lives for the session only: closing AirWeb on the host stops the server and empties the pool.

## HTTP interface

HTTP/1.1, not WebSocket. One request per connection (`Connection: close`). Every request needs the session token
`t` (128 random bits, in the link) or the answer is 403.

| Route | |
|---|---|
| `GET /` | the page |
| `GET /api/state?c=&w=&v=` | JSON: `name`, `free`, `now`, `poll` (ms), `v`, `count`, `peers` (`[{id, name}]`, at most 24, the asking page always among them) and `files` (`[{id, name, size, added, by?, own?}]`). When `v` is the pool's current version the list is left out and `"same":true` is sent: a few hundred bytes. `got` (ids this browser has downloaded in full), `gn` (how many) and `act` (ids being sent to it right now) come with the state; `got` is only included when `g`, the `gn` the page last saw, differs. |
| `GET\|HEAD /d/<id>[/<name>]?c=` | download one pool file; with `c` the host remembers this browser got it |
| `GET\|HEAD /z?ids=1-40,1000001&c=` | the listed pool files (all of them without `ids`) as one ZIP, streamed; `ids` is comma-separated ids and `a-b` ranges |
| `POST /u?n=<name>&c=&w=` | upload one file into the pool; raw body; `Content-Length` required |
| `GET /qr.svg` | the QR code of this page's own link (built from the `Host` header the browser used) |
| `POST /api/short` | a short code for the link: `{"code":"K3X9QP","ttl":600}` (seconds left) |
| `GET\|HEAD /j/<code>` | the one route that needs no token: `302` to `/?t=<token>` for a live code |

`c` is a random id the page makes for its tab (8 to 40 of `A-Za-z0-9_-`); `w` is its device label. Both are optional
(`curl` works), but only pages that send `c` count as "connected". File ids from the host count up from 1; uploads
start at 1 000 000.

## The zip

Streamed as it is sent: each file is read once in 64 KB pieces and written straight to the connection, never held in
memory or copied to a temporary file, so 5 files or 1000 cost the same little memory.

Nothing is compressed. Each file goes in as *stored* deflate blocks, which every unzip tool reads as an ordinary entry
(CRC and sizes follow the data in a data descriptor). That spares the phone's CPU and battery, and it makes the exact
size of the archive known before the first byte, so the browser shows a real progress bar. (A file of unknown length
makes the size unknown; that file is written in the plain form.) ZIP64 is used only where needed: a file of 4 GB or
more, more than 65 535 files, or an archive past 4 GB.

Names are made safe and unique inside the archive (no folders, no control characters, at most 200 bytes, and
`photo (2).jpg` for a repeat, compared without regard to case). A file that can't be read when the zip starts is left
out; one that shrinks or vanishes part-way aborts the download rather than produce a broken zip. A zip uses one
transfer slot and each file counts as downloaded as soon as it has gone through, so a cancelled zip only counts the
files that made it.

## The short link

A six-character code on the host's own address (`/j/K3X9QP`) that sends the browser on to the real link. It is made only
when a connected page asks (the "Need a short link?" button), lasts 10 minutes and is the same for everyone who asks in
that time. It is weaker than the full link by design, so it is fenced in:

- Characters come from an alphabet without look-alikes (no 0/O, 1/I/L), about 30 bits. Letters work in any case.
- Wrong guesses are counted for the whole host. After 8 in a minute every try gets `429` until the minute is over,
  right or wrong, so guessing it would take years. The real link keeps working meanwhile.
- A wrong or expired code gets a plain page that doesn't contain the token.

It fixes typing, not reachability: the other computer still has to be able to reach the host's address (same Wi-Fi or
hotspot). A real short domain, or reaching a host over the internet, would need a server in the middle and isn't built.

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
| Browsers whose "downloaded" list is kept | 512 (the least recently seen are forgotten) | oldest forgotten |

The page polls every `poll` ms, which grows with the number of browsers (2 s alone, 8 s with 60), asks a hidden
tab only a third as often, and sends back `v` so an unchanged pool costs almost nothing. A busy answer makes the page
wait and try again (uploads retry up to five times with growing pauses).

## Tests

- `AirWebServerTest`, `AirWebHttpTest`, `AirWebPoolTest` (Android unit tests, real sockets): the pool, who is
  connected, the unchanged-poll shortcut, the QR route, slow and silent connections, the per-address and total
  connection caps, the waiting room (never more than the limit moving, overflow and timeout answers), space shared
  between uploads, a stalled client being cut loose, and 60 browsers at once.
- `AirWebMovedTest`: a new link (`moved` for an open page, the redirect on a reload, nothing else allowed on an old
  token, only the last 8 remembered) and the `alts` list.
- `QrKindTest` (the phone's QR scanner): pairing code, AirWeb link (full and short, only on a private address), web
  link, Wi-Fi code, text.
- `AirWebShortLinkTest`: the code (alphabet, reuse, expiry, case, the lock-out with a fake clock) and the two routes over
  real sockets.
- `AirWebZipTest`, `AirWebBulkTest`: the zip (every block edge, 200 and 70 000 files, the ZIP64 paths, odd names, a 300 MB
  file in constant memory, read back with Java's own unzip) and the zip route, the id ranges and the per-browser
  "downloaded" list over real sockets, including 1000 files zipped.
- `tools/airwebcheck/check.py <link>`: the same scenarios against a live server (the phone via `adb forward`, or the
  Mac app via `airbridge://debug/airweb?out=<file>`), with `--slow` for the slow-connection checks. It uploads 200
  files, zips all of them and a scattered selection and checks the result with Python's unzip, and prints the poll
  latency under a crowd. It also checks the short link, including the lock-out.
- `tools/airwebcheck/browser_check.py <link>`: drives the real page in headless Chrome against a server with files in
  it: the two lists, grey rows after a download (and after a reload), Select mode and its round ticks, Get all, Select
  all and Clear, a selection as separate files, everything as one zip, the short link round trip, and the layout and
  44 px tap sizes on a phone-sized window.

## Windows

The Windows app has its own host in C# (`AirBridge/AirWeb/`: `AirWebServer`, `AirWebSession`, `AirWebShortLink`,
`AirWebWindow`), with the same limits, routes and page. It uses async sockets (no thread per connection) and streams the
zip *stored* (no compression), without announcing its size (the page's progress bar is then indeterminate).

`dotnet run --project AirBridge.AirWebTests` (any OS) checks it over real sockets: the pool, uploads (hostile names stay
inside the save folder), downloads, the zip, the short link and its lock-out, a new link, a crowd, garbage input. With
`-- serve` it starts a host with sample files and prints its link, so `tools/airwebcheck/check.py` and
`browser_check.py` run against it unchanged (both pass; check.py accepts a missing zip `Content-Length`).
