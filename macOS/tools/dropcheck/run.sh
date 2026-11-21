#!/bin/sh
# Builds and runs the drop-staging self-check with swiftc (no Xcode project, no app launch).
set -e
here=$(cd "$(dirname "$0")" && pwd)
out=${TMPDIR:-/tmp}/airbridge-dropcheck
swiftc -swift-version 5 -o "$out" "$here/../../AirBridge/DropHUD/DropLogic.swift" "$here/Stubs.swift" "$here/main.swift"
"$out"
