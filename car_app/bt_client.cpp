#include "bt_client.h"
#include <tchar.h>
#include <stdio.h>

#define WM_NAV_UPDATE (WM_USER + 100)

NavBtClient::NavBtClient()
{
    m_hComm = INVALID_HANDLE_VALUE;
    m_hThread = NULL;
    m_running = FALSE;
    m_connected = FALSE;
    m_hwndNotify = NULL;
    m_pCache = NULL;
    _tcscpy(m_activePort, TEXT("COM4:"));
    _tcscpy(m_statusText, TEXT("BT: Disconnected"));

    InitializeCriticalSection(&m_csState);
    ZeroMemory(&m_telemetry, sizeof(m_telemetry));
    ZeroMemory(&m_instruction, sizeof(m_instruction));
    ZeroMemory(&m_heartbeat, sizeof(m_heartbeat));
    ZeroMemory(&m_polyline, sizeof(m_polyline));
    m_hasTelemetry = FALSE;
    m_hasInstruction = FALSE;
    m_hasHeartbeat = FALSE;
    m_hasPolyline = FALSE;

    m_payloadBufferSize = 64 * 1024;
    m_pPayloadBuffer = (unsigned char*)malloc(m_payloadBufferSize);
}

NavBtClient::~NavBtClient()
{
    Stop();
    if (m_pPayloadBuffer) {
        free(m_pPayloadBuffer);
        m_pPayloadBuffer = NULL;
    }
    DeleteCriticalSection(&m_csState);
}

BOOL NavBtClient::ConfigureSerial(HANDLE h)
{
    DCB dcb;
    ZeroMemory(&dcb, sizeof(dcb));
    dcb.DCBlength = sizeof(dcb);

    if (!GetCommState(h, &dcb)) return FALSE;

    dcb.BaudRate = CBR_115200; // Fast baud rate for Bluetooth SPP
    dcb.ByteSize = 8;
    dcb.Parity = NOPARITY;
    dcb.StopBits = ONESTOPBIT;
    dcb.fBinary = TRUE;
    dcb.fOutxCtsFlow = FALSE;
    dcb.fOutxDsrFlow = FALSE;
    dcb.fDtrControl = DTR_CONTROL_ENABLE;
    dcb.fRtsControl = RTS_CONTROL_ENABLE;

    if (!SetCommState(h, &dcb)) {
        // Fallback to 9600 if 115200 is not supported
        dcb.BaudRate = CBR_9600;
        SetCommState(h, &dcb);
    }

    COMMTIMEOUTS to;
    to.ReadIntervalTimeout = 50;
    to.ReadTotalTimeoutMultiplier = 10;
    to.ReadTotalTimeoutConstant = 1000;
    to.WriteTotalTimeoutMultiplier = 5;
    to.WriteTotalTimeoutConstant = 500;
    SetCommTimeouts(h, &to);

    PurgeComm(h, PURGE_TXCLEAR | PURGE_RXCLEAR);
    return TRUE;
}

HANDLE NavBtClient::TryOpenBtPort()
{
    // COM4: is the primary Bluetooth Serial Port on Chery Arrizo 5
    // Other common ports: COM9:, COM5:, COM6:, COM7:, COM8:
    const TCHAR* ports[] = {
        TEXT("COM4:"),
        TEXT("COM9:"),
        TEXT("COM5:"),
        TEXT("COM6:"),
        TEXT("COM7:"),
        TEXT("COM8:"),
        NULL
    };

    for (int i = 0; ports[i]; i++) {
        HANDLE h = CreateFile(ports[i], GENERIC_READ | GENERIC_WRITE,
                              0, NULL, OPEN_EXISTING, 0, NULL);
        if (h != INVALID_HANDLE_VALUE) {
            if (ConfigureSerial(h)) {
                _tcscpy(m_activePort, ports[i]);
                return h;
            }
            CloseHandle(h);
        }
    }
    return INVALID_HANDLE_VALUE;
}

BOOL NavBtClient::Start(const TCHAR* portName, MapCacheManager* pCache, HWND hwndNotify)
{
    if (m_running) return TRUE;

    if (portName && portName[0]) {
        _tcsncpy(m_activePort, portName, 31);
        m_activePort[31] = TEXT('\0');
    }
    m_pCache = pCache;
    m_hwndNotify = hwndNotify;
    m_running = TRUE;

    DWORD threadId;
    m_hThread = CreateThread(NULL, 0, ThreadProc, this, 0, &threadId);
    return (m_hThread != NULL);
}

void NavBtClient::Stop()
{
    m_running = FALSE;
    if (m_hComm != INVALID_HANDLE_VALUE) {
        CloseHandle(m_hComm);
        m_hComm = INVALID_HANDLE_VALUE;
    }

    if (m_hThread) {
        WaitForSingleObject(m_hThread, 2000);
        CloseHandle(m_hThread);
        m_hThread = NULL;
    }

    m_connected = FALSE;
    _tcscpy(m_statusText, TEXT("BT: Stopped"));
}

DWORD WINAPI NavBtClient::ThreadProc(LPVOID lpParam)
{
    NavBtClient* pThis = (NavBtClient*)lpParam;
    pThis->WorkerLoop();
    return 0;
}

BOOL NavBtClient::ReadExact(HANDLE h, void* buf, DWORD bytes)
{
    DWORD total = 0;
    char* p = (char*)buf;
    while (total < bytes && m_running) {
        DWORD readNow = 0;
        if (!ReadFile(h, p + total, bytes - total, &readNow, NULL) || readNow == 0) {
            return FALSE;
        }
        total += readNow;
    }
    return (total == bytes);
}

void NavBtClient::WorkerLoop()
{
    while (m_running) {
        m_connected = FALSE;
        _stprintf(m_statusText, TEXT("Searching BT (%s)..."), m_activePort);
        if (m_hwndNotify) PostMessage(m_hwndNotify, WM_NAV_UPDATE, 0, 0);

        m_hComm = TryOpenBtPort();
        if (m_hComm == INVALID_HANDLE_VALUE) {
            Sleep(2000);
            continue;
        }

        m_connected = TRUE;
        _stprintf(m_statusText, TEXT("BT Connected (%s)"), m_activePort);
        if (m_hwndNotify) PostMessage(m_hwndNotify, WM_NAV_UPDATE, 0, 0);

        // Read stream loop
        while (m_running && m_connected) {
            // Find synchronization magic byte
            unsigned int magic = 0;
            DWORD bytesRead = 0;
            BOOL syncd = FALSE;

            // Search byte-by-byte for magic: 0x43485259 ('CHRY')
            while (m_running && m_connected) {
                unsigned char b = 0;
                if (!ReadFile(m_hComm, &b, 1, &bytesRead, NULL) || bytesRead == 0) {
                    syncd = FALSE;
                    break;
                }
                magic = (magic >> 8) | ((unsigned int)b << 24);
                if (magic == CHERY_MAGIC) {
                    syncd = TRUE;
                    break;
                }
            }

            if (!syncd) {
                // Read timeout or connection broken
                Sleep(100);
                continue;
            }

            // Magic found! Now read remaining 6 bytes of header (opcode: 2 bytes, payloadLen: 4 bytes)
            unsigned short opcode = 0;
            unsigned int payloadLen = 0;
            if (!ReadExact(m_hComm, &opcode, sizeof(opcode))) break;
            if (!ReadExact(m_hComm, &payloadLen, sizeof(payloadLen))) break;

            if (payloadLen > 1024 * 1024) {
                // Header corrupted, resync
                continue;
            }

            // Ensure buffer size
            if (payloadLen > (unsigned int)m_payloadBufferSize) {
                m_payloadBufferSize = payloadLen + 8192;
                m_pPayloadBuffer = (unsigned char*)realloc(m_pPayloadBuffer, m_payloadBufferSize);
            }

            if (payloadLen > 0) {
                if (!ReadExact(m_hComm, m_pPayloadBuffer, payloadLen)) break;
            }

            // Dispatch based on opcode
            switch (opcode) {
            case PKT_TYPE_HEARTBEAT:
                if (payloadLen >= sizeof(NavHeartbeatPayload)) {
                    EnterCriticalSection(&m_csState);
                    memcpy(&m_heartbeat, m_pPayloadBuffer, sizeof(NavHeartbeatPayload));
                    m_hasHeartbeat = TRUE;
                    LeaveCriticalSection(&m_csState);
                }
                break;

            case PKT_TYPE_TELEMETRY:
                if (payloadLen >= sizeof(NavTelemetryPayload)) {
                    EnterCriticalSection(&m_csState);
                    memcpy(&m_telemetry, m_pPayloadBuffer, sizeof(NavTelemetryPayload));
                    m_hasTelemetry = TRUE;
                    LeaveCriticalSection(&m_csState);
                }
                break;

            case PKT_TYPE_NAV_INSTRUCTION:
                if (payloadLen >= sizeof(NavInstructionPayload)) {
                    EnterCriticalSection(&m_csState);
                    memcpy(&m_instruction, m_pPayloadBuffer, sizeof(NavInstructionPayload));
                    m_hasInstruction = TRUE;
                    LeaveCriticalSection(&m_csState);
                }
                break;

            case PKT_TYPE_ROUTE_POLYLINE:
                if (payloadLen >= sizeof(unsigned short)) {
                    EnterCriticalSection(&m_csState);
                    int copyLen = min((int)payloadLen, (int)sizeof(NavRoutePolylinePayload));
                    memcpy(&m_polyline, m_pPayloadBuffer, copyLen);
                    m_hasPolyline = TRUE;
                    LeaveCriticalSection(&m_csState);
                }
                break;

            case PKT_TYPE_MAP_IMAGE:
                if (payloadLen >= sizeof(NavMapImagePayload) && m_pCache) {
                    NavMapImagePayload* pImgHdr = (NavMapImagePayload*)m_pPayloadBuffer;
                    unsigned char* pImgData = m_pPayloadBuffer + sizeof(NavMapImagePayload);
                    HDC hdcScr = GetDC(NULL);
                    m_pCache->StoreFrame(pImgHdr, pImgData, hdcScr);
                    ReleaseDC(NULL, hdcScr);
                }
                break;

            case PKT_TYPE_MAP_TILE:
                if (payloadLen >= sizeof(NavMapTilePayload) && m_pCache) {
                    NavMapTilePayload* pTileHdr = (NavMapTilePayload*)m_pPayloadBuffer;
                    unsigned char* pImgData = m_pPayloadBuffer + sizeof(NavMapTilePayload);
                    unsigned int imgLen = pTileHdr->imageBytes;
                    if (imgLen > 0 && sizeof(NavMapTilePayload) + imgLen <= payloadLen) {
                        HDC hdcScr = GetDC(NULL);
                        BOOL ok = m_pCache->StoreTile(pTileHdr->zoom, pTileHdr->tileX, pTileHdr->tileY, pImgData, imgLen, hdcScr);
                        ReleaseDC(NULL, hdcScr);
                        // Send ACK back to phone so phone saves status and never resends this tile
                        SendTileAck(pTileHdr->zoom, pTileHdr->tileX, pTileHdr->tileY, ok ? 1 : 0);
                    }
                }
                break;
            }

            if (m_hwndNotify) {
                PostMessage(m_hwndNotify, WM_NAV_UPDATE, (WPARAM)opcode, 0);
            }
        }

        if (m_hComm != INVALID_HANDLE_VALUE) {
            CloseHandle(m_hComm);
            m_hComm = INVALID_HANDLE_VALUE;
        }
        m_connected = FALSE;
        _tcscpy(m_statusText, TEXT("BT: Disconnected. Reconnecting..."));
        if (m_hwndNotify) PostMessage(m_hwndNotify, WM_NAV_UPDATE, 0, 0);
        Sleep(2000);
    }
}

void NavBtClient::GetLatestTelemetry(NavTelemetryPayload* pOut)
{
    if (!pOut) return;
    EnterCriticalSection(&m_csState);
    *pOut = m_telemetry;
    LeaveCriticalSection(&m_csState);
}

void NavBtClient::GetLatestInstruction(NavInstructionPayload* pOut)
{
    if (!pOut) return;
    EnterCriticalSection(&m_csState);
    *pOut = m_instruction;
    LeaveCriticalSection(&m_csState);
}

void NavBtClient::GetLatestHeartbeat(NavHeartbeatPayload* pOut)
{
    if (!pOut) return;
    EnterCriticalSection(&m_csState);
    *pOut = m_heartbeat;
    LeaveCriticalSection(&m_csState);
}

int NavBtClient::GetLatestPolyline(NavRoutePolylinePayload* pOut)
{
    if (!pOut) return 0;
    int count = 0;
    EnterCriticalSection(&m_csState);
    if (m_hasPolyline) {
        *pOut = m_polyline;
        count = m_polyline.count;
    }
    LeaveCriticalSection(&m_csState);
    return count;
}

BOOL NavBtClient::SendTouch(unsigned short action, unsigned short x, unsigned short y)
{
    if (!m_connected || m_hComm == INVALID_HANDLE_VALUE) return FALSE;

    NavPacketHeader hdr;
    hdr.magic = CHERY_MAGIC;
    hdr.opcode = PKT_TYPE_TOUCH_EVENT;
    hdr.payloadLen = sizeof(NavTouchEventPayload);

    NavTouchEventPayload payload;
    payload.action = action;
    payload.x = x;
    payload.y = y;
    payload.timestamp = GetTickCount();

    DWORD written1 = 0, written2 = 0;
    WriteFile(m_hComm, &hdr, sizeof(hdr), &written1, NULL);
    WriteFile(m_hComm, &payload, sizeof(payload), &written2, NULL);

    return (written1 == sizeof(hdr) && written2 == sizeof(payload));
}

BOOL NavBtClient::SendTileAck(unsigned char zoom, unsigned int tileX, unsigned int tileY, unsigned char status)
{
    if (!m_connected || m_hComm == INVALID_HANDLE_VALUE) return FALSE;

    NavPacketHeader hdr;
    hdr.magic = CHERY_MAGIC;
    hdr.opcode = PKT_TYPE_TILE_ACK;
    hdr.payloadLen = sizeof(NavTileAckPayload);

    NavTileAckPayload payload;
    payload.zoom = zoom;
    payload.tileX = tileX;
    payload.tileY = tileY;
    payload.status = status;

    DWORD written1 = 0, written2 = 0;
    WriteFile(m_hComm, &hdr, sizeof(hdr), &written1, NULL);
    WriteFile(m_hComm, &payload, sizeof(payload), &written2, NULL);

    return (written1 == sizeof(hdr) && written2 == sizeof(payload));
}

