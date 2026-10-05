package com.chery.autonav.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.chery.autonav.protocol.ProtocolConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.*

class TileManager(private val context: Context, private val sendPacketToCar: (ByteArray) -> Unit) {

    private val tilesDir: File = File(context.filesDir, "map_tiles").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("chery_tile_cache", Context.MODE_PRIVATE)

    // Set of tiles already confirmed received and saved by the car head unit
    private val syncedOnCar = ConcurrentHashMap.newKeySet<String>()

    // Queue of tiles waiting to be transmitted to the car over Bluetooth
    private val pendingSendQueue = ConcurrentLinkedQueue<String>()
    private val inFlightOrQueued = ConcurrentHashMap.newKeySet<String>()

    private val executor = Executors.newFixedThreadPool(2)
    // Separate pool for phone-display tile downloads so they never starve the
    // car Bluetooth sync queue.
    private val displayExecutor = Executors.newFixedThreadPool(3)
    private val displayInFlight = ConcurrentHashMap.newKeySet<String>()
    private var lastSendTime = 0L

    companion object {
        const val DEFAULT_ZOOM = 16

        fun getTileX(lon: Double, zoom: Int): Long {
            return floor((lon + 180.0) / 360.0 * (1L shl zoom)).toLong()
        }

        fun getTileY(lat: Double, zoom: Int): Long {
            val latRad = Math.toRadians(lat)
            val sinLat = sin(latRad).coerceIn(-0.9999, 0.9999)
            val y = (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)) * (1L shl zoom)
            return floor(y).toLong()
        }
    }

    init {
        // Load known cached tiles on car from persistent preferences
        val savedSet = prefs.getStringSet("car_cached_tiles", emptySet()) ?: emptySet()
        syncedOnCar.addAll(savedSet)
    }

    fun onTileAckReceived(zoom: Int, tileX: Long, tileY: Long, status: Int) {
        // ACKs come back without style info; mark every style-variant as synced
        // so we never resend the same tile twice.
        if (status == 1) {
            var changed = false
            for (src in TileSources.ALL) {
                val key = tileKey(src.id, zoom, tileX, tileY)
                if (syncedOnCar.add(key)) changed = true
            }
            if (changed) {
                prefs.edit().putStringSet("car_cached_tiles", syncedOnCar).apply()
            }
        }
        // Drop any in-flight marker for this tile (any style).
        inFlightOrQueued.removeIf { it.endsWith("_${zoom}_${tileX}_${tileY}") }
    }

    fun isTileCachedOnCar(zoom: Int, tileX: Long, tileY: Long): Boolean {
        val style = TileSources.getSelectedId(context)
        return syncedOnCar.contains(tileKey(style, zoom, tileX, tileY))
    }

    fun getSyncedCount(): Int = syncedOnCar.size
    fun getPendingCount(): Int = pendingSendQueue.size

    /**
     * Get or fetch tile bitmap locally on phone for phone-side display.
     * File name is prefixed with the selected style so switching styles
     * shows the new look instead of stale cached images.
     */
    fun getTileBitmap(zoom: Int, tileX: Long, tileY: Long): Bitmap? {
        val style = TileSources.getSelectedId(context)
        val file = File(tilesDir, "${style}_${zoom}_${tileX}_${tileY}.jpg")
        if (file.exists() && file.length() > 0) {
            return BitmapFactory.decodeFile(file.absolutePath)
        }
        // Backward compat: fall back to legacy unprefixed cache (OSM era).
        val legacy = File(tilesDir, "${zoom}_${tileX}_${tileY}.jpg")
        if (legacy.exists() && legacy.length() > 0) {
            return BitmapFactory.decodeFile(legacy.absolutePath)
        }
        return null
    }

    private fun tileKey(style: String, zoom: Int, tileX: Long, tileY: Long): String {
        return "${style}_${zoom}_${tileX}_${tileY}"
    }

    fun getSelectedStyleId(): String = TileSources.getSelectedId(context)

    fun getAttribution(): String = TileSources.getSource(getSelectedStyleId()).attribution

    /**
     * Called when the user picks a new style from Settings.
     * Clears in-flight state so the new look is fetched immediately.
     */
    fun setStyle(styleId: String) {
        TileSources.setSelectedId(context, styleId)
        inFlightOrQueued.clear()
        pendingSendQueue.clear()
    }

    /**
     * Checks 3x3 tiles around vehicle and queues missing ones for download & sync.
     */
    fun updateVehiclePosition(lat: Double, lon: Double, zoom: Int = DEFAULT_ZOOM) {
        val curX = getTileX(lon, zoom)
        val curY = getTileY(lat, zoom)
        val style = TileSources.getSelectedId(context)

        for (dy in -1..1) {
            for (dx in -1..1) {
                val tx = curX + dx
                val ty = curY + dy
                val key = tileKey(style, zoom, tx, ty)

                if (!syncedOnCar.contains(key) && !inFlightOrQueued.contains(key)) {
                    inFlightOrQueued.add(key)
                    pendingSendQueue.add(key)
                    fetchAndQueueTile(zoom, tx, ty)
                }
            }
        }
    }

    /**
     * Pre-cache tiles along a list of route points (e.g. from RouteManager)
     */
    fun preCacheRouteTiles(points: List<Pair<Double, Double>>, zoom: Int = DEFAULT_ZOOM) {
        executor.execute {
            val style = TileSources.getSelectedId(context)
            for (pt in points) {
                val tx = getTileX(pt.second, zoom)
                val ty = getTileY(pt.first, zoom)
                val key = tileKey(style, zoom, tx, ty)
                if (!syncedOnCar.contains(key) && !inFlightOrQueued.contains(key)) {
                    inFlightOrQueued.add(key)
                    pendingSendQueue.add(key)
                    fetchAndQueueTile(zoom, tx, ty)
                }
            }
        }
    }

    /**
     * Download every tile covering the phone's visible viewport into the local
     * cache (for on-screen display only). Unlike updateVehiclePosition this does
     * NOT enqueue tiles for Bluetooth transmission to the car — the car keeps
     * getting just the 3x3 around the vehicle to respect SPP bandwidth.
     */
    fun ensureDisplayArea(lat: Double, lon: Double, halfCols: Int, halfRows: Int, zoom: Int = DEFAULT_ZOOM) {
        val cx = getTileX(lon, zoom)
        val cy = getTileY(lat, zoom)
        val style = TileSources.getSelectedId(context)
        for (dy in -halfRows..halfRows) {
            for (dx in -halfCols..halfCols) {
                val tx = cx + dx
                val ty = cy + dy
                val file = File(tilesDir, "${tileKey(style, zoom, tx, ty)}.jpg")
                if (file.exists() && file.length() > 0) continue
                val dkey = "disp_${tileKey(style, zoom, tx, ty)}"
                if (!displayInFlight.add(dkey)) continue
                displayExecutor.execute { downloadForDisplay(style, zoom, tx, ty, dkey) }
            }
        }
    }

    private fun downloadForDisplay(style: String, zoom: Int, tileX: Long, tileY: Long, dkey: String) {
        try {
            val file = File(tilesDir, "${tileKey(style, zoom, tileX, tileY)}.jpg")
            if (file.exists() && file.length() > 0) return
            val (_, urlStr) = TileSources.buildUrl(context, zoom, tileX, tileY)
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "CheryAutoNav/1.0 (Android Vehicle Bridge)")
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val bmp = BitmapFactory.decodeStream(conn.inputStream)
                conn.inputStream.close()
                if (bmp != null) {
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                    FileOutputStream(file).use { it.write(baos.toByteArray()) }
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            // ignore; tile simply stays missing (dark) until next pass
        } finally {
            displayInFlight.remove(dkey)
        }
    }

    private fun fetchAndQueueTile(zoom: Int, tileX: Long, tileY: Long) {
        executor.execute {
            try {
                val style = TileSources.getSelectedId(context)
                val key = tileKey(style, zoom, tileX, tileY)
                val file = File(tilesDir, "${key}.jpg")
                var jpegBytes: ByteArray? = null

                if (file.exists() && file.length() > 0) {
                    jpegBytes = file.readBytes()
                } else {
                    // Download from the user-selected style (Thunderforest / CARTO / OSM)
                    val (_, urlStr) = TileSources.buildUrl(context, zoom, tileX, tileY)
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "CheryAutoNav/1.0 (Android Vehicle Bridge)")
                        connectTimeout = 5000
                        readTimeout = 5000
                    }

                    if (conn.responseCode == 200) {
                        val input = conn.inputStream
                        val bmp = BitmapFactory.decodeStream(input)
                        input.close()

                        if (bmp != null) {
                            val baos = ByteArrayOutputStream()
                            // Compress as JPEG (Quality 80) -> size is around 10-15 KB
                            bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                            jpegBytes = baos.toByteArray()
                            FileOutputStream(file).use { it.write(jpegBytes) }
                        }
                    }
                    conn.disconnect()
                }

                // If tile ready and not yet synced, send when turn comes
                if (jpegBytes != null && !syncedOnCar.contains(key)) {
                    sendTileWhenReady(zoom, tileX, tileY, jpegBytes)
                } else if (jpegBytes == null) {
                    inFlightOrQueued.remove(key)
                    pendingSendQueue.remove(key)
                }
            } catch (e: Exception) {
                val style = TileSources.getSelectedId(context)
                val key = tileKey(style, zoom, tileX, tileY)
                inFlightOrQueued.remove(key)
                pendingSendQueue.remove(key)
            }
        }
    }

    /**
     * Send over Bluetooth SPP with rate limiting (1 tile every 1.2s)
     * so it doesn't starve the 10Hz Telemetry packets.
     */
    @Synchronized
    private fun sendTileWhenReady(zoom: Int, tileX: Long, tileY: Long, bytes: ByteArray) {
        val now = System.currentTimeMillis()
        val delay = (1200L - (now - lastSendTime)).coerceAtLeast(0L)
        if (delay > 0) {
            Thread.sleep(delay)
        }
        val pkt = ProtocolConstants.createMapTilePacket(
            zoom = zoom,
            tileX = tileX,
            tileY = tileY,
            imageData = bytes,
            format = ProtocolConstants.IMG_FMT_JPEG
        )
        sendPacketToCar(pkt)
        lastSendTime = System.currentTimeMillis()
    }
}
