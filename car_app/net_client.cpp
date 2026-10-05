#include "net_client.h"
#include <stdio.h>

#pragma comment(lib, "ws2.lib")

#define WM_NAV_UPDATE (WM_USER + 100)

NavNetClient::NavNetClient()
{
    m_socket = INVALID_SOCKET;
    m_hThread = NULL;
    m_running = FALSE;
    m_connected = FALSE;
    m_hwndNotify = NULL;
    m_pCache = NULL;
    strcpy(m_phoneIp, "192.168.43.1");
    m_port = 5555;
    strcpy(m_statusText, "Disconnected");

    InitializeCriticalSection(&m_csState);
    ZeroMemory(&m_telemetry, sizeof(m_telemetry));
    ZeroMemory(&m_instruction, sizeof(m_instruction));
    ZeroMemory(&m_heartbeat, sizeof(m_heartbeat));
    m_hasTelemetry = FALSE;
    m_hasInstruction = FALSE;
    m_hasHeartbeat = FALSE;

    m_payloadBufferSize = 256 * 1024; // 256 KB buffer for map images
    m_pPayloadBuffer = (unsigned char*)malloc(m_payloadBufferSize);
}

NavNetClient::~NavNetClient()
{
    Stop();
    if (m_pPayloadBuffer) {
        free(m_pPayloadBuffer);
        m_pPayloadBuffer = NULL;
    }
    DeleteCriticalSection(&m_csState);
}

BOOL NavNetClient::Start(const char* phoneIp, int port, MapCacheManager* pCache, HWND hwndNotify)
{
    if (m_running) return TRUE;

    if (phoneIp && phoneIp[0]) {
        strncpy(m_phoneIp, phoneIp, sizeof(m_phoneIp) - 1);
        m_phoneIp[sizeof(m_phoneIp) - 1] = '\0';
    }
    m_port = (port > 0) ? port : 5555;
    m_pCache = pCache;
    m_hwndNotify = hwndNotify;
    m_running = TRUE;

    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);

    DWORD threadId;
    m_hThread = CreateThread(NULL, 0, ThreadProc, this, 0, &threadId);
    return (m_hThread != NULL);
}

void NavNetClient::Stop()
{
    m_running = FALSE;
    if (m_socket != INVALID_SOCKET) {
        closesocket(m_socket);
        m_socket = INVALID_SOCKET;
    }

    if (m_hThread) {
        WaitForSingleObject(m_hThread, 2000);
        CloseHandle(m_hThread);
        m_hThread = NULL;
    }

    WSACleanup();
    m_connected = FALSE;
    strcpy(m_statusText, "Stopped");
}

DWORD WINAPI NavNetClient::ThreadProc(LPVOID lpParam)
{
    NavNetClient* pThis = (NavNetClient*)lpParam;
    pThis->WorkerLoop();
    return 0;
}

BOOL NavNetClient::ReadExact(SOCKET s, void* buf, int bytes)
{
    int total = 0;
    char* p = (char*)buf;
    while (total < bytes && m_running) {
        int n = recv(s, p + total, bytes - total, 0);
        if (n <= 0) return FALSE;
        total += n;
    }
    return (total == bytes);
}

void NavNetClient::WorkerLoop()
{
    while (m_running) {
        m_connected = FALSE;
        sprintf(m_statusText, "Connecting to %s:%d...", m_phoneIp, m_port);
        if (m_hwndNotify) PostMessage(m_hwndNotify, WM_NAV_UPDATE, 0, 0);

        m_socket = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
        if (m_socket == INVALID_SOCKET) {
            Sleep(2000);
            continue;
        }

        // Set socket timeout to 5 seconds
        int timeoutMs = 5000;
        setsockopt(m_socket, SOL_SOCKET, SO_RCVTIMEO, (const char*)&timeoutMs, sizeof(timeoutMs));

        sockaddr_in sa;
        ZeroMemory(&sa, sizeof(sa));
        sa.sin_family = AF_INET;
        sa.sin_port = htons((unsigned short)m_port);
        sa.sin_addr.s_addr = inet_addr(m_phoneIp);

        if (connect(m_socket, (sockaddr*)&sa, sizeof(sa)) != 0) {
            closesocket(m_socket);
            m_socket = INVALID_SOCKET;
            sprintf(m_statusText, "Connect failed. Retrying...");
            if (m_hwndNotify) PostMessage(m_hwndNotify, WM_NAV_UPDATE, 0, 0);
            Sleep(2500);
            continue;
        }

        m_connected = TRUE;
        sprintf(m_statusText, "Connected to Phone");
        if (m_hwndNotify) PostMessage(m_hwndNotify, WM_NAV_UPDATE, 0, 0);

        // Connected! Read packet loop
        while (m_running && m_connected) {
            NavPacketHeader hdr;
            if (!ReadExact(m_socket, &hdr, sizeof(hdr))) {
                break; // Connection lost
            }

            if (hdr.magic != CHERY_MAGIC) {
                // Invalid magic byte stream, disconnect and reconnect
                break;
            }

            // Ensure buffer can hold payload
            if (hdr.payloadLen > (unsigned int)m_payloadBufferSize) {
                m_payloadBufferSize = hdr.payloadLen + 32768;
                m_pPayloadBuffer = (unsigned char*)realloc(m_pPayloadBuffer, m_payloadBufferSize);
            }

            if (hdr.payloadLen > 0) {
                if (!ReadExact(m_socket, m_pPayloadBuffer, hdr.payloadLen)) {
                    break;
                }
            }

            // Dispatch packet based on opcode
            switch (hdr.opcode) {
            case PKT_TYPE_HEARTBEAT:
                if (hdr.payloadLen >= sizeof(NavHeartbeatPayload)) {
                    EnterCriticalSection(&m_csState);
                    memcpy(&m_heartbeat, m_pPayloadBuffer, sizeof(NavHeartbeatPayload));
                    m_hasHeartbeat = TRUE;
                    LeaveCriticalSection(&m_csState);
                }
                break;

            case PKT_TYPE_TELEMETRY:
                if (hdr.payloadLen >= sizeof(NavTelemetryPayload)) {
                    EnterCriticalSection(&m_csState);
                    memcpy(&m_telemetry, m_pPayloadBuffer, sizeof(NavTelemetryPayload));
                    m_hasTelemetry = TRUE;
                    LeaveCriticalSection(&m_csState);
                }
                break;

            case PKT_TYPE_NAV_INSTRUCTION:
                if (hdr.payloadLen >= sizeof(NavInstructionPayload)) {
                    EnterCriticalSection(&m_csState);
                    memcpy(&m_instruction, m_pPayloadBuffer, sizeof(NavInstructionPayload));
                    m_hasInstruction = TRUE;
                    LeaveCriticalSection(&m_csState);
                }
                break;

            case PKT_TYPE_MAP_IMAGE:
                if (hdr.payloadLen >= sizeof(NavMapImagePayload) && m_pCache) {
                    NavMapImagePayload* pImgHdr = (NavMapImagePayload*)m_pPayloadBuffer;
                    unsigned char* pImgData = m_pPayloadBuffer + sizeof(NavMapImagePayload);
                    HDC hdcScr = GetDC(NULL);
                    m_pCache->StoreFrame(pImgHdr, pImgData, hdcScr);
                    ReleaseDC(NULL, hdcScr);
                }
                break;
            }

            // Notify UI
            if (m_hwndNotify) {
                PostMessage(m_hwndNotify, WM_NAV_UPDATE, (WPARAM)hdr.opcode, 0);
            }
        }

        if (m_socket != INVALID_SOCKET) {
            closesocket(m_socket);
            m_socket = INVALID_SOCKET;
        }
        m_connected = FALSE;
        sprintf(m_statusText, "Phone disconnected. Reconnecting...");
        if (m_hwndNotify) PostMessage(m_hwndNotify, WM_NAV_UPDATE, 0, 0);
        Sleep(2000);
    }
}

void NavNetClient::GetLatestTelemetry(NavTelemetryPayload* pOut)
{
    if (!pOut) return;
    EnterCriticalSection(&m_csState);
    *pOut = m_telemetry;
    LeaveCriticalSection(&m_csState);
}

void NavNetClient::GetLatestInstruction(NavInstructionPayload* pOut)
{
    if (!pOut) return;
    EnterCriticalSection(&m_csState);
    *pOut = m_instruction;
    LeaveCriticalSection(&m_csState);
}

void NavNetClient::GetLatestHeartbeat(NavHeartbeatPayload* pOut)
{
    if (!pOut) return;
    EnterCriticalSection(&m_csState);
    *pOut = m_heartbeat;
    LeaveCriticalSection(&m_csState);
}

BOOL NavNetClient::SendTouch(unsigned short action, unsigned short x, unsigned short y)
{
    if (!m_connected || m_socket == INVALID_SOCKET) return FALSE;

    NavPacketHeader hdr;
    hdr.magic = CHERY_MAGIC;
    hdr.opcode = PKT_TYPE_TOUCH_EVENT;
    hdr.payloadLen = sizeof(NavTouchEventPayload);

    NavTouchEventPayload payload;
    payload.action = action;
    payload.x = x;
    payload.y = y;
    payload.timestamp = GetTickCount();

    int sent1 = send(m_socket, (const char*)&hdr, sizeof(hdr), 0);
    int sent2 = send(m_socket, (const char*)&payload, sizeof(payload), 0);
    return (sent1 == sizeof(hdr) && sent2 == sizeof(payload));
}
