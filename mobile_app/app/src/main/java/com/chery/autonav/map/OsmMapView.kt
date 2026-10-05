package com.chery.autonav.map

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.*

class OsmMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var tileManager: TileManager? = null

    // Real vehicle coordinates from GPS / Telemetry
    private var carLat: Double = 30.0444
    private var carLon: Double = 31.2357
    private var headingDeg: Float = 0f

    // Camera center (can be panned away from the vehicle)
    private var centerLat: Double = carLat
    private var centerLon: Double = carLon

    // Modes
    var isFollowingCar: Boolean = true
        private set
    var isCourseUp: Boolean = true // Map rotates with car direction of travel (like Google Maps)

    // Callbacks
    var onFollowStateChanged: ((Boolean) -> Unit)? = null
    var onCompassRotationChanged: ((Float) -> Unit)? = null

    private var zoom: Int = 19
    private var routePoints: List<Pair<Double, Double>> = emptyList()
    private var lastDisplayKey: String? = null

    // Touch gesture state
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Paints
    private val bgPaint = Paint().apply { color = Color.rgb(20, 24, 30) }
    private val routeGlowPaint = Paint().apply {
        color = Color.argb(120, 0, 180, 255)
        strokeWidth = 26f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val routePaint = Paint().apply {
        color = Color.rgb(0, 220, 255)
        strokeWidth = 15f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val carHaloPaint = Paint().apply {
        color = Color.argb(65, 0, 160, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val carWhiteRingPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val carBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(50, 0, 0, 0)
        isAntiAlias = true
    }
    private val carWingLeftPaint = Paint().apply {
        color = Color.rgb(0, 229, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val carWingRightPaint = Paint().apply {
        color = Color.rgb(0, 132, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val carSpinePaint = Paint().apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val tileFilterPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }

    // Mercator projection helpers
    private fun lonToPx(lon: Double, totalPx: Double): Double = (lon + 180.0) / 360.0 * totalPx
    private fun pxToLon(px: Double, totalPx: Double): Double = (px / totalPx) * 360.0 - 180.0

    private fun latToPy(lat: Double, totalPx: Double): Double {
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val sinLat = sin(latRad).coerceIn(-0.9999, 0.9999)
        return (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * PI)) * totalPx
    }

    private fun pyToLat(py: Double, totalPx: Double): Double {
        val y = (py / totalPx).coerceIn(0.0001, 0.9999)
        val latRad = atan(sinh(PI * (1.0 - 2.0 * y)))
        return Math.toDegrees(latRad)
    }

    fun updateVehicle(lat: Double, lon: Double, heading: Float) {
        this.carLat = lat
        this.carLon = lon
        this.headingDeg = heading

        if (isFollowingCar) {
            this.centerLat = lat
            this.centerLon = lon
            onCompassRotationChanged?.invoke(if (isCourseUp) -headingDeg else 0f)
        }
        invalidate()
    }

    fun recenter() {
        isFollowingCar = true
        centerLat = carLat
        centerLon = carLon
        onFollowStateChanged?.invoke(true)
        onCompassRotationChanged?.invoke(if (isCourseUp) -headingDeg else 0f)
        lastDisplayKey = null
        invalidate()
    }

    fun toggleCourseUp(): Boolean {
        isCourseUp = !isCourseUp
        onCompassRotationChanged?.invoke(if (isCourseUp && isFollowingCar) -headingDeg else 0f)
        invalidate()
        return isCourseUp
    }

    fun setRoute(points: List<Pair<Double, Double>>) {
        this.routePoints = points
        invalidate()
    }

    fun setZoomLevel(newZoom: Int) {
        val clamped = newZoom.coerceIn(14, 20)
        if (clamped != zoom) {
            zoom = clamped
            lastDisplayKey = null
            invalidate()
        }
    }

    fun getZoomLevel(): Int = zoom

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            if (factor > 1.25f && zoom < 20) {
                setZoomLevel(zoom + 1)
                return true
            } else if (factor < 0.8f && zoom > 14) {
                setZoomLevel(zoom - 1)
                return true
            }
            return false
        }
    })

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        android.util.Log.e("OsmMapViewTouch", "action=${event.actionMasked}, x=${event.x}, y=${event.y}")
        scaleDetector.onTouchEvent(event)

        val density = resources.displayMetrics.density
        val displayScale = 1.65
        val tileSize = 256.0 * displayScale
        val n = (1L shl zoom).toDouble()
        val totalPx = n * tileSize

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isDragging = false
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val newIndex = if (event.actionIndex == 0) 1 else 0
                lastTouchX = event.getX(newIndex)
                lastTouchY = event.getY(newIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY

                    if (!isDragging) {
                        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        if (dist > touchSlop) {
                            isDragging = true
                            if (isFollowingCar) {
                                isFollowingCar = false
                                post {
                                    onFollowStateChanged?.invoke(false)
                                    onCompassRotationChanged?.invoke(0f)
                                }
                            }
                        }
                    }

                    if (isDragging) {
                        val rot = if (isFollowingCar && isCourseUp) -headingDeg else 0f
                        val rad = Math.toRadians(-rot.toDouble())
                        val mapDx = dx * cos(rad) - dy * sin(rad)
                        val mapDy = dx * sin(rad) + dy * cos(rad)

                        val curPx = lonToPx(centerLon, totalPx) - mapDx
                        val curPy = latToPy(centerLat, totalPx) - mapDy
                        centerLon = pxToLon(curPx, totalPx)
                        centerLat = pyToLat(curPy, totalPx)

                        lastTouchX = event.x
                        lastTouchY = event.y
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val density = resources.displayMetrics.density
        val displayScale = 1.65
        val tileSize = 256.0 * displayScale

        // Center screen anchor: when tracking the vehicle, anchor slightly below center (54%)
        // so more of the road ahead is visible. When free panning, anchor dead center (50%).
        val cx = w / 2f
        val cy = if (isFollowingCar) h * 0.54f else h / 2f

        // 1. Draw Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Compute Mercator pixels for center
        val n = (1L shl zoom).toDouble()
        val totalPx = n * tileSize
        val centerWorldPx = lonToPx(centerLon, totalPx)
        val centerWorldPy = latToPy(centerLat, totalPx)

        val centerTileX = floor(centerWorldPx / tileSize).toInt()
        val centerTileY = floor(centerWorldPy / tileSize).toInt()

        // 3. Viewport coverage: size tile radius to cover diagonal even when rotated
        val maxRadius = hypot(w.toDouble(), h.toDouble()) / 2.0 + tileSize
        val halfCols = ceil(maxRadius / tileSize).toInt() + 1
        val halfRows = ceil(maxRadius / tileSize).toInt() + 1

        val centerKey = "${zoom}/${centerTileX}/${centerTileY}/${tileManager?.getSelectedStyleId()}"
        if (centerKey != lastDisplayKey) {
            lastDisplayKey = centerKey
            tileManager?.ensureDisplayArea(centerLat, centerLon, halfCols, halfRows, zoom)
        }

        // Course-Up rotation: map rotates so travel direction is UP (like Google Maps)
        val mapRotation = if (isFollowingCar && isCourseUp) -headingDeg else 0f

        canvas.save()
        if (mapRotation != 0f) {
            canvas.rotate(mapRotation, cx, cy)
        }

        // Draw Map Tiles
        val dstRect = RectF()
        for (dy in -halfRows..halfRows) {
            for (dx in -halfCols..halfCols) {
                val tx = (centerTileX + dx).toLong()
                val ty = (centerTileY + dy).toLong()

                val screenX = (tx * tileSize - centerWorldPx + cx).toFloat()
                val screenY = (ty * tileSize - centerWorldPy + cy).toFloat()

                val bmp = tileManager?.getTileBitmap(zoom, tx, ty)
                if (bmp != null && !bmp.isRecycled) {
                    dstRect.set(screenX, screenY, (screenX + tileSize).toFloat(), (screenY + tileSize).toFloat())
                    canvas.drawBitmap(bmp, null, dstRect, tileFilterPaint)
                }
            }
        }

        // 4. Draw Route Polyline
        if (routePoints.isNotEmpty()) {
            val path = Path()
            var first = true

            for (pt in routePoints) {
                val ptPx = lonToPx(pt.second, totalPx)
                val ptPy = latToPy(pt.first, totalPx)

                val sx = (ptPx - centerWorldPx + cx).toFloat()
                val sy = (ptPy - centerWorldPy + cy).toFloat()

                if (first) {
                    path.moveTo(sx, sy)
                    first = false
                } else {
                    path.lineTo(sx, sy)
                }
            }

            routeGlowPaint.strokeWidth = 26f * (density / 2.75f)
            routePaint.strokeWidth = 15f * (density / 2.75f)
            canvas.drawPath(path, routeGlowPaint)
            canvas.drawPath(path, routePaint)
        }

        // 5. Draw Vehicle Marker
        val carWorldPx = lonToPx(carLon, totalPx)
        val carWorldPy = latToPy(carLat, totalPx)
        val carScreenX = (carWorldPx - centerWorldPx + cx).toFloat()
        val carScreenY = (carWorldPy - centerWorldPy + cy).toFloat()

        // Vehicle heading relative to the rotated canvas
        val chevronRotation = if (mapRotation != 0f) 0f else headingDeg

        canvas.save()
        canvas.translate(carScreenX, carScreenY)
        canvas.rotate(chevronRotation)

        val dp = density

        // Forward illumination beam
        val beamPath = Path().apply {
            moveTo(0f, 0f)
            lineTo(-32f * dp, -85f * dp)
            lineTo(32f * dp, -85f * dp)
            close()
        }
        val beamPaint = Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(
                0f, 0f, 0f, -85f * dp,
                Color.argb(95, 0, 229, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(beamPath, beamPaint)

        // Outer glow halo
        canvas.drawCircle(0f, 0f, 34f * dp, carHaloPaint)

        // White base circle (Google Maps standard ~44dp diameter puck)
        canvas.drawCircle(0f, 0f, 22f * dp, carWhiteRingPaint)

        // Border ring
        carBorderPaint.strokeWidth = 2f * dp
        canvas.drawCircle(0f, 0f, 22f * dp, carBorderPaint)

        // 3D Navigation Chevron
        val tipY = -16f * dp
        val wingLeftX = -14f * dp
        val wingLeftY = 14f * dp
        val wingRightX = 14f * dp
        val wingRightY = 14f * dp
        val notchY = 6f * dp

        // Left wing (bright electric cyan)
        val leftWing = Path().apply {
            moveTo(0f, tipY)
            lineTo(wingLeftX, wingLeftY)
            lineTo(0f, notchY)
            close()
        }
        canvas.drawPath(leftWing, carWingLeftPaint)

        // Right wing (deeper electric blue for 3D bevel)
        val rightWing = Path().apply {
            moveTo(0f, tipY)
            lineTo(wingRightX, wingRightY)
            lineTo(0f, notchY)
            close()
        }
        canvas.drawPath(rightWing, carWingRightPaint)

        // Center spine highlight
        carSpinePaint.strokeWidth = 2f * dp
        canvas.drawLine(0f, tipY, 0f, notchY, carSpinePaint)

        canvas.restore() // restore vehicle translation/rotation

        canvas.restore() // restore map rotation
    }
}
