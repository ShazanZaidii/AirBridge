#!/bin/sh
# Builds and runs the drag-carry self-check with swiftc (no Xcode project, no app launch).
set -e
here=$(cd "$(dirname "$0")" && pwd)
out=${TMPDIR:-/tmp}/airbridge-dragcheck
swiftc -swift-version 5 -o "$out" "$here/../../AirBridge/Input/DragCarry.swift" "$here/Msg.swift" "$here/main.swift"
"$out"
