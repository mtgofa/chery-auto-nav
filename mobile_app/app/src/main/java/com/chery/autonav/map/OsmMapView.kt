package com.chery.autonav.map

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class OsmMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var tileManager: TileManager? = null

    private var carLat: Double = 30.0444
    private var carLon: Double = 31.2357
    private var headingDeg: Float = 0f
    private var zoom: Int = 16

    private var routePoints: List<Pair<Double, Double>> = emptyList()

    // Paints
    private val bgPaint = Paint().apply { color = Color.rgb(20, 24, 30) }
    private val gridPaint = Paint().apply {
        color = Color.rgb(32, 38, 48)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val routeGlowPaint = Paint().apply {
        color = Color.argb(120, 0, 180, 255)
        strokeWidth = 18f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint().apply {
        color = Color.rgb(0, 220, 255)
        strokeWidth = 10f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val carHaloPaint = Paint().apply {
        color = Color.argb(180, 0, 140, 255)
        style = Paint.Style.FILL
    }
    private val carPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val badgeBgPaint = Paint().apply {
        color = Color.argb(180, 15, 20, 28)
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint().apply {
        color = Color.rgb(0, 220, 255)
        textSize = 30f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    fun updateVehicle(lat: Double, lon: Double, heading: Float) {
        this.carLat = lat
        this.carLon = lon
        this.headingDeg = heading
        invalidate()
    }

    fun setRoute(points: List<Pair<Double, Double>>) {
        this.routePoints = points
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // 1. Draw Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Compute Mercator pixels
        val n = (1L shl zoom).toDouble()
        val worldPx = (carLon + 180.0) / 360.0 * n * 256.0
        val latRad = Math.toRadians(carLat)
        val sinLat = sin(latRad).coerceIn(-0.9999, 0.9999)
        val worldPy = (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)) * n * 256.0

        val centerTileX = (worldPx / 256.0).toInt()
        val centerTileY = (worldPy / 256.0).toInt()

        // 3. Render 3x3 surrounding tiles
        var renderedTiles = 0
        for (dy in -2..2) {
            for (dx in -2..2) {
                val tx = (centerTileX + dx).toLong()
                val ty = (centerTileY + dy).toLong()

                val screenX = (tx * 256.0 - worldPx + cx).toFloat()
                val screenY = (ty * 256.0 - worldPy + cy).toFloat()

                if (screenX + 256f <= 0 || screenX >= w || screenY + 256f <= 0 || screenY >= h) {
                    continue
                }

                val bmp = tileManager?.getTileBitmap(zoom, tx, ty)
                if (bmp != null && !bmp.isRecycled) {
                    canvas.drawBitmap(bmp, screenX, screenY, null)
                    renderedTiles++
                } else {
                    // Draw Tile Placeholder Outline
                    canvas.drawRect(screenX, screenY, screenX + 256f, screenY + 256f, gridPaint)
                }
            }
        }

        // 4. Draw Route Polyline
        if (routePoints.isNotEmpty()) {
            val path = Path()
            var first = true

            for (pt in routePoints) {
                val ptPx = (pt.second + 180.0) / 360.0 * n * 256.0
                val ptLatRad = Math.toRadians(pt.first)
                val ptSinLat = sin(ptLatRad).coerceIn(-0.9999, 0.9999)
                val ptPy = (0.5 - ln((1.0 + ptSinLat) / (1.0 - ptSinLat)) / (4.0 * PI)) * n * 256.0

                val sx = (ptPx - worldPx + cx).toFloat()
                val sy = (ptPy - worldPy + cy).toFloat()

                if (first) {
                    path.moveTo(sx, sy)
                    first = false
                } else {
                    path.lineTo(sx, sy)
                }
            }

            canvas.drawPath(path, routeGlowPaint)
            canvas.drawPath(path, routePaint)
        }

        // 5. Draw Vehicle Marker at (cx, cy)
        canvas.drawCircle(cx, cy, 26f, carHaloPaint)

        canvas.save()
        canvas.rotate(headingDeg, cx, cy)
        val carPath = Path().apply {
            moveTo(cx, cy - 22f)
            lineTo(cx - 14f, cy + 16f)
            lineTo(cx, cy + 8f)
            lineTo(cx + 14f, cy + 16f)
            close()
        }
        canvas.drawPath(carPath, carPaint)
        canvas.restore()

        // 6. Draw Status HUD Overlay
        val synced = tileManager?.getSyncedCount() ?: 0
        val pending = tileManager?.getPendingCount() ?: 0
        val statusMsg = "Car Screen Cache: $synced tiles synced" + (if (pending > 0) " ($pending pending)" else " (Up-to-date)")

        val badgeRect = RectF(20f, 20f, w - 20f, 85f)
        canvas.drawRoundRect(badgeRect, 16f, 16f, badgeBgPaint)
        canvas.drawText(statusMsg, 40f, 62f, badgeTextPaint)
    }
}
