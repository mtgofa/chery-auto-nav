#ifndef NET_CLIENT_H
#define NET_CLIENT_H

#include <windows.h>
#include <winsock2.h>
#include "../protocol.h"
#include "map_cache.h"

class NavNetClient {
public:
    NavNetClient();
    ~NavNetClient();

    // Start background network thread
    BOOL Start(const char* phoneIp, int port, MapCacheManager* pCache, HWND hwndNotify);
    void Stop();

    BOOL IsConnected() const { return m_connected; }
    const char* GetStatusText() const { return m_statusText; }

    // Thread-safe access to latest state
    void GetLatestTelemetry(NavTelemetryPayload* pOut);
    void GetLatestInstruction(NavInstructionPayload* pOut);
    void GetLatestHeartbeat(NavHeartbeatPayload* pOut);

    // Send touch event back to phone
    BOOL SendTouch(unsigned short action, unsigned short x, unsigned short y);

private:
    static DWORD WINAPI ThreadProc(LPVOID lpParam);
    void WorkerLoop();

    BOOL ReadExact(SOCKET s, void* buf, int bytes);

    SOCKET               m_socket;
    HANDLE               m_hThread;
    BOOL                 m_running;
    BOOL                 m_connected;
    char                 m_phoneIp[64];
    int                  m_port;
    HWND                 m_hwndNotify;
    MapCacheManager*     m_pCache;
    char                 m_statusText[128];

    CRITICAL_SECTION     m_csState;
    NavTelemetryPayload  m_telemetry;
    NavInstructionPayload m_instruction;
    NavHeartbeatPayload  m_heartbeat;
    BOOL                 m_hasTelemetry;
    BOOL                 m_hasInstruction;
    BOOL                 m_hasHeartbeat;

    unsigned char*       m_pPayloadBuffer;
    int                  m_payloadBufferSize;
};

#endif // NET_CLIENT_H
