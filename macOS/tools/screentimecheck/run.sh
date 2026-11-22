#!/bin/sh
# Builds and runs the screen-time model self-check (Widgets/Shared/ScreenTimeModel.swift) with swiftc.
set -e
here=$(cd "$(dirname "$0")" && pwd)
out=${TMPDIR:-/tmp}/airbridge-screentimecheck
swiftc -swift-version 5 -o "$out" "$here/../../Widgets/Shared/ScreenTimeModel.swift" "$here/../../AirBridge/ScreenTime/ScreenTimeAccumulator.swift" "$here/main.swift"
"$out"
