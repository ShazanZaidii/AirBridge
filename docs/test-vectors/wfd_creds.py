import base64, hashlib, hmac, json, sys
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes

def manual_hkdf(ikm, salt, info, n):
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    out, t, i = b"", b"", 1
    while len(out) < n:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest(); out += t; i += 1
    return out[:n]

def derive(k, host, client, gen):
    info = host.encode() + b"|" + client.encode() + b"|" + bytes([gen])
    wk = HKDF(algorithm=hashes.SHA256(), length=32, salt=b"airbridge/v2/wfd", info=info).derive(k)
    assert wk == manual_hkdf(k, b"airbridge/v2/wfd", info, 32)
    ssid_mac = hmac.new(wk, b"ssid", hashlib.sha256).digest()
    psk_mac = hmac.new(wk, b"psk", hashlib.sha256).digest()
    ssid = "DIRECT-AB-" + base64.b32encode(ssid_mac[:5]).decode().rstrip("=")
    psk = base64.urlsafe_b64encode(psk_mac[:24]).decode().rstrip("=")
    assert len(ssid) == 18 and len(psk) == 32
    return {"wk": wk.hex(), "ssid": ssid, "psk": psk}

host = "22222222-2222-4222-8222-222222222222"
client = "11111111-1111-4111-8111-111111111111"
cases = []
for name, k, h, c, gen in [
    ("k_seq_gen0", bytes(range(32)), host, client, 0),
    ("k_seq_gen1", bytes(range(32)), host, client, 1),
    ("k_seq_roles_swapped", bytes(range(32)), client, host, 0),
    ("k_a5_gen255", bytes([0xA5]*32), host, client, 255),
    ("k_ff_real_ids", bytes([0xFF]*32), "0f9c2d4e-8b1a-4c3d-9e7f-1a2b3c4d5e6f", "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", 7),
]:
    r = derive(k, h, c, gen)
    cases.append({"name": name, "k": k.hex(), "hostId": h, "clientId": c, "gen": gen, **r})
print(json.dumps({
  "description": "AirBridge PROTOCOL.md §10.1 Wi-Fi Direct credential derivation test vectors. Computed with Python 'cryptography' HKDF (cross-checked against a stdlib-hmac RFC 5869 implementation).",
  "spec": "wk = HKDF-SHA256(ikm=K, salt=\"airbridge/v2/wfd\", info=hostId|\"|\"|clientId|\"|\"|u8(gen), 32); ssid = \"DIRECT-AB-\" + base32(HMAC-SHA256(wk,\"ssid\")[0..5)) (RFC 4648, no padding); psk = base64url(HMAC-SHA256(wk,\"psk\")[0..24)) (no padding)",
  "encoding": {"k": "hex", "wk": "hex", "ids": "UTF-8 strings, lowercase as on the wire", "gen": "integer 0..255, one byte"},
  "cases": cases}, indent=2))
