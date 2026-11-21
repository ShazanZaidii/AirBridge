#!/bin/sh
# Pro mirroring stream check: scrcpy framing over a local socket into the real hardware decoder (no phone needed).
set -e
here=$(cd "$(dirname "$0")" && pwd)
app="$here/../../AirBridge"
out=${TMPDIR:-/tmp}/airbridge-promirrorstream
swiftc -swift-version 5 -o "$out" "$app/ProMirror/ScrcpyProtocol.swift" "$app/Mirror/MirrorDecoder.swift" \
  "$app/Input/MirrorEncoder.swift" "$here/main.swift"
"$out"
