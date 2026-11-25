#!/usr/bin/env python3
"""Makes sure a built AirBridge.exe's manifest has the package-identity line (issue #10), adding it in place if not.

The SDK normally embeds app.manifest (with its <msix> element) into AirBridge.exe, also when building on a Mac; this is
the check for when it didn't and the exe kept the stock manifest ("MyApplication.app"). Without that element the zip
build's external-location package (Identity/AirBridge.Identity.msix) can't give the process an identity.

The manifest resource is rewritten byte for byte at the same length (padded with spaces, which XML allows after the
root element), so no section, size or offset in the file changes. The stock manifest's only setting, asInvoker, is the
default for a 64-bit app with this name, so it isn't repeated. Usage: patch_apphost_manifest.py AirBridge.exe [...]
"""
import sys

NEW = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
       '<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">'
       '<assemblyIdentity version="1.0.0.0" name="AirBridge.app"/>'
       '<msix xmlns="urn:schemas-microsoft-com:msix.v1" '
       'publisher="CN=AirBridge, OID.2.25.311729368913984317654407730594956997722=1" '
       'packageName="AirBridge.Identity" applicationId="AirBridge"/>'
       '</assembly>').encode("utf-8")


def patch(path):
    data = bytearray(open(path, "rb").read())
    if b"urn:schemas-microsoft-com:msix.v1" in data:
        print(f"{path}: already has the identity line")
        return
    start = data.find(b"\xef\xbb\xbf<?xml")
    if start < 0:
        start = data.find(b"<?xml")
    end = data.find(b"</assembly>", start)
    if start < 0 or end < 0 or data.count(b"urn:schemas-microsoft-com:asm.v1") != 1:
        sys.exit(f"{path}: no single manifest found")
    end += len(b"</assembly>")
    old_len = end - start
    bom = b"\xef\xbb\xbf" if data[start:start + 3] == b"\xef\xbb\xbf" else b""
    new = bom + NEW
    if len(new) > old_len:
        sys.exit(f"{path}: the new manifest ({len(new)} bytes) doesn't fit in the old one ({old_len})")
    data[start:end] = new + b" " * (old_len - len(new))
    open(path, "wb").write(bytes(data))
    print(f"{path}: identity line added ({len(new)} of {old_len} bytes)")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for p in sys.argv[1:]:
        patch(p)
