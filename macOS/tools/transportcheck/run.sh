#!/bin/sh
# Builds SocketTransport with a small driver and checks it never traps when dropped mid-close.
set -e
cd "$(dirname "$0")"
OUT="${TMPDIR:-/tmp}/transportcheck"
swiftc -O -o "$OUT" ../../AirBridge/Net/Transport.swift Stubs.swift main.swift
"$OUT"
