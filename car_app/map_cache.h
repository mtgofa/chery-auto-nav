#ifndef MAP_CACHE_H
#define MAP_CACHE_H

#include <windows.h>
#include "../protocol.h"

#define MAX_CACHED_TILES 16
#define MAX_SLIPPY_TILES 25

struct CachedMapTile {
    unsigned int  frameSeq;
    int           width;
    int           height;
    int           carX;
    int           carY;
    float         heading;
    HBITMAP       hBitmap;
    void*         pBits;
    BOOL          isValid;
    DWORD         lastUsedTick;
};

struct SlippyTileEntry {
    unsigned char zoom;
    unsigned int  tileX;
    unsigned int  tileY;
    HBITMAP       hBitmap;
    void*         pBits;
    DWORD         lastUsedTick;
    BOOL          isValid;
};

class MapCacheManager {
public:
    MapCacheManager();
    ~MapCacheManager();

    // Initialize or clear cache
    void Clear();

    // 1. Full Frame API (Wi-Fi streaming fallback)
    BOOL StoreFrame(const NavMapImagePayload* pHeader, const unsigned char* pData, HDC hdcRef);
    HBITMAP GetCurrentBitmap(int* pWidth, int* pHeight, int* pCarX, int* pCarY, float* pHeading);
    BOOL HasValidFrame() const;

    // 2. Slippy Map Tile API (Bluetooth SPP dynamic caching)
    BOOL StoreTile(unsigned char zoom, unsigned int tileX, unsigned int tileY,
                   const unsigned char* pData, unsigned int dataLen, HDC hdcRef);
    BOOL RenderSlippyTiles(HDC hdc, double carLat, double carLon, unsigned char zoom,
                           int carScreenX, int carScreenY, int viewWidth, int viewHeight);
    BOOL HasTile(unsigned char zoom, unsigned int tileX, unsigned int tileY);
    void GetTileFilePath(unsigned char zoom, unsigned int tileX, unsigned int tileY, TCHAR* outPath, int maxLen);

private:
    CRITICAL_SECTION m_cs;
    CachedMapTile    m_tiles[MAX_CACHED_TILES];
    int              m_currentTileIndex;
    int              m_totalFramesReceived;

    SlippyTileEntry  m_slippyTiles[MAX_SLIPPY_TILES];
    TCHAR            m_tilesDir[MAX_PATH];

    void InitTilesDirectory();
    HBITMAP LoadTileFromDisk(unsigned char zoom, unsigned int tileX, unsigned int tileY, HDC hdcRef, void** ppBitsOut);
    HBITMAP DecodeImageToDIB(HDC hdcRef, const unsigned char* pData, unsigned int len, int* pWidth, int* pHeight, void** ppBitsOut);
    HBITMAP CreateDIBFromRGB565(HDC hdcRef, int w, int h, const unsigned char* pSrcRGB565, void** ppBitsOut);
    HBITMAP CreateDIBFromRGB24(HDC hdcRef, int w, int h, const unsigned char* pSrcRGB24, void** ppBitsOut);
};

#endif // MAP_CACHE_H
