#!/bin/sh
# Builds and runs the transfer-card self-check with swiftc (no Xcode project, no app launch).
set -e
here=$(cd "$(dirname "$0")" && pwd)
src="$here/../../AirBridge"
out=${TMPDIR:-/tmp}/airbridge-hudcheck
swiftc -swift-version 5 -o "$out" "$src/DropHUD/DropLogic.swift" "$src/DropHUD/HUDState.swift" "$src/Net/TransferStatus.swift" "$here/main.swift"
"$out"
