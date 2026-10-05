#ifndef BT_CLIENT_H
#define BT_CLIENT_H

#include <windows.h>
#include "../protocol.h"
#include "map_cache.h"

class NavBtClient {
public:
    NavBtClient();
    ~NavBtClient();

    // Start background Bluetooth thread
    BOOL Start(const TCHAR* portName, MapCacheManager* pCache, HWND hwndNotify);
    void Stop();

    BOOL IsConnected() const { return m_connected; }
    const TCHAR* GetStatusText() const { return m_statusText; }
    const TCHAR* GetPortName() const { return m_activePort; }

    // Thread-safe access to latest state
    void GetLatestTelemetry(NavTelemetryPayload* pOut);
    void GetLatestInstruction(NavInstructionPayload* pOut);
    void GetLatestHeartbeat(NavHeartbeatPayload* pOut);
    int  GetLatestPolyline(NavRoutePolylinePayload* pOut);

    // Send touch event back to phone
    BOOL SendTouch(unsigned short action, unsigned short x, unsigned short y);

    // Send tile receipt acknowledgment back to phone
    BOOL SendTileAck(unsigned char zoom, unsigned int tileX, unsigned int tileY, unsigned char status);

private:
    static DWORD WINAPI ThreadProc(LPVOID lpParam);
    void WorkerLoop();

    HANDLE TryOpenBtPort();
    BOOL ConfigureSerial(HANDLE h);
    BOOL ReadExact(HANDLE h, void* buf, DWORD bytes);

    HANDLE               m_hComm;
    HANDLE               m_hThread;
    BOOL                 m_running;
    BOOL                 m_connected;
    TCHAR                m_activePort[32];
    HWND                 m_hwndNotify;
    MapCacheManager*     m_pCache;
    TCHAR                m_statusText[128];

    CRITICAL_SECTION     m_csState;
    NavTelemetryPayload  m_telemetry;
    NavInstructionPayload m_instruction;
    NavHeartbeatPayload  m_heartbeat;
    NavRoutePolylinePayload m_polyline;
    BOOL                 m_hasTelemetry;
    BOOL                 m_hasInstruction;
    BOOL                 m_hasHeartbeat;
    BOOL                 m_hasPolyline;

    unsigned char*       m_pPayloadBuffer;
    int                  m_payloadBufferSize;
};

#endif // BT_CLIENT_H
