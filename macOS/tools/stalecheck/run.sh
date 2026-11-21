#!/bin/sh
# Builds and runs the reinstall / stale-entry self-check with swiftc (no Xcode project, no app launch, no Keychain).
set -e
here=$(cd "$(dirname "$0")" && pwd)
out=${TMPDIR:-/tmp}/airbridge-stalecheck
swiftc -swift-version 5 -o "$out" "$here/../../AirBridge/Net/StaleDevices.swift" "$here/../../AirBridge/Input/Layout.swift" "$here/../../AirBridge/PeerBus.swift" "$here/main.swift"
"$out"
