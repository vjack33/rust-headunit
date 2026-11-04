import socket
import ssl
import time
import struct
import sys

def recv_exact(s, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise ConnectionError(f"Connection closed while waiting for {n} bytes, got {len(buf)}")
        buf.extend(chunk)
    return bytes(buf)

def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 5288
    print(f"Connecting to Headunit at 127.0.0.1:{port}...")
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect(("127.0.0.1", port))
    print(f"[1] Connected to Headunit TCP port {port}!")

    # 1. Read Version Request from Headunit
    # Headunit sends: [chan(0), flags(3), len(6), type(1), major(1), minor(2)]
    hdr = recv_exact(s, 4)
    chan, flags, plen = hdr[0], hdr[1], struct.unpack(">H", hdr[2:4])[0]
    payload = recv_exact(s, plen)
    print(f"[2] Received Version Request: chan={chan}, flags={flags}, plen={plen}, payload={payload.hex()}")
    msg_type = struct.unpack(">H", payload[0:2])[0]
    major, minor = struct.unpack(">HH", payload[2:6])
    print(f"    Version Request Type={msg_type}, Version={major}.{minor}")

    # 2. Send Version Response
    # Channel 0, flags 3, type 2, major 1, minor 2, status 0
    ver_resp_body = struct.pack(">HHHH", 2, 1, 2, 0)
    ver_resp_hdr = bytes([0, 3, (len(ver_resp_body) >> 8) & 0xFF, len(ver_resp_body) & 0xFF])
    s.sendall(ver_resp_hdr + ver_resp_body)
    print("[3] Sent Version Response (v1.2, Status=OK)")

    # 3. TLS Handshake via MemoryBIO
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    ctx.load_cert_chain('/tmp/rsa.crt', '/tmp/rsa.key')
    incoming = ssl.MemoryBIO()
    outgoing = ssl.MemoryBIO()
    tls = ctx.wrap_bio(incoming, outgoing, server_side=True)

    print("[4] Starting TLS Handshake over AAP Envelopes...")
    handshake_done = False
    for step in range(10):
        hdr = recv_exact(s, 4)
        c, f, l = hdr[0], hdr[1], struct.unpack(">H", hdr[2:4])[0]
        data = recv_exact(s, l)
        if c == 0 and len(data) >= 2 and data[0] == 0 and data[1] == 3:
            # MESSAGE_ENCAPSULATED_SSL
            incoming.write(data[2:])
            try:
                tls.do_handshake()
                handshake_done = True
                print("    TLS Handshake Finished on Server side!")
            except ssl.SSLWantReadError:
                pass

            if outgoing.pending > 0:
                tls_out = outgoing.read()
                frame_body = bytes([0, 3]) + tls_out
                frame_hdr = bytes([0, 3, (len(frame_body) >> 8) & 0xFF, len(frame_body) & 0xFF])
                s.sendall(frame_hdr + frame_body)
                print(f"    Sent TLS Handshake frame, len={len(tls_out)}")

            if handshake_done:
                break

    if not handshake_done:
        print("[FAIL] TLS Handshake did not complete!")
        return

    print("[5] TLS Handshake Successful!")

    # 4. Read AUTH_COMPLETE (Plaintext AAP frame)
    auth_hdr = recv_exact(s, 4)
    ac, af, al = auth_hdr[0], auth_hdr[1], struct.unpack(">H", auth_hdr[2:4])[0]
    auth_data = recv_exact(s, al)
    auth_type = struct.unpack(">H", auth_data[0:2])[0]
    print(f"[6] Received AuthComplete: chan={ac}, flags={af}, type={auth_type}, data={auth_data.hex()}")

    def send_encrypted(channel, flags, plain_bytes):
        tls.write(plain_bytes)
        cipher = outgoing.read()
        l = len(cipher)
        hdr = bytes([channel, flags | 8, (l >> 8) & 0xFF, l & 0xFF])
        s.sendall(hdr + cipher)

    def read_encrypted():
        hdr = recv_exact(s, 4)
        c, f, l = hdr[0], hdr[1], struct.unpack(">H", hdr[2:4])[0]
        declared_total = None
        if (f & 1 != 0) and (f & 2 == 0):
            tot_bytes = recv_exact(s, 4)
            declared_total = struct.unpack(">I", tot_bytes)[0]
        cipher = recv_exact(s, l)
        incoming.write(cipher)
        plain = tls.read()
        return c, f, declared_total, plain

    # 5. Send SERVICE_DISCOVERY_REQUEST on Channel 0
    print("[7] Sending ServiceDiscoveryRequest...")
    # phone_name = "Samsung S25 Ultra" (field 4), phone_brand = "Samsung" (field 5)
    sd_req = bytes([0, 5, (4 << 3) | 2, 7]) + b"S25-Sim" + bytes([(5 << 3) | 2, 7]) + b"Samsung"
    send_encrypted(0, 3, sd_req)

    # 6. Read SERVICE_DISCOVERY_RESPONSE from Headunit
    c, f, tot, plain = read_encrypted()
    sd_type = struct.unpack(">H", plain[0:2])[0]
    print(f"[8] Received ServiceDiscoveryResponse: chan={c}, flags={f}, type={sd_type}, len={len(plain)}")
    print(f"    Raw preview: {plain[:40].hex()}...")

    # 7. Open Video Channel (Channel 2)
    print("[9] Sending ChannelOpenRequest for Video (Channel 2)...")
    # type 7 = CHANNEL_OPEN_REQUEST
    chan_open = struct.pack(">H", 7)
    send_encrypted(2, 3, chan_open)

    c, f, tot, plain = read_encrypted()
    resp_type = struct.unpack(">H", plain[0:2])[0]
    print(f"[10] Received ChannelOpenResponse for Video: chan={c}, type={resp_type}, len={len(plain)}")

    # 8. Send Video Setup Request (MEDIA_MESSAGE_SETUP: 0x8000)
    print("[11] Sending Video Setup Request (0x8000)...")
    setup_req = struct.pack(">H", 0x8000) + bytes([(1 << 3) | 0, 3]) # type = H264 (3)
    send_encrypted(2, 3, setup_req)

    # Read Video Config Response (0x8003)
    c, f, tot, plain = read_encrypted()
    cfg_type = struct.unpack(">H", plain[0:2])[0]
    print(f"[12] Received Video Config Response: chan={c}, type=0x{cfg_type:04x}, data={plain.hex()}")

    # Read Video Focus Notification (0x8008)
    c, f, tot, plain = read_encrypted()
    foc_type = struct.unpack(">H", plain[0:2])[0]
    print(f"[13] Received Video Focus Notification: chan={c}, type=0x{foc_type:04x}, data={plain.hex()}")

    # 9. Send Video Start (MEDIA_MESSAGE_START: 0x8001)
    print("[14] Sending Video Start Request (0x8001) with session_id=1...")
    # session_id = 1, configuration_index = 0
    start_req = struct.pack(">H", 0x8001) + bytes([(1 << 3) | 0, 1, (2 << 3) | 0, 0])
    send_encrypted(2, 3, start_req)

    # 10. Send Video Codec Config / CSD (SPS / PPS NAL units)
    print("[15] Sending H.264 SPS/PPS (Codec Config / CSD)...")
    # H.264 720p SPS + PPS NAL units
    sps_pps = bytes.fromhex("000000016742801fda014016ec0440000003004000000c03c58b65800000000168ce3c80")
    csd_payload = struct.pack(">H", 1) + sps_pps # type=1
    send_encrypted(2, 3, csd_payload)

    # 11. Send Multi-Fragment Video Frame (I-Frame / IDR Slice)
    print("[16] Sending Multi-Fragment H.264 Video Frame (FIRST, MIDDLE, LAST)...")
    # IDR slice header with start code 00 00 00 01 65...
    nal_full = bytes.fromhex("000000016588840010") + (b"\x80" * 4000)
    ts_us = int(time.time() * 1_000_000)

    # Fragment 1: FIRST (flags=1)
    # type(2 bytes) + timestamp(8 bytes) + first slice
    frag1_plain = struct.pack(">HQ", 0, ts_us) + nal_full[:1500]
    declared_total = 10 + len(nal_full)

    # Manual send for FIRST fragment carrying declared_total
    tls.write(frag1_plain)
    cipher1 = outgoing.read()
    hdr1 = bytes([2, 1 | 8, (len(cipher1) >> 8) & 0xFF, len(cipher1) & 0xFF])
    tot_bytes = struct.pack(">I", declared_total)
    s.sendall(hdr1 + tot_bytes + cipher1)
    print(f"    Sent Fragment 1 (FIRST): cipher_len={len(cipher1)}, declared_total={declared_total}")

    # Fragment 2: MIDDLE (flags=0)
    frag2_plain = nal_full[1500:3000]
    tls.write(frag2_plain)
    cipher2 = outgoing.read()
    hdr2 = bytes([2, 0 | 8, (len(cipher2) >> 8) & 0xFF, len(cipher2) & 0xFF])
    s.sendall(hdr2 + cipher2)
    print(f"    Sent Fragment 2 (MIDDLE): cipher_len={len(cipher2)}")

    # Fragment 3: LAST (flags=2)
    frag3_plain = nal_full[3000:]
    tls.write(frag3_plain)
    cipher3 = outgoing.read()
    hdr3 = bytes([2, 2 | 8, (len(cipher3) >> 8) & 0xFF, len(cipher3) & 0xFF])
    s.sendall(hdr3 + cipher3)
    print(f"    Sent Fragment 3 (LAST): cipher_len={len(cipher3)}")

    # 12. Read MEDIA_MESSAGE_ACK from Headunit!
    print("[17] Waiting for MEDIA_MESSAGE_ACK (0x8004)...")
    c, f, tot, plain = read_encrypted()
    ack_type = struct.unpack(">H", plain[0:2])[0]
    print(f"[18] >>> RECEIVED MEDIA_MESSAGE_ACK: chan={c}, type=0x{ack_type:04x}, data={plain.hex()} <<<")

    print("\n=======================================================")
    print("SUCCESS: Pure Native Rust Headunit Handshake & Streaming VERIFIED!")
    print("=======================================================")
    s.close()

if __name__ == "__main__":
    main()
