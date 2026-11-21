#!/bin/sh
# Downloads the two third-party files Pro mirroring bundles, verifies them against pinned checksums and puts
# them in AirBridge/ProMirror/Vendor. Run with --verify to only check the files already there.
#
# Updating scrcpy: the client protocol is version-locked. Change SCRCPY_VERSION and SCRCPY_SHA256 here, rename
# the file, then update Scrcpy.version / serverResource / serverSHA256 / devicePath in ScrcpyProtocol.swift,
# re-check the wire format against the new tag's doc/develop.md and app/tests/test_control_msg_serialize.c,
# and run tools/promirrorcheck/run.sh and tools/promirrorstream/run.sh.
set -e
SCRCPY_VERSION=5.0.1
SCRCPY_SHA256=764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536
PT_VERSION=37.0.1
PT_ZIP_SHA1=6ae73f4de6452dc57e62ec02b68eed92a4c21661          # Google's repository2-3.xml
PT_ZIP_SHA256=ee39ad5967e95c2a07f04dbcbde96b1a0c916ba376096db5d2f498b7727a5d1d
ADB_SHA256=1811e253b21b12cbfda7201ebaf86c10e7ddcb5c606a7a81f7c82b4c429c2d3b

here=$(cd "$(dirname "$0")" && pwd)
vendor="$here/../AirBridge/ProMirror/Vendor"
sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
need() { [ "$(sha256 "$1")" = "$2" ] || { echo "checksum mismatch: $1" >&2; exit 1; }; }

if [ "$1" = "--verify" ]; then
  need "$vendor/scrcpy-server-v$SCRCPY_VERSION" "$SCRCPY_SHA256"
  need "$vendor/adb" "$ADB_SHA256"
  codesign --verify --strict "$vendor/adb"
  codesign -dv "$vendor/adb" 2>&1 | grep -q "TeamIdentifier=EQHXZ8M8AV" || { echo "adb is not signed by Google" >&2; exit 1; }
  echo "Pro mirroring vendor files OK"
  exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fsSL -o "$tmp/scrcpy-server" "https://github.com/Genymobile/scrcpy/releases/download/v$SCRCPY_VERSION/scrcpy-server-v$SCRCPY_VERSION"
need "$tmp/scrcpy-server" "$SCRCPY_SHA256"
curl -fsSL -o "$tmp/LICENSE" "https://raw.githubusercontent.com/Genymobile/scrcpy/v$SCRCPY_VERSION/LICENSE"
curl -fsSL -o "$tmp/pt.zip" "https://dl.google.com/android/repository/platform-tools_r$PT_VERSION-darwin.zip"
[ "$(shasum -a 1 "$tmp/pt.zip" | cut -d' ' -f1)" = "$PT_ZIP_SHA1" ] || { echo "platform-tools SHA-1 mismatch" >&2; exit 1; }
need "$tmp/pt.zip" "$PT_ZIP_SHA256"
unzip -q "$tmp/pt.zip" platform-tools/adb platform-tools/NOTICE.txt -d "$tmp"
need "$tmp/platform-tools/adb" "$ADB_SHA256"

mkdir -p "$vendor"
cp "$tmp/scrcpy-server" "$vendor/scrcpy-server-v$SCRCPY_VERSION"
cp "$tmp/LICENSE" "$vendor/scrcpy-LICENSE.txt"
cp "$tmp/platform-tools/adb" "$vendor/adb"
cp "$tmp/platform-tools/NOTICE.txt" "$vendor/android-platform-tools-NOTICE.txt"
chmod +x "$vendor/adb"
"$0" --verify
