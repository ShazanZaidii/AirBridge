#!/usr/bin/env python3
"""Builds AirBridge/Identity/AirBridge.Identity.msix: the identity package for the zip build (issue #10).

Windows grants notification access (and other package-only APIs) only to apps with package identity. The installer
(MSIX) build has it. The zip build gets it by registering this small "package with external location" on first launch
(Phone/PackageIdentity.cs): it holds only a manifest and a logo, and points at the folder AirBridge.exe runs from.

It is unsigned on purpose: Windows 11 accepts an unsigned external-location package added with
AddPackageOptions.AllowUnsigned when the publisher carries the "unsigned" OID below. No certificate, no admin prompt.

An MSIX is a zip: the payload files stored (not compressed), then AppxBlockMap.xml (SHA-256 of every 64 KiB block
of every payload file), then [Content_Types].xml. Run from anywhere; it writes the file next to the app's sources.
"""
import base64
import hashlib
import io
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "AirBridge", "Identity", "AirBridge.Identity.msix")
LOGO = os.path.join(ROOT, "AirBridge.Package", "Images", "Square150x150Logo.scale-100.png")

PUBLISHER = "CN=AirBridge, OID.2.25.311729368913984317654407730594956997722=1"
NAME = "AirBridge.Identity"
APP_ID = "AirBridge"


def version():
    with open(os.path.join(ROOT, "AirBridge", "AirBridge.csproj"), encoding="utf-8") as f:
        text = f.read()
    v = text.split("<Version>")[1].split("</Version>")[0].strip()
    parts = (v.split(".") + ["0", "0", "0", "0"])[:4]
    return ".".join(parts)


MANIFEST = f"""<?xml version="1.0" encoding="utf-8"?>
<Package
  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
  xmlns:uap3="http://schemas.microsoft.com/appx/manifest/uap/windows10/3"
  xmlns:uap10="http://schemas.microsoft.com/appx/manifest/uap/windows10/10"
  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
  IgnorableNamespaces="uap uap3 uap10 rescap">
  <Identity Name="{NAME}" Publisher="{PUBLISHER}" Version="{version()}" ProcessorArchitecture="neutral" />
  <Properties>
    <DisplayName>AirBridge</DisplayName>
    <PublisherDisplayName>AirBridge</PublisherDisplayName>
    <Logo>logo.png</Logo>
    <uap10:AllowExternalContent>true</uap10:AllowExternalContent>
  </Properties>
  <Resources>
    <Resource Language="en-us" />
  </Resources>
  <Dependencies>
    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.19041.0" MaxVersionTested="10.0.26100.0" />
  </Dependencies>
  <Capabilities>
    <rescap:Capability Name="runFullTrust" />
    <rescap:Capability Name="unvirtualizedResources" />
    <uap3:Capability Name="userNotificationListener" />
  </Capabilities>
  <Applications>
    <Application Id="{APP_ID}" Executable="AirBridge.exe" uap10:TrustLevel="mediumIL" uap10:RuntimeBehavior="win32App">
      <uap:VisualElements DisplayName="AirBridge" Description="AirBridge" BackgroundColor="transparent"
        Square150x150Logo="logo.png" Square44x44Logo="logo.png" AppListEntry="none" />
    </Application>
  </Applications>
</Package>
"""

CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="png" ContentType="image/png"/>
<Override PartName="/AppxManifest.xml" ContentType="application/vnd.ms-appx.manifest+xml"/>
<Override PartName="/AppxBlockMap.xml" ContentType="application/vnd.ms-appx.blockmap+xml"/>
</Types>
"""

BLOCK = 64 * 1024


def entry(name):
    """A stored zip entry with a fixed date, so the file is the same every time it's built."""
    zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_STORED
    zi.create_system = 0
    return zi


def block_map(files):
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<BlockMap xmlns="http://schemas.microsoft.com/appx/2010/blockmap" HashMethod="http://www.w3.org/2001/04/xmlenc#sha256">']
    for name, data in files:
        lfh = 30 + len(name.encode("utf-8"))  # local file header: fixed part + name, no extra field
        out.append(f'<File Name="{name}" Size="{len(data)}" LfhSize="{lfh}">')
        for i in range(0, max(len(data), 1), BLOCK):
            h = base64.b64encode(hashlib.sha256(data[i:i + BLOCK]).digest()).decode()
            out.append(f'<Block Hash="{h}"/>')
        out.append("</File>")
    out.append("</BlockMap>")
    return "\n".join(out).encode("utf-8")


def build():
    with open(LOGO, "rb") as f:
        logo = f.read()
    payload = [("AppxManifest.xml", MANIFEST.encode("utf-8")), ("logo.png", logo)]
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for name, data in payload:
            z.writestr(entry(name), data)
        z.writestr(entry("AppxBlockMap.xml"), block_map(payload))
        z.writestr(entry("[Content_Types].xml"), CONTENT_TYPES.encode("utf-8"))
    data = buf.getvalue()
    # The block map's LfhSize must match what was written: check every local header has no extra field.
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for zi in z.infolist():
            off = zi.header_offset
            name_len = int.from_bytes(data[off + 26:off + 28], "little")
            extra_len = int.from_bytes(data[off + 28:off + 30], "little")
            assert extra_len == 0 and name_len == len(zi.filename.encode()), zi.filename
            assert zi.compress_type == zipfile.ZIP_STORED and zi.flag_bits & 0x08 == 0, zi.filename
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(data)
    print(f"{OUT}: {len(data)} bytes, version {version()}")


if __name__ == "__main__":
    build()
    sys.exit(0)
