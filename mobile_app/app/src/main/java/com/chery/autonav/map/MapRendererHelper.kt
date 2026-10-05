package com.chery.autonav.map

import android.graphics.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.*

class MapRendererHelper(val viewWidth: Int = 580, val viewHeight: Int = 480) {

    private var zoomLevel: Float = 16.0f
    private val bitmap: Bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.RGB_565)
    private val canvas: Canvas = Canvas(bitmap)

    // Paints
    private val bgPaint = Paint().apply { color = Color.rgb(20, 24, 30) }
    private val gridPaint = Paint().apply {
        color = Color.rgb(32, 38, 48)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val roadPaint = Paint().apply {
        color = Color.rgb(55, 65, 82)
        strokeWidth = 22f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val roadCenterPaint = Paint().apply {
        color = Color.rgb(80, 92, 112)
        strokeWidth = 18f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routeGlowPaint = Paint().apply {
        color = Color.argb(120, 0, 180, 255)
        strokeWidth = 20f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint().apply {
        color = Color.rgb(0, 220, 255)
        strokeWidth = 12f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint().apply {
        color = Color.rgb(180, 195, 215)
        textSize = 20f
        isAntiAlias = true
    }

    fun zoomIn() {
        zoomLevel = (zoomLevel + 0.5f).coerceAtMost(19.0f)
    }

    fun zoomOut() {
        zoomLevel = (zoomLevel - 0.5f).coerceAtLeast(12.0f)
    }

    /**
     * Renders map frame with route polyline, roads, and places vehicle at carX, carY.
     * Returns raw RGB565 byte array ready to send to WinCE.
     */
    fun renderMapFrame(
        carLat: Double,
        carLon: Double,
        headingDeg: Float,
        routePoints: List<Pair<Double, Double>>
    ): ByteArray {
        val carX = viewWidth / 2
        val carY = (viewHeight * 0.65f).toInt() // Position car slightly below center (perspective mode)

        // Clear background
        canvas.drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), bgPaint)

        // Draw coordinate grid
        val step = 60f
        var x = 0f
        while (x < viewWidth) {
            canvas.drawLine(x, 0f, x, viewHeight.toFloat(), gridPaint)
            x += step
        }
        var y = 0f
        while (y < viewHeight) {
            canvas.drawLine(0f, y, viewWidth.toFloat(), y, gridPaint)
            y += step
        }

        // Draw surrounding synthetic road network based on lat/lon
        val roadOffsets = listOf(-160f, -60f, 80f, 180f)
        for (off in roadOffsets) {
            val rY = (carY + off) % viewHeight
            canvas.drawLine(0f, rY, viewWidth.toFloat(), rY, roadPaint)
            canvas.drawLine(0f, rY, viewWidth.toFloat(), rY, roadCenterPaint)
        }
        for (off in roadOffsets) {
            val rX = (carX + off) % viewWidth
            canvas.drawLine(rX, 0f, rX, viewHeight.toFloat(), roadPaint)
            canvas.drawLine(rX, 0f, rX, viewHeight.toFloat(), roadCenterPaint)
        }

        // Draw active navigation Route polyline if available
        if (routePoints.isNotEmpty()) {
            val path = Path()
            var first = true

            // Convert GPS delta to screen pixels relative to car
            val scale = 250000.0f * (zoomLevel / 16.0f)

            for (pt in routePoints) {
                val dLat = (pt.first - carLat)
                val dLon = (pt.second - carLon)

                // Rotate points opposite to vehicle heading for "Head-Up" navigation
                val rad = -Math.toRadians(headingDeg.toDouble())
                val rx = dLon * cos(rad) - dLat * sin(rad)
                val ry = dLon * sin(rad) + dLat * cos(rad)

                val screenX = (carX + rx * scale).toFloat()
                val screenY = (carY - ry * scale).toFloat()

                if (first) {
                    path.moveTo(screenX, screenY)
                    first = false
                } else {
                    path.lineTo(screenX, screenY)
                }
            }

            // Draw route with glow
            canvas.drawPath(path, routeGlowPaint)
            canvas.drawPath(path, routePaint)
        }

        // Overlay Map Watermark / Scale
        canvas.drawText("Chery Live Maps", 25f, viewHeight - 25f, textPaint)

        // Convert Bitmap directly to raw RGB565 bytes
        val buffer = ByteBuffer.allocate(viewWidth * viewHeight * 2)
        bitmap.copyPixelsToBuffer(buffer)
        return buffer.array()
    }
}
