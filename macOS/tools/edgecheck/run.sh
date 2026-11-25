#!/bin/sh
# Builds EdgePush with its checks (issue #6: deliberate pushes cross, accidental contact doesn't).
set -e
cd "$(dirname "$0")"
OUT="${TMPDIR:-/tmp}/edgecheck"
swiftc -O -o "$OUT" ../../AirBridge/Input/EdgePush.swift main.swift
"$OUT"
