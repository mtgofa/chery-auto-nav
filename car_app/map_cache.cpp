#include "map_cache.h"
#include <tchar.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#define STBI_NO_STDIO
#define STBI_NO_SIMD
#define STBI_ONLY_JPEG
#define STBI_ONLY_PNG
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

MapCacheManager::MapCacheManager()
{
    InitializeCriticalSection(&m_cs);
    m_currentTileIndex = -1;
    m_totalFramesReceived = 0;

    for (int i = 0; i < MAX_CACHED_TILES; i++) {
        m_tiles[i].frameSeq = 0;
        m_tiles[i].width = 0;
        m_tiles[i].height = 0;
        m_tiles[i].carX = 0;
        m_tiles[i].carY = 0;
        m_tiles[i].heading = 0.0f;
        m_tiles[i].hBitmap = NULL;
        m_tiles[i].pBits = NULL;
        m_tiles[i].isValid = FALSE;
        m_tiles[i].lastUsedTick = 0;
    }

    for (int i = 0; i < MAX_SLIPPY_TILES; i++) {
        m_slippyTiles[i].zoom = 0;
        m_slippyTiles[i].tileX = 0;
        m_slippyTiles[i].tileY = 0;
        m_slippyTiles[i].hBitmap = NULL;
        m_slippyTiles[i].pBits = NULL;
        m_slippyTiles[i].isValid = FALSE;
        m_slippyTiles[i].lastUsedTick = 0;
    }

    InitTilesDirectory();
}

MapCacheManager::~MapCacheManager()
{
    Clear();
    DeleteCriticalSection(&m_cs);
}

void MapCacheManager::InitTilesDirectory()
{
    // 1. Try Storage Card (External SD Card)
    CreateDirectory(TEXT("\\Storage Card"), NULL);
    CreateDirectory(TEXT("\\Storage Card\\CheryNav"), NULL);
    if (CreateDirectory(TEXT("\\Storage Card\\CheryNav\\tiles"), NULL) || GetLastError() == ERROR_ALREADY_EXISTS) {
        _tcscpy(m_tilesDir, TEXT("\\Storage Card\\CheryNav\\tiles"));
        return;
    }

    // 2. Try ResidentFlash (Internal Flash)
    CreateDirectory(TEXT("\\ResidentFlash\\CheryNav"), NULL);
    if (CreateDirectory(TEXT("\\ResidentFlash\\CheryNav\\tiles"), NULL) || GetLastError() == ERROR_ALREADY_EXISTS) {
        _tcscpy(m_tilesDir, TEXT("\\ResidentFlash\\CheryNav\\tiles"));
        return;
    }

    // 3. Fallback to application directory ./tiles
    CreateDirectory(TEXT("tiles"), NULL);
    _tcscpy(m_tilesDir, TEXT("tiles"));
}

void MapCacheManager::GetTileFilePath(unsigned char zoom, unsigned int tileX, unsigned int tileY, TCHAR* outPath, int maxLen)
{
    if (!outPath || maxLen <= 0) return;
    wsprintf(outPath, TEXT("%s\\%u_%u_%u.jpg"), m_tilesDir, (UINT)zoom, (UINT)tileX, (UINT)tileY);
}

void MapCacheManager::Clear()
{
    EnterCriticalSection(&m_cs);
    for (int i = 0; i < MAX_CACHED_TILES; i++) {
        if (m_tiles[i].hBitmap) {
            DeleteObject(m_tiles[i].hBitmap);
            m_tiles[i].hBitmap = NULL;
        }
        m_tiles[i].pBits = NULL;
        m_tiles[i].isValid = FALSE;
    }
    m_currentTileIndex = -1;

    for (int i = 0; i < MAX_SLIPPY_TILES; i++) {
        if (m_slippyTiles[i].hBitmap) {
            DeleteObject(m_slippyTiles[i].hBitmap);
            m_slippyTiles[i].hBitmap = NULL;
        }
        m_slippyTiles[i].pBits = NULL;
        m_slippyTiles[i].isValid = FALSE;
    }
    LeaveCriticalSection(&m_cs);
}

HBITMAP MapCacheManager::CreateDIBFromRGB565(HDC hdcRef, int w, int h, const unsigned char* pSrcRGB565, void** ppBitsOut)
{
    BITMAPINFO bmi;
    ZeroMemory(&bmi, sizeof(bmi));
    bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bmi.bmiHeader.biWidth = w;
    bmi.bmiHeader.biHeight = -h; // Top-down DIB
    bmi.bmiHeader.biPlanes = 1;
    bmi.bmiHeader.biBitCount = 16;
    bmi.bmiHeader.biCompression = BI_BITFIELDS;

    DWORD* pMasks = (DWORD*)&bmi.bmiColors[0];
    pMasks[0] = 0xF800;
    pMasks[1] = 0x07E0;
    pMasks[2] = 0x001F;

    void* pBits = NULL;
    HBITMAP hBmp = CreateDIBSection(hdcRef, &bmi, DIB_RGB_COLORS, &pBits, NULL, 0);
    if (!hBmp || !pBits) return NULL;

    if (pSrcRGB565) {
        memcpy(pBits, pSrcRGB565, w * h * 2);
    }
    if (ppBitsOut) *ppBitsOut = pBits;
    return hBmp;
}

HBITMAP MapCacheManager::CreateDIBFromRGB24(HDC hdcRef, int w, int h, const unsigned char* pSrcRGB24, void** ppBitsOut)
{
    BITMAPINFO bmi;
    ZeroMemory(&bmi, sizeof(bmi));
    bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bmi.bmiHeader.biWidth = w;
    bmi.bmiHeader.biHeight = -h;
    bmi.bmiHeader.biPlanes = 1;
    bmi.bmiHeader.biBitCount = 24;
    bmi.bmiHeader.biCompression = BI_RGB;

    void* pBits = NULL;
    HBITMAP hBmp = CreateDIBSection(hdcRef, &bmi, DIB_RGB_COLORS, &pBits, NULL, 0);
    if (!hBmp || !pBits) return NULL;

    if (pSrcRGB24) {
        unsigned char* pDst = (unsigned char*)pBits;
        for (int i = 0; i < w * h; i++) {
            pDst[i * 3 + 0] = pSrcRGB24[i * 3 + 2]; // B
            pDst[i * 3 + 1] = pSrcRGB24[i * 3 + 1]; // G
            pDst[i * 3 + 2] = pSrcRGB24[i * 3 + 0]; // R
        }
    }
    if (ppBitsOut) *ppBitsOut = pBits;
    return hBmp;
}

HBITMAP MapCacheManager::DecodeImageToDIB(HDC hdcRef, const unsigned char* pData, unsigned int len, int* pWidth, int* pHeight, void** ppBitsOut)
{
    if (!pData || len == 0) return NULL;

    int w = 0, h = 0, comp = 0;
    unsigned char* pRgb = stbi_load_from_memory(pData, len, &w, &h, &comp, 3);
    if (!pRgb) return NULL;

    // Create 16-bit RGB565 DIB for optimal WinCE performance
    void* pBits = NULL;
    HBITMAP hBmp = CreateDIBFromRGB565(hdcRef, w, h, NULL, &pBits);
    if (hBmp && pBits) {
        unsigned short* dst = (unsigned short*)pBits;
        for (int i = 0; i < w * h; i++) {
            unsigned char r = pRgb[i * 3 + 0];
            unsigned char g = pRgb[i * 3 + 1];
            unsigned char b = pRgb[i * 3 + 2];
            dst[i] = (unsigned short)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
        }
        if (pWidth) *pWidth = w;
        if (pHeight) *pHeight = h;
        if (ppBitsOut) *ppBitsOut = pBits;
    }

    stbi_image_free(pRgb);
    return hBmp;
}

BOOL MapCacheManager::StoreFrame(const NavMapImagePayload* pHeader, const unsigned char* pData, HDC hdcRef)
{
    if (!pHeader || !pData) return FALSE;

    EnterCriticalSection(&m_cs);
    int targetIdx = (m_currentTileIndex + 1) % MAX_CACHED_TILES;
    CachedMapTile* pTile = &m_tiles[targetIdx];

    if (pTile->hBitmap) {
        DeleteObject(pTile->hBitmap);
        pTile->hBitmap = NULL;
    }

    if (pHeader->format == IMG_FMT_RGB565) {
        pTile->hBitmap = CreateDIBFromRGB565(hdcRef, pHeader->width, pHeader->height, pData, &pTile->pBits);
    } else if (pHeader->format == IMG_FMT_RGB24) {
        pTile->hBitmap = CreateDIBFromRGB24(hdcRef, pHeader->width, pHeader->height, pData, &pTile->pBits);
    } else if (pHeader->format == IMG_FMT_JPEG) {
        int w = 0, h = 0;
        pTile->hBitmap = DecodeImageToDIB(hdcRef, pData, pHeader->imageBytes, &w, &h, &pTile->pBits);
    }

    if (pTile->hBitmap) {
        pTile->frameSeq = pHeader->frameSeq;
        pTile->width = pHeader->width;
        pTile->height = pHeader->height;
        pTile->carX = pHeader->carX;
        pTile->carY = pHeader->carY;
        pTile->heading = pHeader->heading;
        pTile->isValid = TRUE;
        pTile->lastUsedTick = GetTickCount();

        m_currentTileIndex = targetIdx;
        m_totalFramesReceived++;
        LeaveCriticalSection(&m_cs);
        return TRUE;
    }

    LeaveCriticalSection(&m_cs);
    return FALSE;
}

HBITMAP MapCacheManager::GetCurrentBitmap(int* pWidth, int* pHeight, int* pCarX, int* pCarY, float* pHeading)
{
    EnterCriticalSection(&m_cs);
    if (m_currentTileIndex >= 0 && m_tiles[m_currentTileIndex].isValid) {
        CachedMapTile* pTile = &m_tiles[m_currentTileIndex];
        if (pWidth) *pWidth = pTile->width;
        if (pHeight) *pHeight = pTile->height;
        if (pCarX) *pCarX = pTile->carX;
        if (pCarY) *pCarY = pTile->carY;
        if (pHeading) *pHeading = pTile->heading;
        HBITMAP hRet = pTile->hBitmap;
        LeaveCriticalSection(&m_cs);
        return hRet;
    }
    LeaveCriticalSection(&m_cs);
    return NULL;
}

BOOL MapCacheManager::HasValidFrame() const
{
    return (m_currentTileIndex >= 0 && m_tiles[m_currentTileIndex].isValid);
}

// ----------------- Slippy Tile Caching & Rendering -----------------

BOOL MapCacheManager::HasTile(unsigned char zoom, unsigned int tileX, unsigned int tileY)
{
    EnterCriticalSection(&m_cs);
    // 1. Check in RAM cache
    for (int i = 0; i < MAX_SLIPPY_TILES; i++) {
        if (m_slippyTiles[i].isValid &&
            m_slippyTiles[i].zoom == zoom &&
            m_slippyTiles[i].tileX == tileX &&
            m_slippyTiles[i].tileY == tileY) {
            LeaveCriticalSection(&m_cs);
            return TRUE;
        }
    }
    LeaveCriticalSection(&m_cs);

    // 2. Check on Disk
    TCHAR filePath[MAX_PATH];
    GetTileFilePath(zoom, tileX, tileY, filePath, MAX_PATH);
    DWORD attr = GetFileAttributes(filePath);
    return (attr != 0xFFFFFFFF && !(attr & FILE_ATTRIBUTE_DIRECTORY));
}

BOOL MapCacheManager::StoreTile(unsigned char zoom, unsigned int tileX, unsigned int tileY,
                               const unsigned char* pData, unsigned int dataLen, HDC hdcRef)
{
    if (!pData || dataLen == 0) return FALSE;

    // 1. Save to Persistent Disk
    TCHAR filePath[MAX_PATH];
    GetTileFilePath(zoom, tileX, tileY, filePath, MAX_PATH);

    HANDLE hFile = CreateFile(filePath, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (hFile != INVALID_HANDLE_VALUE) {
        DWORD written = 0;
        WriteFile(hFile, pData, dataLen, &written, NULL);
        CloseHandle(hFile);
    }

    // 2. Decode and put into RAM cache
    int w = 0, h = 0;
    void* pBits = NULL;
    HBITMAP hBmp = DecodeImageToDIB(hdcRef, pData, dataLen, &w, &h, &pBits);
    if (!hBmp) return (hFile != INVALID_HANDLE_VALUE);

    EnterCriticalSection(&m_cs);
    // Find LRU or empty slot
    int targetSlot = 0;
    DWORD oldestTick = 0xFFFFFFFF;
    for (int i = 0; i < MAX_SLIPPY_TILES; i++) {
        if (!m_slippyTiles[i].isValid) {
            targetSlot = i;
            break;
        }
        if (m_slippyTiles[i].zoom == zoom &&
            m_slippyTiles[i].tileX == tileX &&
            m_slippyTiles[i].tileY == tileY) {
            targetSlot = i;
            break;
        }
        if (m_slippyTiles[i].lastUsedTick < oldestTick) {
            oldestTick = m_slippyTiles[i].lastUsedTick;
            targetSlot = i;
        }
    }

    if (m_slippyTiles[targetSlot].hBitmap && m_slippyTiles[targetSlot].hBitmap != hBmp) {
        DeleteObject(m_slippyTiles[targetSlot].hBitmap);
    }

    m_slippyTiles[targetSlot].zoom = zoom;
    m_slippyTiles[targetSlot].tileX = tileX;
    m_slippyTiles[targetSlot].tileY = tileY;
    m_slippyTiles[targetSlot].hBitmap = hBmp;
    m_slippyTiles[targetSlot].pBits = pBits;
    m_slippyTiles[targetSlot].isValid = TRUE;
    m_slippyTiles[targetSlot].lastUsedTick = GetTickCount();

    LeaveCriticalSection(&m_cs);
    return TRUE;
}

HBITMAP MapCacheManager::LoadTileFromDisk(unsigned char zoom, unsigned int tileX, unsigned int tileY, HDC hdcRef, void** ppBitsOut)
{
    TCHAR filePath[MAX_PATH];
    GetTileFilePath(zoom, tileX, tileY, filePath, MAX_PATH);

    HANDLE hFile = CreateFile(filePath, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (hFile == INVALID_HANDLE_VALUE) return NULL;

    DWORD size = GetFileSize(hFile, NULL);
    if (size == 0 || size > 256 * 1024) {
        CloseHandle(hFile);
        return NULL;
    }

    unsigned char* buf = (unsigned char*)malloc(size);
    if (!buf) {
        CloseHandle(hFile);
        return NULL;
    }

    DWORD readBytes = 0;
    ReadFile(hFile, buf, size, &readBytes, NULL);
    CloseHandle(hFile);

    int w = 0, h = 0;
    HBITMAP hBmp = DecodeImageToDIB(hdcRef, buf, readBytes, &w, &h, ppBitsOut);
    free(buf);
    return hBmp;
}

BOOL MapCacheManager::RenderSlippyTiles(HDC hdc, double carLat, double carLon, unsigned char zoom,
                                      int carScreenX, int carScreenY, int viewWidth, int viewHeight)
{
    if (carLat == 0.0 && carLon == 0.0) return FALSE;

    double n = (double)(1 << zoom);
    double worldPx = (carLon + 180.0) / 360.0 * n * 256.0;

    double latRad = carLat * 3.141592653589793 / 180.0;
    double sinLat = sin(latRad);
    if (sinLat > 0.9999) sinLat = 0.9999;
    if (sinLat < -0.9999) sinLat = -0.9999;
    double worldPy = (0.5 - log((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * 3.141592653589793)) * n * 256.0;

    int centerTileX = (int)(worldPx / 256.0);
    int centerTileY = (int)(worldPy / 256.0);

    HDC hdcMem = CreateCompatibleDC(hdc);
    int renderedTiles = 0;

    // Check a 3x3 or 4x3 bounding box around center
    for (int dy = -1; dy <= 2; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
            unsigned int curX = (unsigned int)(centerTileX + dx);
            unsigned int curY = (unsigned int)(centerTileY + dy);

            int screenX = (int)(curX * 256.0 - worldPx + carScreenX);
            int screenY = (int)(curY * 256.0 - worldPy + carScreenY);

            // Bounding box visibility cull
            if (screenX + 256 <= 0 || screenX >= viewWidth ||
                screenY + 256 <= 0 || screenY >= viewHeight) {
                continue;
            }

            EnterCriticalSection(&m_cs);
            HBITMAP hBmp = NULL;
            int foundIdx = -1;
            for (int i = 0; i < MAX_SLIPPY_TILES; i++) {
                if (m_slippyTiles[i].isValid &&
                    m_slippyTiles[i].zoom == zoom &&
                    m_slippyTiles[i].tileX == curX &&
                    m_slippyTiles[i].tileY == curY) {
                    hBmp = m_slippyTiles[i].hBitmap;
                    m_slippyTiles[i].lastUsedTick = GetTickCount();
                    foundIdx = i;
                    break;
                }
            }
            LeaveCriticalSection(&m_cs);

            // If not in RAM, try loading from disk
            if (!hBmp) {
                void* pBits = NULL;
                hBmp = LoadTileFromDisk(zoom, curX, curY, hdc, &pBits);
                if (hBmp) {
                    EnterCriticalSection(&m_cs);
                    int targetSlot = 0;
                    DWORD oldest = 0xFFFFFFFF;
                    for (int i = 0; i < MAX_SLIPPY_TILES; i++) {
                        if (!m_slippyTiles[i].isValid) { targetSlot = i; break; }
                        if (m_slippyTiles[i].lastUsedTick < oldest) {
                            oldest = m_slippyTiles[i].lastUsedTick;
                            targetSlot = i;
                        }
                    }
                    if (m_slippyTiles[targetSlot].hBitmap) {
                        DeleteObject(m_slippyTiles[targetSlot].hBitmap);
                    }
                    m_slippyTiles[targetSlot].zoom = zoom;
                    m_slippyTiles[targetSlot].tileX = curX;
                    m_slippyTiles[targetSlot].tileY = curY;
                    m_slippyTiles[targetSlot].hBitmap = hBmp;
                    m_slippyTiles[targetSlot].pBits = pBits;
                    m_slippyTiles[targetSlot].isValid = TRUE;
                    m_slippyTiles[targetSlot].lastUsedTick = GetTickCount();
                    LeaveCriticalSection(&m_cs);
                }
            }

            if (hBmp) {
                HBITMAP hOld = (HBITMAP)SelectObject(hdcMem, hBmp);
                BitBlt(hdc, screenX, screenY, 256, 256, hdcMem, 0, 0, SRCCOPY);
                SelectObject(hdcMem, hOld);
                renderedTiles++;
            }
        }
    }

    DeleteDC(hdcMem);
    return (renderedTiles > 0);
}
