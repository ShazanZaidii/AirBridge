#!/bin/sh
# Builds and runs the clipboard-image self-check with swiftc. Uses its own named pasteboard, never touches yours.
set -e
here=$(cd "$(dirname "$0")" && pwd)
out=${TMPDIR:-/tmp}/airbridge-clipcheck
swiftc -swift-version 5 -o "$out" "$here/../../AirBridge/Net/ClipboardImages.swift" "$here/main.swift"
"$out"
