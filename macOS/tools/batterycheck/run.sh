#!/bin/sh
# Builds and runs the phone-battery self-check with swiftc: publishes through the real IOKit calls and reads the
# system's accessory list back (no Xcode project, no app launch). Entries live only while the check runs.
set -e
here=$(cd "$(dirname "$0")" && pwd)
out=${TMPDIR:-/tmp}/airbridge-batterycheck
swiftc -swift-version 5 -o "$out" "$here/../../Widgets/Integration/DevicePowerSources.swift" "$here/../../AirBridge/PeerBus.swift" "$here/main.swift"
"$out"
