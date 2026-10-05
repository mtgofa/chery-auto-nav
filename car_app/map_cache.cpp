#include "map_cache.h"

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
}

MapCacheManager::~MapCacheManager()
{
    Clear();
    DeleteCriticalSection(&m_cs);
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

    // Standard RGB565 masks
    DWORD* pMasks = (DWORD*)&bmi.bmiColors[0];
    pMasks[0] = 0xF800; // Red
    pMasks[1] = 0x07E0; // Green
    pMasks[2] = 0x001F; // Blue

    void* pBits = NULL;
    HBITMAP hbm = CreateDIBSection(hdcRef, &bmi, DIB_RGB_COLORS, &pBits, NULL, 0);
    if (hbm && pBits && pSrcRGB565) {
        memcpy(pBits, pSrcRGB565, w * h * 2);
    }
    if (ppBitsOut) *ppBitsOut = pBits;
    return hbm;
}

HBITMAP MapCacheManager::CreateDIBFromRGB24(HDC hdcRef, int w, int h, const unsigned char* pSrcRGB24, void** ppBitsOut)
{
    BITMAPINFO bmi;
    ZeroMemory(&bmi, sizeof(bmi));
    bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bmi.bmiHeader.biWidth = w;
    bmi.bmiHeader.biHeight = -h; // Top-down
    bmi.bmiHeader.biPlanes = 1;
    bmi.bmiHeader.biBitCount = 24;
    bmi.bmiHeader.biCompression = BI_RGB;

    void* pBits = NULL;
    HBITMAP hbm = CreateDIBSection(hdcRef, &bmi, DIB_RGB_COLORS, &pBits, NULL, 0);
    if (hbm && pBits && pSrcRGB24) {
        memcpy(pBits, pSrcRGB24, w * h * 3);
    }
    if (ppBitsOut) *ppBitsOut = pBits;
    return hbm;
}

BOOL MapCacheManager::StoreFrame(const NavMapImagePayload* pHeader, const unsigned char* pData, HDC hdcRef)
{
    if (!pHeader || !pData || pHeader->width <= 0 || pHeader->height <= 0)
        return FALSE;

    EnterCriticalSection(&m_cs);

    // Pick a slot (round-robin / LRU replacement)
    int targetSlot = (m_currentTileIndex + 1) % MAX_CACHED_TILES;

    // If slot already has a bitmap with same dimension, we can reuse it
    CachedMapTile& slot = m_tiles[targetSlot];
    
    BOOL needRecreate = (slot.hBitmap == NULL || slot.width != pHeader->width || slot.height != pHeader->height);

    if (needRecreate) {
        if (slot.hBitmap) {
            DeleteObject(slot.hBitmap);
            slot.hBitmap = NULL;
            slot.pBits = NULL;
        }

        if (pHeader->format == IMG_FMT_RGB565) {
            slot.hBitmap = CreateDIBFromRGB565(hdcRef, pHeader->width, pHeader->height, pData, &slot.pBits);
        } else {
            slot.hBitmap = CreateDIBFromRGB24(hdcRef, pHeader->width, pHeader->height, pData, &slot.pBits);
        }
    } else {
        // Fast path: reuse existing buffer and copy memory directly
        int bytesToCopy = 0;
        if (pHeader->format == IMG_FMT_RGB565) {
            bytesToCopy = pHeader->width * pHeader->height * 2;
        } else {
            bytesToCopy = pHeader->width * pHeader->height * 3;
        }
        if (slot.pBits && pData) {
            memcpy(slot.pBits, pData, bytesToCopy);
        }
    }

    if (slot.hBitmap) {
        slot.frameSeq = pHeader->frameSeq;
        slot.width = pHeader->width;
        slot.height = pHeader->height;
        slot.carX = pHeader->carX;
        slot.carY = pHeader->carY;
        slot.heading = pHeader->heading;
        slot.isValid = TRUE;
        slot.lastUsedTick = GetTickCount();

        m_currentTileIndex = targetSlot;
        m_totalFramesReceived++;
        LeaveCriticalSection(&m_cs);
        return TRUE;
    }

    slot.isValid = FALSE;
    LeaveCriticalSection(&m_cs);
    return FALSE;
}

HBITMAP MapCacheManager::GetCurrentBitmap(int* pWidth, int* pHeight, int* pCarX, int* pCarY, float* pHeading)
{
    HBITMAP ret = NULL;
    EnterCriticalSection(&m_cs);
    if (m_currentTileIndex >= 0 && m_tiles[m_currentTileIndex].isValid) {
        const CachedMapTile& slot = m_tiles[m_currentTileIndex];
        ret = slot.hBitmap;
        if (pWidth)   *pWidth = slot.width;
        if (pHeight)  *pHeight = slot.height;
        if (pCarX)    *pCarX = slot.carX;
        if (pCarY)    *pCarY = slot.carY;
        if (pHeading) *pHeading = slot.heading;
    }
    LeaveCriticalSection(&m_cs);
    return ret;
}

BOOL MapCacheManager::HasValidFrame() const
{
    return (m_currentTileIndex >= 0 && m_tiles[m_currentTileIndex].isValid);
}
