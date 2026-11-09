#!/bin/sh
# Offline probe of the IOBluetooth hands-free API and the call-audio helpers (no pairing, no connection,
# no microphone): tools/CallAudioProbe/run.sh
set -e
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
out=${TMPDIR:-/tmp}/callaudio-probe
xcrun swiftc -swift-version 5 -o "$out" "$root/AirBridge/CallAudio/CallAudioBridge.swift" "$root/AirBridge/CallAudio/CallAudioLog.swift" "$root/AirBridge/CallAudio/MacAudioDevices.swift" "$root/AirBridge/CallAudio/CallAudio.swift" "$here/main.swift"
"$out"
