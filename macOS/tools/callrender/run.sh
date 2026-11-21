#!/bin/sh
# Draws the call banner and the in-call window (light and dark) to PNGs for review: tools/callrender/run.sh [outdir]
# Builds a tiny separate program with swiftc; never launches AirBridge. The windows appear for about a second
# in the bottom-left corner of the screen (Liquid Glass is drawn by the window server, so they must be on screen),
# over a backdrop window of its own so nothing else on the screen is captured.
set -e
here=$(cd "$(dirname "$0")" && pwd)
src="$here/../../AirBridge"
out=${TMPDIR:-/tmp}/airbridge-callrender
swiftc -swift-version 5 -o "$out" "$src/PhoneNotifs/PhoneNotifModel.swift" "$src/Calls/CallLogic.swift" \
  "$src/CallAudio/CallAudio.swift" "$src/Calls/CallViews.swift" "$src/Calls/CallWindows.swift" "$src/Calls/AudioRouteMenu.swift" "$here/main.swift"
"$out" "${1:-${TMPDIR:-/tmp}/airbridge-callrender-out}"
