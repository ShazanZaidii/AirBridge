#!/usr/bin/env python3
"""TCP throughput peer for the Android link debug screen (device test S-W4).

Run on the PC (any OS, Python 3.8+):   python wfd_tcp_peer.py [port]   (default 5201)

The phone connects over the joined Wi-Fi Direct network and sends one mode byte:
  'U'  phone -> PC: this script reads and discards until the phone closes
  'D'  PC -> phone: this script writes zeros until the phone closes
Allow Python through Windows Firewall on Public networks (the Wi-Fi Direct adapter is Public).
"""
import socket
import sys
import threading
import time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 5201
BUF = bytes(256 * 1024)


def handle(conn: socket.socket, addr) -> None:
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    mode = conn.recv(1)
    t0, total = time.monotonic(), 0
    try:
        if mode == b"U":
            while True:
                chunk = conn.recv(1 << 20)
                if not chunk:
                    break
                total += len(chunk)
        elif mode == b"D":
            while True:
                conn.sendall(BUF)
                total += len(BUF)
    except OSError:
        pass
    finally:
        conn.close()
    dt = max(time.monotonic() - t0, 1e-6)
    print(f"{addr[0]} mode={mode.decode(errors='replace')} bytes={total} secs={dt:.1f} MBps={total / 1e6 / dt:.1f}", flush=True)


def main() -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(4)
    print(f"listening on 0.0.0.0:{PORT}", flush=True)
    while True:
        conn, addr = srv.accept()
        threading.Thread(target=handle, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    main()
