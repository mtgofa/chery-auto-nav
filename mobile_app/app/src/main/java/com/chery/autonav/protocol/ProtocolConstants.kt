package com.chery.autonav.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object ProtocolConstants {
    const val CHERY_MAGIC = 0x43485259 // 'CHRY'

    // Server -> Client Opcodes
    const val PKT_TYPE_HEARTBEAT       = 0x0001
    const val PKT_TYPE_TELEMETRY       = 0x0002
    const val PKT_TYPE_NAV_INSTRUCTION = 0x0003
    const val PKT_TYPE_MAP_IMAGE       = 0x0004
    const val PKT_TYPE_ROUTE_INFO      = 0x0005
    const val PKT_TYPE_ROUTE_POLYLINE  = 0x0006
    const val PKT_TYPE_MAP_TILE        = 0x0007

    // Client -> Server Opcodes
    const val PKT_TYPE_TOUCH_EVENT     = 0x0010
    const val PKT_TYPE_CLIENT_STATUS   = 0x0011
    const val PKT_TYPE_TILE_ACK        = 0x0012

    // Maneuver Types
    const val MANEUVER_NONE            = 0
    const val MANEUVER_STRAIGHT        = 1
    const val MANEUVER_SLIGHT_LEFT     = 2
    const val MANEUVER_LEFT            = 3
    const val MANEUVER_SHARP_LEFT      = 4
    const val MANEUVER_SLIGHT_RIGHT    = 5
    const val MANEUVER_RIGHT           = 6
    const val MANEUVER_SHARP_RIGHT     = 7
    const val MANEUVER_UTURN           = 8
    const val MANEUVER_ROUNDABOUT      = 9
    const val MANEUVER_DESTINATION     = 10

    // Image Formats
    const val IMG_FMT_RGB565           = 0
    const val IMG_FMT_RGB24            = 1
    const val IMG_FMT_BMP              = 2
    const val IMG_FMT_JPEG             = 3

    // Create binary header
    fun createHeader(opcode: Int, payloadLen: Int): ByteArray {
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CHERY_MAGIC)
        buffer.putShort(opcode.toShort())
        buffer.putInt(payloadLen)
        return buffer.array()
    }

    // Pack Heartbeat
    fun createHeartbeatPacket(batteryLevel: Int, isCharging: Boolean, wifiSignal: Int, btConnected: Boolean): ByteArray {
        val payloadLen = 8
        val buffer = ByteBuffer.allocate(10 + payloadLen).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CHERY_MAGIC)
        buffer.putShort(PKT_TYPE_HEARTBEAT.toShort())
        buffer.putInt(payloadLen)

        buffer.putInt((System.currentTimeMillis() / 1000).toInt())
        buffer.put(batteryLevel.coerceIn(0, 100).toByte())
        buffer.put(if (isCharging) 1.toByte() else 0.toByte())
        buffer.put(wifiSignal.coerceIn(0, 4).toByte())
        buffer.put(if (btConnected) 1.toByte() else 0.toByte())

        return buffer.array()
    }

    // Pack Telemetry
    fun createTelemetryPacket(
        lat: Double,
        lon: Double,
        speedKmh: Float,
        bearingDeg: Float,
        altitudeM: Float = 0f,
        accuracyM: Float = 5f,
        hasFix: Boolean = true,
        numSats: Int = 12
    ): ByteArray {
        val payloadLen = 8 + 8 + 4 + 4 + 4 + 4 + 1 + 1 // 34 bytes
        val buffer = ByteBuffer.allocate(10 + payloadLen).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CHERY_MAGIC)
        buffer.putShort(PKT_TYPE_TELEMETRY.toShort())
        buffer.putInt(payloadLen)

        buffer.putDouble(lat)
        buffer.putDouble(lon)
        buffer.putFloat(speedKmh)
        buffer.putFloat(bearingDeg)
        buffer.putFloat(altitudeM)
        buffer.putFloat(accuracyM)
        buffer.put(if (hasFix) 1.toByte() else 0.toByte())
        buffer.put(numSats.toByte())

        return buffer.array()
    }

    // Pack Turn-by-Turn Instruction
    fun createInstructionPacket(
        maneuver: Int,
        distToTurnM: Int,
        remDistM: Int,
        remTimeSec: Int,
        streetName: String,
        instruction: String
    ): ByteArray {
        val streetBytes = ByteArray(64)
        val instrBytes = ByteArray(128)
        val sB = streetName.toByteArray(StandardCharsets.UTF_8)
        val iB = instruction.toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(sB, 0, streetBytes, 0, minOf(sB.size, 63))
        System.arraycopy(iB, 0, instrBytes, 0, minOf(iB.size, 127))

        val payloadLen = 2 + 4 + 4 + 4 + 64 + 128 // 206 bytes
        val buffer = ByteBuffer.allocate(10 + payloadLen).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CHERY_MAGIC)
        buffer.putShort(PKT_TYPE_NAV_INSTRUCTION.toShort())
        buffer.putInt(payloadLen)

        buffer.putShort(maneuver.toShort())
        buffer.putInt(distToTurnM)
        buffer.putInt(remDistM)
        buffer.putInt(remTimeSec)
        buffer.put(streetBytes)
        buffer.put(instrBytes)

        return buffer.array()
    }

    // Pack Map Image Frame
    fun createMapImagePacket(
        frameSeq: Int,
        width: Int,
        height: Int,
        carX: Int,
        carY: Int,
        heading: Float,
        imageData: ByteArray,
        format: Int = IMG_FMT_RGB565
    ): ByteArray {
        val payloadLen = 4 + 2 + 2 + 1 + 2 + 2 + 4 + 4 + imageData.size // 21 bytes header + data
        val buffer = ByteBuffer.allocate(10 + payloadLen).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CHERY_MAGIC)
        buffer.putShort(PKT_TYPE_MAP_IMAGE.toShort())
        buffer.putInt(payloadLen)

        buffer.putInt(frameSeq)
        buffer.putShort(width.toShort())
        buffer.putShort(height.toShort())
        buffer.put(format.toByte())
        buffer.putShort(carX.toShort())
        buffer.putShort(carY.toShort())
        buffer.putFloat(heading)
        buffer.putInt(imageData.size)
        buffer.put(imageData)

        return buffer.array()
    }

    // Pack Vector Route Polyline (super lightweight for Bluetooth SPP)
    fun createRoutePolylinePacket(carLat: Double, carLon: Double, points: List<Pair<Double, Double>>): ByteArray {
        val maxPoints = 80
        val count = minOf(points.size, maxPoints)
        val payloadLen = 2 + (count * 8) // uint16 count + count * (float latOffset + float lonOffset)

        val buffer = ByteBuffer.allocate(10 + payloadLen).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CHERY_MAGIC)
        buffer.putShort(PKT_TYPE_ROUTE_POLYLINE.toShort())
        buffer.putInt(payloadLen)

        buffer.putShort(count.toShort())
        for (i in 0 until count) {
            val pt = points[i]
            buffer.putFloat((pt.first - carLat).toFloat())
            buffer.putFloat((pt.second - carLon).toFloat())
        }

        return buffer.array()
    }

    data class TouchEvent(val action: Int, val x: Int, val y: Int, val timestamp: Long)

    fun parseTouchEvent(payloadBytes: ByteArray): TouchEvent? {
        if (payloadBytes.size < 8) return null
        val buffer = ByteBuffer.wrap(payloadBytes).order(ByteOrder.LITTLE_ENDIAN)
        val action = buffer.short.toInt() and 0xFFFF
        val x = buffer.short.toInt() and 0xFFFF
        val y = buffer.short.toInt() and 0xFFFF
        val timestamp = buffer.int.toLong() and 0xFFFFFFFFL
        return TouchEvent(action, x, y, timestamp)
    }

    // 7. Slippy Map Tile (Phone -> Car)
    fun createMapTilePacket(
        zoom: Int,
        tileX: Long,
        tileY: Long,
        imageData: ByteArray,
        format: Int = IMG_FMT_JPEG
    ): ByteArray {
        val payloadLen = 1 + 4 + 4 + 1 + 4 + imageData.size // 14 bytes header + imageData
        val buffer = ByteBuffer.allocate(10 + payloadLen).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CHERY_MAGIC)
        buffer.putShort(PKT_TYPE_MAP_TILE.toShort())
        buffer.putInt(payloadLen)

        buffer.put(zoom.toByte())
        buffer.putInt(tileX.toInt())
        buffer.putInt(tileY.toInt())
        buffer.put(format.toByte())
        buffer.putInt(imageData.size)
        buffer.put(imageData)

        return buffer.array()
    }

    // 8. Tile Reception Acknowledgment (Car -> Phone)
    data class TileAck(val zoom: Int, val tileX: Long, val tileY: Long, val status: Int)

    fun parseTileAck(payloadBytes: ByteArray): TileAck? {
        if (payloadBytes.size < 10) return null
        val buffer = ByteBuffer.wrap(payloadBytes).order(ByteOrder.LITTLE_ENDIAN)
        val zoom = buffer.get().toInt() and 0xFF
        val tileX = buffer.int.toLong() and 0xFFFFFFFFL
        val tileY = buffer.int.toLong() and 0xFFFFFFFFL
        val status = buffer.get().toInt() and 0xFF
        return TileAck(zoom, tileX, tileY, status)
    }
}
