#!/bin/sh
# Unit tests for the call-audio routing rules (AirBridge/CallAudio/CallAudioPolicy.swift).
# The policy has no Bluetooth or audio code in it, so it builds and runs on its own:
#   tools/CallAudioPolicyTests/run.sh
set -e
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
out=${TMPDIR:-/tmp}/callaudio-policy-tests
xcrun swiftc -swift-version 5 -O -o "$out" "$root/AirBridge/CallAudio/CallAudioPolicy.swift" "$here/main.swift"
"$out"
