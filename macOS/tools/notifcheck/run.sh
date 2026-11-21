#!/bin/sh
# Builds and runs the phone-notification self-check with swiftc (no Xcode project, no app launch).
set -e
here=$(cd "$(dirname "$0")" && pwd)
out=${TMPDIR:-/tmp}/airbridge-notifcheck
swiftc -swift-version 5 -o "$out" "$here/../../AirBridge/PhoneNotifs/PhoneNotifModel.swift" "$here/../../AirBridge/Calls/CallLogic.swift" "$here/main.swift"
"$out"
