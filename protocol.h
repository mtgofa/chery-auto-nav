#ifndef CHERY_NAV_PROTOCOL_H
#define CHERY_NAV_PROTOCOL_H

#ifdef _WIN32
#include <windows.h>
#else
#include <stdint.h>
#endif

#pragma pack(push, 1)

// Magic bytes: 'CHRY'
#define CHERY_MAGIC 0x43485259

// Packet Opcodes
#define PKT_TYPE_HEARTBEAT       0x0001
#define PKT_TYPE_TELEMETRY       0x0002
#define PKT_TYPE_NAV_INSTRUCTION 0x0003
#define PKT_TYPE_MAP_IMAGE       0x0004
#define PKT_TYPE_ROUTE_INFO      0x0005
#define PKT_TYPE_ROUTE_POLYLINE  0x0006
#define PKT_TYPE_MAP_TILE        0x0007

// Client -> Server Opcodes (Car to Phone)
#define PKT_TYPE_TOUCH_EVENT     0x0010
#define PKT_TYPE_CLIENT_STATUS   0x0011
#define PKT_TYPE_TILE_ACK        0x0012

// Maneuver Types for Turn-by-Turn
#define MANEUVER_NONE            0
#define MANEUVER_STRAIGHT        1
#define MANEUVER_SLIGHT_LEFT     2
#define MANEUVER_LEFT            3
#define MANEUVER_SHARP_LEFT      4
#define MANEUVER_SLIGHT_RIGHT    5
#define MANEUVER_RIGHT           6
#define MANEUVER_SHARP_RIGHT     7
#define MANEUVER_UTURN           8
#define MANEUVER_ROUNDABOUT      9
#define MANEUVER_DESTINATION     10

// Image Formats
#define IMG_FMT_RGB565           0  // 16-bit 5-6-5 raw (Best for WinCE performance)
#define IMG_FMT_RGB24            1  // 24-bit BGR
#define IMG_FMT_BMP              2  // Standard Windows DIB/BMP with header
#define IMG_FMT_JPEG             3  // Compressed JPEG

// Common Packet Header
typedef struct {
    unsigned int  magic;       // 0x43485259 ('CHRY')
    unsigned short opcode;     // PKT_TYPE_*
    unsigned int  payloadLen;  // Length of struct following header
} NavPacketHeader;

// 1. Heartbeat
typedef struct {
    unsigned int  phoneTimestamp;
    unsigned char batteryLevel; // 0-100%
    unsigned char isCharging;   // 1 if charging, 0 otherwise
    unsigned char wifiSignal;   // 0-4 bars
    unsigned char btConnected;  // 1 if BT connected
} NavHeartbeatPayload;

// 2. GPS & Telemetry
typedef struct {
    double        latitude;
    double        longitude;
    float         speedKmh;
    float         bearingDeg;   // 0-360 deg
    float         altitudeM;
    float         accuracyM;
    unsigned char hasFix;       // 1 = valid GPS fix
    unsigned char numSats;
} NavTelemetryPayload;

// 3. Navigation Turn-by-Turn Guidance
typedef struct {
    unsigned short maneuverType;           // MANEUVER_*
    unsigned int   distanceToTurnM;        // Meters to next turn
    unsigned int   remainingDistanceM;     // Total trip remaining distance
    unsigned int   remainingTimeSec;       // Total trip remaining seconds
    char           streetName[64];         // Next road name (UTF-8)
    char           instruction[128];       // Voice/Text instruction (e.g. "Turn left onto Ring Road")
} NavInstructionPayload;

// 4. Map Image Frame / Tile
typedef struct {
    unsigned int   frameSeq;
    unsigned short width;
    unsigned short height;
    unsigned char  format;                 // IMG_FMT_*
    unsigned short carX;                   // Car icon X inside this image
    unsigned short carY;                   // Car icon Y inside this image
    float          heading;                // Direction of map rotation
    unsigned int   imageBytes;             // Size of following raw image data
} NavMapImagePayload;

// 5. Touch Event (Car Screen -> Phone)
typedef struct {
    unsigned short action;                 // 0=Down, 1=Up, 2=Move, 3=ZoomIn, 4=ZoomOut, 5=Recenter, 6=ModeToggle
    unsigned short x;
    unsigned short y;
    unsigned int   timestamp;
} NavTouchEventPayload;

// 6. Vector Route Polyline (Ideal for Bluetooth SPP - lightweight & fast)
#define MAX_POLYLINE_POINTS 80
typedef struct {
    float latOffset; // Relative offset in degrees (lat - carLat)
    float lonOffset; // Relative offset in degrees (lon - carLon)
} NavRoutePoint;

typedef struct {
    unsigned short count;
    NavRoutePoint  points[MAX_POLYLINE_POINTS];
} NavRoutePolylinePayload;

// 7. Slippy Map Tile (Phone -> Car)
typedef struct {
    unsigned char  zoom;        // Map Zoom level (e.g. 15 or 16)
    unsigned int   tileX;       // Slippy tile X
    unsigned int   tileY;       // Slippy tile Y
    unsigned char  format;      // IMG_FMT_* (IMG_FMT_JPEG, IMG_FMT_PNG)
    unsigned int   imageBytes;  // Length of compressed image data immediately following
} NavMapTilePayload;

// 8. Tile Reception Acknowledgment (Car -> Phone)
typedef struct {
    unsigned char  zoom;
    unsigned int   tileX;
    unsigned int   tileY;
    unsigned char  status;      // 1 = Saved successfully, 0 = Failed
} NavTileAckPayload;

#pragma pack(pop)

#endif // CHERY_NAV_PROTOCOL_H
