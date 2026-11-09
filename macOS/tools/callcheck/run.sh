#!/bin/sh
# Builds and runs the call-experience self-check (ring policy, proximity, presence, beacon id, call_state parsing)
# with swiftc. No Xcode project, never launches the app.
set -e
here=$(cd "$(dirname "$0")" && pwd)
out=${TMPDIR:-/tmp}/airbridge-callcheck
swiftc -swift-version 5 -o "$out" "$here/../../AirBridge/PhoneNotifs/PhoneNotifModel.swift" "$here/../../AirBridge/Calls/CallLogic.swift" "$here/main.swift"
cd "$here/../.." && "$out"
