#!/bin/sh
# Builds the two Windows release zips from this Mac (or any OS with the .NET 8 SDK), ready to upload as release assets:
#   AirBridge-Windows.zip                 self-contained x64 (no .NET needed)
#   AirBridge-Windows-needs-dotnet8.zip   framework-dependent x64 (needs the .NET 8 Desktop Runtime)
# Each is one AirBridge-Windows/ folder. AirBridge.exe gets the package-identity line (#10) and the identity package
# is rebuilt for this version first. Usage: tools/build_release_zips.sh [output folder] (default: ./dist)
set -e
cd "$(dirname "$0")/.."
OUT="${1:-$PWD/dist}"
mkdir -p "$OUT"
python3 tools/make_identity_package.py
for kind in fd sc; do
  dir="$OUT/$kind/AirBridge-Windows"
  rm -rf "$OUT/$kind"
  if [ $kind = sc ]; then sc=true; else sc=false; fi
  dotnet publish AirBridge/AirBridge.csproj -c Release -r win-x64 --self-contained $sc \
    -p:EnableWindowsTargeting=true -p:Platform=x64 -o "$dir" >/dev/null
  python3 tools/patch_apphost_manifest.py "$dir/AirBridge.exe"
  test -f "$dir/Identity/AirBridge.Identity.msix"
done
( cd "$OUT/sc" && rm -f ../AirBridge-Windows.zip && zip -qr -X ../AirBridge-Windows.zip AirBridge-Windows )
( cd "$OUT/fd" && rm -f ../AirBridge-Windows-needs-dotnet8.zip && zip -qr -X ../AirBridge-Windows-needs-dotnet8.zip AirBridge-Windows )
ls -l "$OUT"/*.zip
