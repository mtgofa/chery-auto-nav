#ifndef MAP_CACHE_H
#define MAP_CACHE_H

#include <windows.h>
#include "../protocol.h"

#define MAX_CACHED_TILES 16

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

class MapCacheManager {
public:
    MapCacheManager();
    ~MapCacheManager();

    // Initialize or resize DIB sections
    void Clear();

    // Store a new map frame from network payload
    BOOL StoreFrame(const NavMapImagePayload* pHeader, const unsigned char* pData, HDC hdcRef);

    // Get the current active HBITMAP to render
    HBITMAP GetCurrentBitmap(int* pWidth, int* pHeight, int* pCarX, int* pCarY, float* pHeading);

    // Is there a valid map frame available?
    BOOL HasValidFrame() const;

private:
    CRITICAL_SECTION m_cs;
    CachedMapTile    m_tiles[MAX_CACHED_TILES];
    int              m_currentTileIndex;
    int              m_totalFramesReceived;

    HBITMAP CreateDIBFromRGB565(HDC hdcRef, int w, int h, const unsigned char* pSrcRGB565, void** ppBitsOut);
    HBITMAP CreateDIBFromRGB24(HDC hdcRef, int w, int h, const unsigned char* pSrcRGB24, void** ppBitsOut);
};

#endif // MAP_CACHE_H
