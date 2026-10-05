#!/usr/bin/env python3
"""
Chery AutoNav - Phone Server Simulator (Python)
Simulates the Android phone app:
- Listens on TCP port 5555
- Generates realistic navigation route simulation (Tahrir -> Cairo Airport)
- Streams:
    * PKT_TYPE_TELEMETRY (10 Hz)
    * PKT_TYPE_NAV_INSTRUCTION (2 Hz)
    * PKT_TYPE_MAP_IMAGE (2 Hz, RGB565 format)
    * PKT_TYPE_HEARTBEAT (1 Hz)
- Receives touch events from car screen!
"""

import socket
import struct
import time
import math
import sys
import threading

CHERY_MAGIC = 0x43485259

# Opcodes
PKT_TYPE_HEARTBEAT       = 0x0001
PKT_TYPE_TELEMETRY       = 0x0002
PKT_TYPE_NAV_INSTRUCTION = 0x0003
PKT_TYPE_MAP_IMAGE       = 0x0004
PKT_TYPE_ROUTE_INFO      = 0x0005
PKT_TYPE_ROUTE_POLYLINE  = 0x0006
PKT_TYPE_MAP_TILE        = 0x0007
PKT_TYPE_TOUCH_EVENT     = 0x0010
PKT_TYPE_CLIENT_STATUS   = 0x0011
PKT_TYPE_TILE_ACK        = 0x0012

# Maneuvers
MANEUVER_STRAIGHT     = 1
MANEUVER_SLIGHT_LEFT  = 2
MANEUVER_LEFT         = 3
MANEUVER_RIGHT        = 6
MANEUVER_ROUNDABOUT   = 9
MANEUVER_DESTINATION  = 10

def make_header(opcode, payload_len):
    # struct NavPacketHeader: uint32 magic, uint16 opcode, uint32 payloadLen
    return struct.pack('<IHI', CHERY_MAGIC, opcode, payload_len)

def pack_heartbeat(battery=88, is_charging=0, wifi=4, bt=1):
    timestamp = int(time.time())
    payload = struct.pack('<IBBBB', timestamp, battery, is_charging, wifi, bt)
    return make_header(PKT_TYPE_HEARTBEAT, len(payload)) + payload

def pack_telemetry(lat, lon, speed_kmh, bearing_deg, alt=25.0, acc=4.0, has_fix=1, num_sats=14):
    payload = struct.pack('<ddffffBB', lat, lon, speed_kmh, bearing_deg, alt, acc, has_fix, num_sats)
    return make_header(PKT_TYPE_TELEMETRY, len(payload)) + payload

def pack_instruction(maneuver, dist_turn, rem_dist, rem_time, street, instr):
    street_bytes = street.encode('utf-8')[:63].ljust(64, b'\x00')
    instr_bytes = instr.encode('utf-8')[:127].ljust(128, b'\x00')
    payload = struct.pack('<HIII64s128s', maneuver, dist_turn, rem_dist, rem_time, street_bytes, instr_bytes)
    return make_header(PKT_TYPE_NAV_INSTRUCTION, len(payload)) + payload

PKT_TYPE_ROUTE_POLYLINE = 0x0006

def pack_route_polyline(car_lat, car_lon, points):
    count = min(len(points), 80)
    pts_bytes = bytearray()
    for i in range(count):
        p_lat, p_lon = points[i]
        pts_bytes += struct.pack('<ff', float(p_lat - car_lat), float(p_lon - car_lon))
    payload = struct.pack('<H', count) + bytes(pts_bytes)
    return make_header(PKT_TYPE_ROUTE_POLYLINE, len(payload)) + payload

def pack_map_tile(zoom, tile_x, tile_y, img_bytes, fmt=3):
    payload = struct.pack('<BIIBI', zoom, tile_x, tile_y, fmt, len(img_bytes)) + img_bytes
    return make_header(PKT_TYPE_MAP_TILE, len(payload)) + payload

def create_synthetic_rgb565_map(width=580, height=480, car_x=290, car_y=312, heading=45.0, progress=0.0):
    """
    Creates an RGB565 raw map image with roads, grid, and navigation route.
    Fast synthetic generation in pure Python without extra dependencies.
    """
    total_pixels = width * height
    # Pre-allocate bytearray for RGB565 (2 bytes per pixel)
    buf = bytearray(total_pixels * 2)

    # Colors in RGB565 (R:5, G:6, B:5)
    COLOR_BG     = 0x10C3  # Dark grey-blue
    COLOR_GRID   = 0x1905  # Subtle grid
    COLOR_ROAD   = 0x3A2B  # Asphalt grey
    COLOR_ROUTE  = 0x06FF  # Bright Cyan / Neon Blue

    # Fill background
    bg_bytes = struct.pack('<H', COLOR_BG)
    for i in range(total_pixels):
        buf[i*2 : i*2+2] = bg_bytes

    # Draw grid lines
    grid_bytes = struct.pack('<H', COLOR_GRID)
    for y in range(0, height, 40):
        for x in range(width):
            idx = (y * width + x) * 2
            buf[idx : idx+2] = grid_bytes
    for x in range(0, width, 40):
        for y in range(height):
            idx = (y * width + x) * 2
            buf[idx : idx+2] = grid_bytes

    # Draw moving curved route polyline
    route_bytes = struct.pack('<H', COLOR_ROUTE)
    road_bytes = struct.pack('<H', COLOR_ROAD)

    # Center road
    for y in range(height):
        # Sine curve road
        cx = int(car_x + math.sin((y + progress * 200) * 0.02) * 50)
        for w in range(-12, 13):
            px = cx + w
            if 0 <= px < width:
                idx = (y * width + px) * 2
                buf[idx : idx+2] = road_bytes

        # Route glow center
        for w in range(-4, 5):
            px = cx + w
            if 0 <= px < width:
                idx = (y * width + px) * 2
                buf[idx : idx+2] = route_bytes

    return bytes(buf)

def pack_map_frame(frame_seq, width, height, car_x, car_y, heading, img_bytes):
    # uint32 frameSeq, uint16 width, uint16 height, uint8 format, uint16 carX, uint16 carY, float heading, uint32 imageBytes
    IMG_FMT_RGB565 = 0
    hdr = struct.pack('<IHHBHHfI', frame_seq, width, height, IMG_FMT_RGB565, car_x, car_y, heading, len(img_bytes))
    payload = hdr + img_bytes
    return make_header(PKT_TYPE_MAP_IMAGE, len(payload)) + payload

def handle_client(sock, addr):
    print(f"[+] Car Screen Connected from: {addr}")
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    # Thread to receive touches from car
    def reader_loop():
        try:
            while True:
                hdr_data = sock.recv(10)
                if not hdr_data or len(hdr_data) < 10:
                    break
                magic, opcode, plen = struct.unpack('<IHI', hdr_data)
                if magic != CHERY_MAGIC:
                    break
                payload = sock.recv(plen)
                if opcode == PKT_TYPE_TOUCH_EVENT and len(payload) >= 8:
                    action, x, y, ts = struct.unpack('<HHHI', payload[:10])
                    action_names = {0: "DOWN", 1: "UP", 2: "MOVE", 3: "ZOOM_IN", 4: "ZOOM_OUT", 5: "RECENTER", 6: "MODE"}
                    print(f"[*] Touch Event from Car Screen: {action_names.get(action, action)} at ({x}, {y})")
                elif opcode == PKT_TYPE_TILE_ACK and len(payload) >= 10:
                    zoom, tx, ty, status = struct.unpack('<BIIB', payload[:10])
                    print(f"[*] Tile ACK received: z={zoom}, x={tx}, y={ty} status={status} (Saved in car cache, won't resend)")
        except Exception as e:
            pass
        print(f"[-] Car Screen Reader closed for {addr}")

    t = threading.Thread(target=reader_loop, daemon=True)
    t.start()

    # Route Steps
    route_steps = [
        {"maneuver": MANEUVER_STRAIGHT,   "street": "Tahrir Square",       "instr": "Head straight onto Ramses St", "dist": 400},
        {"maneuver": MANEUVER_RIGHT,      "street": "Ramses St",           "instr": "Turn right onto Ramses St",    "dist": 1200},
        {"maneuver": MANEUVER_SLIGHT_LEFT,"street": "Salah Salem St",      "instr": "Take ramp to Salah Salem St",  "dist": 3500},
        {"maneuver": MANEUVER_ROUNDABOUT, "street": "Abbassia Square",     "instr": "Take 2nd exit on roundabout",  "dist": 1800},
        {"maneuver": MANEUVER_DESTINATION,"street": "Cairo Airport Rd",    "instr": "Arrived at Destination",       "dist": 0}
    ]

    base_lat = 30.0444
    base_lon = 31.2357
    speed = 78.0
    heading = 35.0
    frame_seq = 0
    start_time = time.time()
    step_idx = 0

    try:
        while True:
            elapsed = time.time() - start_time
            # Advance vehicle
            speed = 75.0 + 15.0 * math.sin(elapsed * 0.2)
            heading = (35.0 + 10.0 * math.sin(elapsed * 0.1)) % 360.0
            lat = base_lat + (elapsed * 0.0001)
            lon = base_lon + (elapsed * 0.00008)

            current_step = route_steps[step_idx]
            dist_turn = max(50, current_step["dist"] - int(elapsed * 20) % 2000)
            if dist_turn <= 60 and step_idx < len(route_steps) - 1:
                step_idx = (step_idx + 1) % len(route_steps)

            rem_dist = max(500, 16500 - int(elapsed * 25))
            rem_time = int(rem_dist / (max(speed, 20.0) / 3.6))

            # 1. Send Telemetry
            sock.sendall(pack_telemetry(lat, lon, speed, heading))

            # 2. Send Instruction
            sock.sendall(pack_instruction(
                current_step["maneuver"],
                dist_turn,
                rem_dist,
                rem_time,
                current_step["street"],
                current_step["instr"]
            ))

            # 3. Send Heartbeat
            sock.sendall(pack_heartbeat(battery=85, wifi=4, bt=1))

            # 4. Send Vector Route Polyline
            demo_pts = [(lat + 0.0005 * i, lon + 0.0004 * i) for i in range(30)]
            sock.sendall(pack_route_polyline(lat, lon, demo_pts))

            # 5. Render and Send Map Image (RGB565)
            frame_seq += 1
            img_data = create_synthetic_rgb565_map(580, 480, 290, 312, heading, elapsed)
            sock.sendall(pack_map_frame(frame_seq, 580, 480, 290, 312, heading, img_data))

            time.sleep(0.2) # ~5 FPS update rate

    except (BrokenPipeError, ConnectionResetError):
        print(f"[-] Client {addr} disconnected.")
    finally:
        sock.close()

def main():
    host = '0.0.0.0'
    port = 5555
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((host, port))
    server.listen(5)

    # Get local IP
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
    except Exception:
        local_ip = "127.0.0.1"

    print("=" * 60)
    print("  Chery AutoNav - Phone Server Simulator")
    print(f"  Listening on: {local_ip}:{port}")
    print("  Ready for CheryNav.exe to connect!")
    print("=" * 60)

    try:
        while True:
            client_sock, client_addr = server.accept()
            client_thread = threading.Thread(target=handle_client, args=(client_sock, client_addr), daemon=True)
            client_thread.start()
    except KeyboardInterrupt:
        print("\nStopping server.")
    finally:
        server.close()

if __name__ == '__main__':
    main()
