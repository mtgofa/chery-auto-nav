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
        val key = "${zoom}_${tileX}_${tileY}"
        if (status == 1) {
            syncedOnCar.add(key)
            // Persist to SharedPreferences so we never resend even after app restarts
            prefs.edit().putStringSet("car_cached_tiles", syncedOnCar).apply()
        }
        inFlightOrQueued.remove(key)
    }

    fun isTileCachedOnCar(zoom: Int, tileX: Long, tileY: Long): Boolean {
        return syncedOnCar.contains("${zoom}_${tileX}_${tileY}")
    }

    fun getSyncedCount(): Int = syncedOnCar.size
    fun getPendingCount(): Int = pendingSendQueue.size

    /**
     * Get or fetch tile bitmap locally on phone for phone-side display.
     */
    fun getTileBitmap(zoom: Int, tileX: Long, tileY: Long): Bitmap? {
        val file = File(tilesDir, "${zoom}_${tileX}_${tileY}.jpg")
        if (file.exists() && file.length() > 0) {
            return BitmapFactory.decodeFile(file.absolutePath)
        }
        return null
    }

    /**
     * Checks 3x3 tiles around vehicle and queues missing ones for download & sync.
     */
    fun updateVehiclePosition(lat: Double, lon: Double, zoom: Int = DEFAULT_ZOOM) {
        val curX = getTileX(lon, zoom)
        val curY = getTileY(lat, zoom)

        for (dy in -1..1) {
            for (dx in -1..1) {
                val tx = curX + dx
                val ty = curY + dy
                val key = "${zoom}_${tx}_${ty}"

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
            for (pt in points) {
                val tx = getTileX(pt.second, zoom)
                val ty = getTileY(pt.first, zoom)
                val key = "${zoom}_${tx}_${ty}"
                if (!syncedOnCar.contains(key) && !inFlightOrQueued.contains(key)) {
                    inFlightOrQueued.add(key)
                    pendingSendQueue.add(key)
                    fetchAndQueueTile(zoom, tx, ty)
                }
            }
        }
    }

    private fun fetchAndQueueTile(zoom: Int, tileX: Long, tileY: Long) {
        executor.execute {
            try {
                val file = File(tilesDir, "${zoom}_${tileX}_${tileY}.jpg")
                var jpegBytes: ByteArray? = null

                if (file.exists() && file.length() > 0) {
                    jpegBytes = file.readBytes()
                } else {
                    // Download from OpenStreetMap Tile Server
                    val urlStr = "https://tile.openstreetmap.org/$zoom/$tileX/$tileY.png"
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
                if (jpegBytes != null && !syncedOnCar.contains("${zoom}_${tileX}_${tileY}")) {
                    sendTileWhenReady(zoom, tileX, tileY, jpegBytes)
                }
            } catch (e: Exception) {
                inFlightOrQueued.remove("${zoom}_${tileX}_${tileY}")
                pendingSendQueue.remove("${zoom}_${tileX}_${tileY}")
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
