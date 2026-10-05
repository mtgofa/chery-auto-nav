#include <windows.h>
#include <tchar.h>
#include <stdio.h>
#include <math.h>

#include "bt_client.h"
#include "map_cache.h"
#include "../protocol.h"

#define SCREEN_WIDTH   800
#define SCREEN_HEIGHT  480

#define TIMER_REFRESH  1
#define TIMER_ANIMATE  2

#define WM_NAV_UPDATE  (WM_USER + 100)

// Global Objects
static HINSTANCE        g_hInst = NULL;
static HWND             g_hWnd = NULL;
static NavBtClient      g_btClient;
static MapCacheManager  g_mapCache;

// UI & Layout state
static BOOL  g_fullMapMode = FALSE;
static BOOL  g_nightMode = TRUE;

// Fonts
static HFONT g_hFontHuge = NULL;
static HFONT g_hFontTitle = NULL;
static HFONT g_hFontSubtitle = NULL;
static HFONT g_hFontLabel = NULL;
static HFONT g_hFontSmall = NULL;

// UI Button Rectangles
static RECT  g_rcBtnZoomIn   = { 520, 20, 570, 70 };
static RECT  g_rcBtnZoomOut  = { 520, 85, 570, 135 };
static RECT  g_rcBtnRecenter = { 520, 150, 570, 200 };
static RECT  g_rcBtnMode     = { 520, 215, 570, 265 };

// Forward declarations
LRESULT CALLBACK WndProc(HWND, UINT, WPARAM, LPARAM);
void DrawDashboard(HDC hdc);
void DrawTurnManeuver(HDC hdc, int x, int y, int size, int maneuverType);
void DrawCarMarker(HDC hdc, int cx, int cy, float headingDeg);

// Entry point for Windows CE
int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPTSTR lpCmdLine, int nCmdShow)
{
    g_hInst = hInstance;

    WNDCLASS wc;
    ZeroMemory(&wc, sizeof(wc));
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInstance;
    wc.hbrBackground = (HBRUSH)GetStockObject(BLACK_BRUSH);
    wc.lpszClassName = TEXT("CheryAutoNavWnd");
    RegisterClass(&wc);

    g_hWnd = CreateWindow(
        TEXT("CheryAutoNavWnd"),
        TEXT("Chery Auto Navigation"),
        WS_POPUP | WS_VISIBLE,
        0, 0, SCREEN_WIDTH, SCREEN_HEIGHT,
        NULL, NULL, hInstance, NULL
    );

    if (!g_hWnd) return 0;

    ShowWindow(g_hWnd, nCmdShow);
    UpdateWindow(g_hWnd);

    // Start Bluetooth Client on COM4: (Chery Arrizo 5 native Bluetooth SPP)
    g_btClient.Start(TEXT("COM4:"), &g_mapCache, g_hWnd);

    MSG msg;
    while (GetMessage(&msg, NULL, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    g_btClient.Stop();
    return (int)msg.wParam;
}

// Convert UTF-8 to WCHAR for WinCE
static void Utf8ToTchar(const char* utf8, TCHAR* out, int maxLen)
{
    if (!utf8 || !out || maxLen <= 0) return;
    MultiByteToWideChar(CP_UTF8, 0, utf8, -1, out, maxLen);
    out[maxLen - 1] = TEXT('\0');
}

void DrawTurnManeuver(HDC hdc, int x, int y, int size, int maneuverType)
{
    HPEN hPen = CreatePen(PS_SOLID, 4, RGB(255, 255, 255));
    HPEN hOldPen = (HPEN)SelectObject(hdc, hPen);
    HBRUSH hBrush = CreateSolidBrush(RGB(255, 255, 255));
    HBRUSH hOldBrush = (HBRUSH)SelectObject(hdc, hBrush);

    int half = size / 2;
    int cx = x + half;
    int cy = y + half;

    switch (maneuverType) {
    case MANEUVER_LEFT:
    case MANEUVER_SLIGHT_LEFT:
    case MANEUVER_SHARP_LEFT:
    {
        // Arrow pointing left
        MoveToEx(hdc, cx + 15, cy + 20, NULL);
        LineTo(hdc, cx + 15, cy - 5);
        LineTo(hdc, cx - 12, cy - 5);

        POINT pts[3] = { { cx - 18, cy - 5 }, { cx - 5, cy - 15 }, { cx - 5, cy + 5 } };
        Polygon(hdc, pts, 3);
        break;
    }
    case MANEUVER_RIGHT:
    case MANEUVER_SLIGHT_RIGHT:
    case MANEUVER_SHARP_RIGHT:
    {
        // Arrow pointing right
        MoveToEx(hdc, cx - 15, cy + 20, NULL);
        LineTo(hdc, cx - 15, cy - 5);
        LineTo(hdc, cx + 12, cy - 5);

        POINT pts[3] = { { cx + 18, cy - 5 }, { cx + 5, cy - 15 }, { cx + 5, cy + 5 } };
        Polygon(hdc, pts, 3);
        break;
    }
    case MANEUVER_UTURN:
    {
        // U-turn arc. Windows CE's GDI has no Arc(), so approximate the top
        // half of the ellipse (bbox cx-15,cy-18 .. cx+15,cy+12 -> center
        // (cx, cy-3), radius 15) with a polyline from right over the top to
        // left.
        {
            POINT uarc[17];
            for (int i = 0; i <= 16; i++) {
                double a = 3.14159265 * i / 16.0; // 0..pi
                uarc[i].x = (int)(cx + 15.0 * cos(a));
                uarc[i].y = (int)((cy - 3) - 15.0 * sin(a));
            }
            Polyline(hdc, uarc, 17);
        }
        MoveToEx(hdc, cx + 15, cy, NULL);
        LineTo(hdc, cx + 15, cy + 20);

        POINT pts[3] = { { cx - 15, cy + 22 }, { cx - 22, cy + 8 }, { cx - 8, cy + 8 } };
        Polygon(hdc, pts, 3);
        break;
    }
    case MANEUVER_DESTINATION:
    {
        // Destination Pin
        Ellipse(hdc, cx - 12, cy - 16, cx + 12, cy + 8);
        MoveToEx(hdc, cx, cy + 8, NULL);
        LineTo(hdc, cx, cy + 22);
        break;
    }
    case MANEUVER_STRAIGHT:
    default:
    {
        // Arrow pointing straight up
        MoveToEx(hdc, cx, cy + 20, NULL);
        LineTo(hdc, cx, cy - 12);

        POINT pts[3] = { { cx, cy - 20 }, { cx - 12, cy - 4 }, { cx + 12, cy - 4 } };
        Polygon(hdc, pts, 3);
        break;
    }
    }

    SelectObject(hdc, hOldPen);
    SelectObject(hdc, hOldBrush);
    DeleteObject(hPen);
    DeleteObject(hBrush);
}

void DrawCarMarker(HDC hdc, int cx, int cy, float headingDeg)
{
    float rad = (headingDeg - 90.0f) * 3.14159265f / 180.0f;
    float cosA = (float)cos(rad);
    float sinA = (float)sin(rad);

    // Car pointer triangle points
    int tipX = (int)(cx + 20 * cosA);
    int tipY = (int)(cy + 20 * sinA);
    int leftX = (int)(cx + 14 * cos(rad + 2.5f));
    int leftY = (int)(cy + 14 * sin(rad + 2.5f));
    int rightX = (int)(cx + 14 * cos(rad - 2.5f));
    int rightY = (int)(cy + 14 * sin(rad - 2.5f));

    // Outer glow / halo
    HBRUSH hHalo = CreateSolidBrush(RGB(0, 160, 255));
    HPEN hHaloPen = CreatePen(PS_SOLID, 2, RGB(255, 255, 255));
    SelectObject(hdc, hHalo);
    SelectObject(hdc, hHaloPen);
    Ellipse(hdc, cx - 18, cy - 18, cx + 18, cy + 18);

    // Arrow pointer
    HBRUSH hArrowBrush = CreateSolidBrush(RGB(255, 255, 255));
    SelectObject(hdc, hArrowBrush);
    POINT pts[3] = { { tipX, tipY }, { leftX, leftY }, { rightX, rightY } };
    Polygon(hdc, pts, 3);

    DeleteObject(hHalo);
    DeleteObject(hHaloPen);
    DeleteObject(hArrowBrush);
}

void DrawDashboard(HDC hdc)
{
    NavTelemetryPayload telem;
    NavInstructionPayload instr;
    NavHeartbeatPayload hb;
    NavRoutePolylinePayload poly;

    g_btClient.GetLatestTelemetry(&telem);
    g_btClient.GetLatestInstruction(&instr);
    g_btClient.GetLatestHeartbeat(&hb);
    int polyCount = g_btClient.GetLatestPolyline(&poly);

    int mapWidth = g_fullMapMode ? SCREEN_WIDTH : 580;
    int mapHeight = SCREEN_HEIGHT;

    int carX = mapWidth / 2;
    int carY = (int)(mapHeight * 0.65f); // Car placed slightly below center

    // Check if we have an image frame from cache
    int bmpW = 0, bmpH = 0;
    float heading = telem.bearingDeg;
    HBITMAP hMapBmp = g_mapCache.GetCurrentBitmap(&bmpW, &bmpH, &carX, &carY, &heading);

    if (hMapBmp && bmpW > 0 && bmpH > 0) {
        HDC hdcMem = CreateCompatibleDC(hdc);
        HBITMAP hOldBmp = (HBITMAP)SelectObject(hdcMem, hMapBmp);
        BitBlt(hdc, 0, 0, mapWidth, mapHeight, hdcMem, 0, 0, SRCCOPY);
        SelectObject(hdcMem, hOldBmp);
        DeleteDC(hdcMem);
        DrawCarMarker(hdc, carX, carY, telem.bearingDeg);
    } else {
        // High-Speed Vector Map Viewport (ideal for Bluetooth)
        RECT rcMap = { 0, 0, mapWidth, mapHeight };
        HBRUSH hBg = CreateSolidBrush(RGB(18, 22, 28));
        FillRect(hdc, &rcMap, hBg);
        DeleteObject(hBg);

        // Draw Coordinate Grid / Blocks
        HPEN hGridPen = CreatePen(PS_SOLID, 1, RGB(28, 34, 44));
        HPEN hOldP = (HPEN)SelectObject(hdc, hGridPen);
        for (int x = 0; x < mapWidth; x += 40) {
            MoveToEx(hdc, x, 0, NULL);
            LineTo(hdc, x, mapHeight);
        }
        for (int y = 0; y < mapHeight; y += 40) {
            MoveToEx(hdc, 0, y, NULL);
            LineTo(hdc, mapWidth, y);
        }
        SelectObject(hdc, hOldP);
        DeleteObject(hGridPen);

        // Draw Vector Roads & Route
        if (polyCount > 1) {
            // Draw Route Road Outline
            HPEN hRoadPen = CreatePen(PS_SOLID, 18, RGB(45, 54, 70));
            HPEN hOldR = (HPEN)SelectObject(hdc, hRoadPen);

            float scale = 30000.0f; // Scale factor for degree offset to pixels
            float rad = -telem.bearingDeg * 3.14159265f / 180.0f;
            float cosA = (float)cos(rad);
            float sinA = (float)sin(rad);

            POINT pts[MAX_POLYLINE_POINTS];
            for (int i = 0; i < polyCount; i++) {
                float rx = poly.points[i].lonOffset * cosA - poly.points[i].latOffset * sinA;
                float ry = poly.points[i].lonOffset * sinA + poly.points[i].latOffset * cosA;
                pts[i].x = carX + (int)(rx * scale);
                pts[i].y = carY - (int)(ry * scale);
            }
            Polyline(hdc, pts, polyCount);

            // Draw Inner Route Polyline (Bright Cyan)
            HPEN hRoutePen = CreatePen(PS_SOLID, 10, RGB(0, 220, 255));
            SelectObject(hdc, hRoutePen);
            Polyline(hdc, pts, polyCount);

            SelectObject(hdc, hOldR);
            DeleteObject(hRoadPen);
            DeleteObject(hRoutePen);
        }

        // Draw vehicle marker
        DrawCarMarker(hdc, carX, carY, telem.bearingDeg);

        // If searching / disconnected, show prompt
        if (!g_btClient.IsConnected()) {
            SetBkMode(hdc, TRANSPARENT);
            SetTextColor(hdc, RGB(255, 180, 50));
            SelectObject(hdc, g_hFontTitle);
            RECT rcPrompt = { 20, mapHeight / 2 - 30, mapWidth - 20, mapHeight / 2 + 30 };
            DrawText(hdc, g_btClient.GetStatusText(), -1, &rcPrompt, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        }
    }

    // 2. Navigation Turn-by-Turn Card (Floating Top-Left)
    if (instr.distanceToTurnM > 0 || instr.maneuverType > 0) {
        RECT rcCard = { 20, 20, 480, 115 };
        HBRUSH hCardBrush = CreateSolidBrush(RGB(15, 125, 75)); // Android Auto Green
        FillRect(hdc, &rcCard, hCardBrush);
        DeleteObject(hCardBrush);

        // Maneuver Icon
        DrawTurnManeuver(hdc, 30, 30, 60, instr.maneuverType);

        // Distance text
        TCHAR szDist[32];
        if (instr.distanceToTurnM < 1000) {
            _stprintf(szDist, TEXT("%d m"), instr.distanceToTurnM);
        } else {
            _stprintf(szDist, TEXT("%.1f km"), (float)instr.distanceToTurnM / 1000.0f);
        }

        SetBkMode(hdc, TRANSPARENT);
        SetTextColor(hdc, RGB(255, 255, 255));
        SelectObject(hdc, g_hFontTitle);
        RECT rcDist = { 105, 28, 460, 65 };
        DrawText(hdc, szDist, -1, &rcDist, DT_LEFT | DT_SINGLELINE);

        // Street Name
        TCHAR szStreet[64];
        Utf8ToTchar(instr.streetName[0] ? instr.streetName : instr.instruction, szStreet, 64);
        SelectObject(hdc, g_hFontSubtitle);
        RECT rcStreet = { 105, 68, 460, 105 };
        DrawText(hdc, szStreet, -1, &rcStreet, DT_LEFT | DT_SINGLELINE);
    }

    // 3. Quick Action Floating Buttons
    struct ButtonDef {
        RECT rc;
        const TCHAR* label;
    } buttons[] = {
        { g_rcBtnZoomIn,   TEXT("+") },
        { g_rcBtnZoomOut,  TEXT("-") },
        { g_rcBtnRecenter, TEXT("@") },
        { g_rcBtnMode,     TEXT("<>") }
    };

    for (int i = 0; i < 4; i++) {
        HBRUSH hBtnB = CreateSolidBrush(RGB(28, 32, 40));
        FillRect(hdc, &buttons[i].rc, hBtnB);
        DeleteObject(hBtnB);

        HPEN hBtnPen = CreatePen(PS_SOLID, 1, RGB(65, 75, 90));
        HPEN hOld = (HPEN)SelectObject(hdc, hBtnPen);
        SelectObject(hdc, GetStockObject(NULL_BRUSH));
        Rectangle(hdc, buttons[i].rc.left, buttons[i].rc.top, buttons[i].rc.right, buttons[i].rc.bottom);
        SelectObject(hdc, hOld);
        DeleteObject(hBtnPen);

        SetBkMode(hdc, TRANSPARENT);
        SetTextColor(hdc, RGB(220, 230, 245));
        SelectObject(hdc, g_hFontTitle);
        DrawText(hdc, buttons[i].label, -1, &buttons[i].rc, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    }

    // 4. Right Side Dashboard Panel
    if (!g_fullMapMode) {
        RECT rcSide = { 580, 0, SCREEN_WIDTH, SCREEN_HEIGHT };
        HBRUSH hSideBg = CreateSolidBrush(RGB(15, 18, 24));
        FillRect(hdc, &rcSide, hSideBg);
        DeleteObject(hSideBg);

        // Divider
        HPEN hDivPen = CreatePen(PS_SOLID, 2, RGB(45, 52, 65));
        HPEN hOld = (HPEN)SelectObject(hdc, hDivPen);
        MoveToEx(hdc, 580, 0, NULL);
        LineTo(hdc, 580, SCREEN_HEIGHT);
        SelectObject(hdc, hOld);
        DeleteObject(hDivPen);

        // Top Status Bar: Bluetooth & Phone Battery
        SetBkMode(hdc, TRANSPARENT);
        SetTextColor(hdc, g_btClient.IsConnected() ? RGB(0, 220, 120) : RGB(255, 140, 40));
        SelectObject(hdc, g_hFontSmall);
        TCHAR szBtStats[64];
        _stprintf(szBtStats, TEXT("BT: %s | Bat: %d%%"),
                  g_btClient.IsConnected() ? g_btClient.GetPortName() : TEXT("OFF"),
                  hb.batteryLevel);
        RECT rcStats = { 590, 10, SCREEN_WIDTH - 10, 30 };
        DrawText(hdc, szBtStats, -1, &rcStats, DT_LEFT | DT_SINGLELINE);

        // Speedometer Card
        RECT rcSpeedCard = { 595, 45, SCREEN_WIDTH - 15, 175 };
        HBRUSH hCardB = CreateSolidBrush(RGB(24, 28, 38));
        FillRect(hdc, &rcSpeedCard, hCardB);
        DeleteObject(hCardB);

        TCHAR szSpeed[16];
        int displaySpeed = (int)(telem.speedKmh + 0.5f);
        _stprintf(szSpeed, TEXT("%d"), displaySpeed);
        SetTextColor(hdc, RGB(0, 220, 255));
        SelectObject(hdc, g_hFontHuge);
        RECT rcSpeedNum = { 595, 55, SCREEN_WIDTH - 15, 135 };
        DrawText(hdc, szSpeed, -1, &rcSpeedNum, DT_CENTER | DT_VCENTER | DT_SINGLELINE);

        SetTextColor(hdc, RGB(150, 165, 185));
        SelectObject(hdc, g_hFontLabel);
        RECT rcUnit = { 595, 135, SCREEN_WIDTH - 15, 165 };
        DrawText(hdc, TEXT("km / h"), -1, &rcUnit, DT_CENTER | DT_SINGLELINE);

        // Trip Distance & ETA Card
        RECT rcEtaCard = { 595, 190, SCREEN_WIDTH - 15, 330 };
        HBRUSH hEtaB = CreateSolidBrush(RGB(24, 28, 38));
        FillRect(hdc, &rcEtaCard, hEtaB);
        DeleteObject(hEtaB);

        // Remaining Distance
        TCHAR szRemDist[32];
        if (instr.remainingDistanceM < 1000) {
            _stprintf(szRemDist, TEXT("%d m"), instr.remainingDistanceM);
        } else {
            _stprintf(szRemDist, TEXT("%.1f km"), (float)instr.remainingDistanceM / 1000.0f);
        }
        SetTextColor(hdc, RGB(255, 200, 50));
        SelectObject(hdc, g_hFontTitle);
        RECT rcRemDistVal = { 605, 205, SCREEN_WIDTH - 25, 240 };
        DrawText(hdc, szRemDist, -1, &rcRemDistVal, DT_CENTER | DT_SINGLELINE);

        SetTextColor(hdc, RGB(130, 140, 160));
        SelectObject(hdc, g_hFontSmall);
        RECT rcRemDistLbl = { 605, 242, SCREEN_WIDTH - 25, 260 };
        DrawText(hdc, TEXT("REMAINING"), -1, &rcRemDistLbl, DT_CENTER | DT_SINGLELINE);

        // ETA Time
        int minutes = instr.remainingTimeSec / 60;
        TCHAR szEtaTime[32];
        if (minutes < 60) {
            _stprintf(szEtaTime, TEXT("%d min"), minutes);
        } else {
            _stprintf(szEtaTime, TEXT("%dh %dm"), minutes / 60, minutes % 60);
        }
        SetTextColor(hdc, RGB(255, 255, 255));
        SelectObject(hdc, g_hFontTitle);
        RECT rcEtaVal = { 605, 270, SCREEN_WIDTH - 25, 300 };
        DrawText(hdc, szEtaTime, -1, &rcEtaVal, DT_CENTER | DT_SINGLELINE);

        SetTextColor(hdc, RGB(130, 140, 160));
        SelectObject(hdc, g_hFontSmall);
        RECT rcEtaLbl = { 605, 302, SCREEN_WIDTH - 25, 320 };
        DrawText(hdc, TEXT("EST. DURATION"), -1, &rcEtaLbl, DT_CENTER | DT_SINGLELINE);

        // Reconnect Button
        RECT rcBtnConn = { 595, 360, SCREEN_WIDTH - 15, 430 };
        HBRUSH hBtnConnB = CreateSolidBrush(g_btClient.IsConnected() ? RGB(20, 90, 50) : RGB(120, 40, 20));
        FillRect(hdc, &rcBtnConn, hBtnConnB);
        DeleteObject(hBtnConnB);

        SetTextColor(hdc, RGB(255, 255, 255));
        SelectObject(hdc, g_hFontLabel);
        DrawText(hdc, g_btClient.IsConnected() ? TEXT("BT PAIRED & SYNCED") : TEXT("TAP TO RETRY BT"),
                 -1, &rcBtnConn, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    }
}

LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
{
    switch (msg) {
    case WM_CREATE:
    {
        LOGFONT lf;
        ZeroMemory(&lf, sizeof(lf));
        _tcsncpy(lf.lfFaceName, TEXT("Tahoma"), LF_FACESIZE - 1);

        lf.lfHeight = 52;
        lf.lfWeight = FW_BOLD;
        g_hFontHuge = CreateFontIndirect(&lf);

        lf.lfHeight = 26;
        lf.lfWeight = FW_BOLD;
        g_hFontTitle = CreateFontIndirect(&lf);

        lf.lfHeight = 19;
        lf.lfWeight = FW_NORMAL;
        g_hFontSubtitle = CreateFontIndirect(&lf);

        lf.lfHeight = 16;
        lf.lfWeight = FW_BOLD;
        g_hFontLabel = CreateFontIndirect(&lf);

        lf.lfHeight = 13;
        lf.lfWeight = FW_NORMAL;
        g_hFontSmall = CreateFontIndirect(&lf);

        SetTimer(hwnd, TIMER_REFRESH, 100, NULL);
        return 0;
    }

    case WM_NAV_UPDATE:
    {
        InvalidateRect(hwnd, NULL, FALSE);
        return 0;
    }

    case WM_TIMER:
    {
        if (wParam == TIMER_REFRESH) {
            InvalidateRect(hwnd, NULL, FALSE);
        }
        return 0;
    }

    case WM_LBUTTONDOWN:
    {
        int x = LOWORD(lParam);
        int y = HIWORD(lParam);

        if (PtInRect(&g_rcBtnZoomIn, POINT{x, y})) {
            g_btClient.SendTouch(3, (unsigned short)x, (unsigned short)y);
            return 0;
        }
        if (PtInRect(&g_rcBtnZoomOut, POINT{x, y})) {
            g_btClient.SendTouch(4, (unsigned short)x, (unsigned short)y);
            return 0;
        }
        if (PtInRect(&g_rcBtnRecenter, POINT{x, y})) {
            g_btClient.SendTouch(5, (unsigned short)x, (unsigned short)y);
            return 0;
        }
        if (PtInRect(&g_rcBtnMode, POINT{x, y})) {
            g_fullMapMode = !g_fullMapMode;
            InvalidateRect(hwnd, NULL, TRUE);
            return 0;
        }

        if (!g_fullMapMode && x >= 595 && x <= SCREEN_WIDTH - 15 && y >= 360 && y <= 430) {
            g_btClient.Stop();
            g_btClient.Start(TEXT("COM4:"), &g_mapCache, hwnd);
            return 0;
        }

        g_btClient.SendTouch(0, (unsigned short)x, (unsigned short)y);
        return 0;
    }

    case WM_LBUTTONUP:
    {
        int x = LOWORD(lParam);
        int y = HIWORD(lParam);
        g_btClient.SendTouch(1, (unsigned short)x, (unsigned short)y);
        return 0;
    }

    case WM_PAINT:
    {
        PAINTSTRUCT ps;
        HDC hdc = BeginPaint(hwnd, &ps);

        HDC hdcMem = CreateCompatibleDC(hdc);
        HBITMAP hbmMem = CreateCompatibleBitmap(hdc, SCREEN_WIDTH, SCREEN_HEIGHT);
        HBITMAP hbmOld = (HBITMAP)SelectObject(hdcMem, hbmMem);

        DrawDashboard(hdcMem);

        BitBlt(hdc, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT, hdcMem, 0, 0, SRCCOPY);

        SelectObject(hdcMem, hbmOld);
        DeleteObject(hbmMem);
        DeleteDC(hdcMem);

        EndPaint(hwnd, &ps);
        return 0;
    }

    case WM_DESTROY:
    {
        KillTimer(hwnd, TIMER_REFRESH);
        if (g_hFontHuge) DeleteObject(g_hFontHuge);
        if (g_hFontTitle) DeleteObject(g_hFontTitle);
        if (g_hFontSubtitle) DeleteObject(g_hFontSubtitle);
        if (g_hFontLabel) DeleteObject(g_hFontLabel);
        if (g_hFontSmall) DeleteObject(g_hFontSmall);
        PostQuitMessage(0);
        return 0;
    }
    }
    return DefWindowProc(hwnd, msg, wParam, lParam);
}
