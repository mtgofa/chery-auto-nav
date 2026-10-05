#!/usr/bin/env python3
"""
Chery AutoNav - Car Client Simulator (Python)
Simulates the WinCE car screen client:
- Connects to Phone IP:5555
- Unpacks Telemetry, Instructions, Heartbeat, and Map frames
- Validates data flow and response
"""

import socket
import struct
import sys
import time

CHERY_MAGIC = 0x43485259

def main():
    host = sys.argv[1] if len(sys.argv) > 1 else '127.0.0.1'
    port = 5555

    print(f"Connecting to Car Bridge Server at {host}:{port}...")
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.connect((host, port))
    except Exception as e:
        print(f"Connection failed: {e}")
        return

    print("Connected successfully! Listening for navigation packets...\n")

    frameCount = 0
    try:
        while True:
            # Read 10-byte header
            hdr = s.recv(10)
            if not hdr or len(hdr) < 10:
                break
            magic, opcode, plen = struct.unpack('<IHI', hdr)
            if magic != CHERY_MAGIC:
                print(f"Bad magic: {hex(magic)}")
                break

            # Read payload
            payload = bytearray()
            while len(payload) < plen:
                chunk = s.recv(min(plen - len(payload), 65536))
                if not chunk:
                    break
                payload.extend(chunk)

            if opcode == 0x0001: # Heartbeat
                ts, bat, chg, wifi, bt = struct.unpack('<IBBBB', payload[:8])
                print(f"[Heartbeat] Battery: {bat}% | WiFi: {wifi} | BT: {bt}")

            elif opcode == 0x0002: # Telemetry
                lat, lon, speed, bearing, alt, acc, fix, sats = struct.unpack('<ddffffBB', payload[:34])
                print(f"[Telemetry] Speed: {speed:.1f} km/h | Heading: {bearing:.1f}° | Lat: {lat:.5f}, Lon: {lon:.5f}")

            elif opcode == 0x0003: # Nav Instruction
                maneuver, dist, rem_d, rem_t, street, instr = struct.unpack('<HIII64s128s', payload[:206])
                street_str = street.split(b'\x00')[0].decode('utf-8', errors='ignore')
                instr_str = instr.split(b'\x00')[0].decode('utf-8', errors='ignore')
                print(f"[Nav Turn ] In {dist}m -> '{street_str}' ({instr_str}) | Rem: {rem_d}m, {rem_t}s")

            elif opcode == 0x0004: # Map Image
                seq, w, h, fmt, cx, cy, head, img_len = struct.unpack('<IHHBHHfI', payload[:21])
                frameCount += 1
                if frameCount % 10 == 0:
                    print(f"[Map Frame] Received Frame #{seq}: {w}x{h} ({img_len} bytes) - Car at ({cx}, {cy})")

    except KeyboardInterrupt:
        print("\nDisconnecting...")
    finally:
        s.close()

if __name__ == '__main__':
    main()
