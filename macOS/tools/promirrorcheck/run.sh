#!/bin/sh
# Builds and runs the Pro mirroring self-check with swiftc (no Xcode project, no app launch, no phone).
set -e
here=$(cd "$(dirname "$0")" && pwd)
src="$here/../../AirBridge/ProMirror"
out=${TMPDIR:-/tmp}/airbridge-promirrorcheck
swiftc -swift-version 5 -o "$out" \
  "$src/ScrcpyProtocol.swift" "$src/AndroidKeys.swift" "$src/ProMirrorInput.swift" \
  "$src/ADB.swift" "$src/ADBDiscovery.swift" "$src/ProMirrorSetup.swift" \
  "$here/main.swift"
"$out"
